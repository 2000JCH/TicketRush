package simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ClosedInjectionStep;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 한계 테스트 — "이 스택은 동시 몇 명까지 오버셀 없이 버티나"를 찾는다(test-plan.md 4번).
 *
 * <p>{@link GoldenPathSimulation}과 시나리오(대기열 → 좌석 조회 → 홀드 → 결제 요청)는 같지만
 * <b>투입 방식이 다르다</b>: 고정 부하가 아니라 <b>동시 사용자 수를 계단식으로 끌어올린다</b>
 * (closed model, {@code injectClosed}). 각 단계에서 P95·에러율·병목(Grafana 4패널)을 읽고,
 * 종료 조건(test-plan.md 4-2: 홀드→결제 P95 &gt; 5s / 에러율 &gt; 5% / 오버셀 &gt; 0) 중 하나라도
 * 걸리기 직전 단계가 한계치다.
 *
 * <p><b>2단계로 쓴다</b>:
 * <ul>
 *   <li><b>1차(범위 찾기, 버림)</b>: {@code -Dcapacity.mode=double -Dcapacity.level.seconds=30}
 *       — 100 → 200 → 400 → 800 …로 빠르게 올려 어느 구간에서 깨지는지만 파악.
 *   <li><b>2차(정밀, 기록)</b>: {@code -Dcapacity.mode=linear -Dcapacity.start=600 -Dcapacity.step=100
 *       -Dcapacity.max=1000 -Dcapacity.level.seconds=60} — 1차가 찾은 구간만 촘촘히, 각 단계를 길게
 *       유지해 P95를 안정적으로 읽는다. 이 수치를 test-results.md 4번에 기록.
 * </ul>
 *
 * <p><b>대기열 스로틀은 기본값(QUEUE_ADMIT_COUNT=100)으로 둔다</b> — 분산락 벤치마크와 달리 여기서는
 * 대기열 퍼널도 "시스템의 일부"다. "동시 N명이 몰려도 퍼널이 안 깨지나"를 보는 것이므로 스로틀을
 * 인위적으로 열지 않는다.
 *
 * <p><b>baseUrl은 app 컨테이너(:8080)로 직접</b> — nginx.rehearsal.conf의 대기열 진입 rate limit
 * (5r/s per IP)이 단일 호스트 부하를 전부 429로 막아 앱이 아니라 nginx 설정을 재게 되기 때문
 * (docker-compose.rehearsal.yml [nginx] 주석 참고).
 *
 * <p><b>사전 조건</b>: {@code scripts/seed-load-test.ps1}로 이벤트·SEATED 구역을 오픈 상태로 만들고
 * {@code buyers.csv}(사전 로그인된 BUYER 풀)를 생성해둔다. {@code -BuyerCount}는 도달할 최대 동시
 * 사용자 수보다 넉넉히 크게 준다(순환 feeder라 부족하면 계정을 재사용해 사재기 방지 규칙에 걸린다).
 *
 * <p><b>실행</b>: {@code scripts/run-capacity.ps1} 래퍼 사용.
 */
public class CapacitySimulation extends Simulation {

    // ── 파라미터 (전부 -D 시스템 프로퍼티로 재정의 가능) ───────────────────────────────
    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final long EVENT_ID = Long.getLong("event.id", 1L);
    private static final long SECTION_ID = Long.getLong("section.id", 1L);
    private static final double GROUP_HOLD_RATIO =
            Double.parseDouble(System.getProperty("group.hold.ratio", "0.3"));
    private static final int QUEUE_POLL_MAX = Integer.getInteger("queue.poll.max", 40);

    /** "linear"(고정 +step, 기본) 또는 "double"(start부터 2배씩). 클래스 Javadoc "2단계로 쓴다" 참고. */
    private static final String MODE = System.getProperty("capacity.mode", "linear");
    private static final int START = Integer.getInteger("capacity.start", 100);
    private static final int MAX = Integer.getInteger("capacity.max", 1000);
    private static final int STEP = Integer.getInteger("capacity.step", 100);
    private static final int LEVEL_SECONDS = Integer.getInteger("capacity.level.seconds", 60);
    private static final int RAMP_SECONDS = Integer.getInteger("capacity.ramp.seconds", 10);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections()
            .userAgentHeader("Gatling/CapacitySimulation");

    private static final FeederBuilder<String> buyerFeeder = csv("data/buyers.csv").circular();
    private static final AtomicLong idemSeq = new AtomicLong();

    private static Session withIdemKey(Session session) {
        return session.set("idemKey",
                System.currentTimeMillis() + "-" + idemSeq.getAndIncrement() + "-" + System.nanoTime());
    }

    private static Session pickSeats(Session session) {
        List<String> ids = session.getList("seatIds");
        if (ids == null || ids.isEmpty()) {
            return session.set("seatIdsJson", "[]").markAsFailed();
        }
        var rnd = ThreadLocalRandom.current();
        String first = ids.get(rnd.nextInt(ids.size()));
        boolean group = ids.size() >= 2 && rnd.nextDouble() < GROUP_HOLD_RATIO;
        if (group) {
            String second = ids.get(rnd.nextInt(ids.size()));
            if (second.equals(first)) {
                second = ids.get((ids.indexOf(first) + 1) % ids.size());
            }
            return session.set("seatIdsJson", "[" + first + "," + second + "]");
        }
        return session.set("seatIdsJson", "[" + first + "]");
    }

    private final ScenarioBuilder scenario = scenario("capacity")
            .feed(buyerFeeder)
            .exec(CapacitySimulation::withIdemKey)
            .exec(http("queue-enter")
                    .post("/api/v1/events/" + EVENT_ID + "/queue/entries")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().in(201, 200)))
            .exec(session -> session.set("entryToken", "").set("polls", 0))
            .asLongAs(session -> session.getString("entryToken").isEmpty()
                    && session.getInt("polls") < QUEUE_POLL_MAX)
            .on(
                    exec(http("queue-poll")
                            .get("/api/v1/events/" + EVENT_ID + "/queue/entries/me")
                            .header("Authorization", "Bearer #{accessToken}")
                            .check(status().is(200))
                            .check(jsonPath("$.entryToken").optional().saveAs("entryTokenMaybe")))
                            .exec(session -> {
                                String t = session.getString("entryTokenMaybe");
                                return session
                                        .set("entryToken", t == null ? "" : t)
                                        .set("polls", session.getInt("polls") + 1)
                                        .remove("entryTokenMaybe");
                            })
                            .pause(Duration.ofMillis(1000))
            )
            .doIf(session -> session.getString("entryToken").isEmpty())
            .then(exec(Session::markAsFailed).exitHereIfFailed())
            .exec(http("seat-list")
                    .get("/api/v1/events/" + EVENT_ID + "/seats?sectionId=" + SECTION_ID)
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .check(status().is(200))
                    .check(jsonPath("$[*].seatId").findAll().saveAs("seatIds")))
            .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
            .exec(CapacitySimulation::pickSeats)
            .exec(http("seat-hold")
                    .post("/api/v1/events/" + EVENT_ID + "/seats/holds")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"sectionId\":" + SECTION_ID + ",\"seatIds\":#{seatIdsJson}}"))
                    .check(status().in(200, 409))
                    .check(status().saveAs("holdStatus")))
            .doIf(session -> session.getInt("holdStatus") == 200)
            .then(exec(http("payment-request")
                    .post("/api/v1/reservations")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"eventId\":" + EVENT_ID + ",\"sectionId\":" + SECTION_ID
                            + ",\"seatIds\":#{seatIdsJson},\"idempotencyKey\":\"#{idemKey}\"}"))
                    .check(status().in(201, 409, 422))));

    /** 계단식 동시 사용자 프로파일. linear = 고정 +STEP, double = START부터 2배씩. */
    private ClosedInjectionStep[] buildStages() {
        List<Integer> levels = new ArrayList<>();
        if ("double".equals(MODE)) {
            for (int u = START; u <= MAX; u *= 2) {
                levels.add(u);
            }
        } else {
            for (int u = START; u <= MAX; u += STEP) {
                levels.add(u);
            }
        }
        var stages = new ArrayList<ClosedInjectionStep>();
        int prev = 0;
        for (int lvl : levels) {
            if (prev > 0) {
                stages.add(rampConcurrentUsers(prev).to(lvl).during(Duration.ofSeconds(RAMP_SECONDS)));
            }
            stages.add(constantConcurrentUsers(lvl).during(Duration.ofSeconds(LEVEL_SECONDS)));
            prev = lvl;
        }
        return stages.toArray(new ClosedInjectionStep[0]);
    }

    {
        setUp(scenario.injectClosed(buildStages()))
                .protocols(httpProtocol)
                // 종료 조건 판정은 테스트 후 Gatling 리포트 + Grafana + DB 정합성 SQL로 한다(test-plan.md 4-2).
                // 어서션으로 중간에 실패시키지 않는다 — 깨지는 지점까지 그래프를 봐야 하므로.
                .maxDuration(Duration.ofMinutes(30));
    }
}
