package simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 골든 패스 부하 시나리오 — 대기열 진입/폴링 → 좌석 조회 → 좌석 홀드 → 결제 요청.
 *
 * <p>두 곳에 쓰인다(decisions.md 2·8번):
 * <ul>
 *   <li><b>카오스 테스트</b>: 중간 규모(동시 100~200)로 돌리는 동안 Pumba로 Redis/Kafka를 죽이고,
 *       오버셀 0·결제 무손실이 유지되는지 본다. 처리량 자체가 목표가 아니다.
 *   <li><b>부하 테스트 / 분산락 벤치마크</b>: {@code -Dgroup.hold.ratio=1.0}으로 좌석 2개 그룹 홀드
 *       비중을 높이고, {@code group-hold.lock-strategy}를 redis/db로 바꿔가며 처리량과 P99를 비교한다.
 * </ul>
 *
 * <p><b>대기열 진입 투입 방식({@code inject.mode})도 두 용도가 다르다</b> — 실제 티켓팅은 "오픈 시각에
 * 다같이 클릭"하는 순간 폭주지, 몇십 초에 걸쳐 서서히 들어오는 게 아니다.
 * <ul>
 *   <li>{@code chaos}(기본값): {@code burst.ratio}(기본 0.7) 비율만큼은 완전 동시(오픈 순간 몰림)로,
 *       나머지는 {@code ramp.seconds}에 걸쳐 계속 새로 유입(지각/재시도 유입) — 장애 주입 시점 이후에도
 *       트래픽이 끊기지 않고 이어지게 하기 위함.
 *   <li>{@code atonce}: {@code users} 전원을 한 시각에 동시 투입. 분산락 벤치마크에서 좌석 경합을 실제
 *       오픈 순간처럼 가장 강하게 재현할 때 쓴다.
 * </ul>
 *
 * <p><b>회원가입/로그인은 이 시나리오 안에서 하지 않는다</b> — 실제 수강신청·티켓팅처럼 사용자는 오픈
 * 훨씬 전에 이미 가입·로그인을 끝내둔 상태라고 가정한다. {@code scripts/seed-load-test.ps1}가 미리
 * BUYER 계정 풀을 만들고 각각 로그인까지 해서 {@code accessToken}을 {@code buyers.csv}에 담아두면,
 * 이 시나리오는 그 토큰을 그대로 받아 대기열 진입부터 시작한다. 로그인 BCrypt 비용이 측정 구간(특히
 * 분산락 벤치마크의 락 경합 신호, 한계 테스트의 병목 지점)을 가리는 걸 막기 위함.
 * Access Token 기본 TTL은 30분(`jwt.access-token-expiration`)이라 seed 직후 실행하면 충분히 유효하다.
 *
 * <p><b>사전 조건</b>: {@code scripts/seed-load-test.ps1}로 (1) 대상 이벤트·SEATED 구역을 오픈 상태로
 * 만들고 (2) {@code src/gatling/resources/data/buyers.csv}(email,password,accessToken)를 생성해둔다.
 * 이 스크립트가 만드는 BUYER 수보다 Gatling {@code -Dusers}가 많으면 순환(circular) feeder가 계정을
 * 재사용한다 — 테스트 규모에 맞춰 seed 시 {@code -BuyerCount}를 충분히 크게 준다.
 * 좌석 ID는 시나리오가 좌석 조회 응답에서 직접 뽑는다.
 *
 * <p><b>실행 예</b>:
 * <pre>
 *   gradlew.bat gatlingRun --simulation simulation.GoldenPathSimulation \
 *     -DbaseUrl=http://localhost:8080 -Devent.id=1 -Dsection.id=1 -Dusers=150 -Dramp.seconds=30
 * </pre>
 */
public class GoldenPathSimulation extends Simulation {

    // ── 파라미터 (전부 -D 시스템 프로퍼티로 재정의 가능) ───────────────────────────────
    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final long EVENT_ID = Long.getLong("event.id", 1L);
    private static final long SECTION_ID = Long.getLong("section.id", 1L);
    /** 좌석 2개 그룹 홀드 비중(0.0~1.0). 분산락 벤치마크에서는 1.0으로 올린다. */
    private static final double GROUP_HOLD_RATIO =
            Double.parseDouble(System.getProperty("group.hold.ratio", "0.3"));
    private static final int USERS = Integer.getInteger("users", 100);
    private static final int RAMP_SECONDS = Integer.getInteger("ramp.seconds", 20);
    /** "chaos"(버스트+지속 트리클, 기본) 또는 "atonce"(전원 완전 동시). 클래스 Javadoc 참고. */
    private static final String INJECT_MODE = System.getProperty("inject.mode", "chaos");
    /** chaos 모드에서 완전 동시로 투입할 비율(0.0~1.0), 나머지는 ramp.seconds에 걸쳐 트리클 유입. */
    private static final double BURST_RATIO = Double.parseDouble(System.getProperty("burst.ratio", "0.7"));
    /** 대기열 폴링 최대 시도 횟수 (1초 pause와 곱해 대기 상한이 된다). */
    private static final int QUEUE_POLL_MAX = Integer.getInteger("queue.poll.max", 30);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections()
            .userAgentHeader("Gatling/GoldenPathSimulation");

    /** seed-load-test.ps1이 미리 만들어둔 BUYER 계정 풀(email,password,accessToken). 순환 재사용. */
    private static final FeederBuilder<String> buyerFeeder = csv("data/buyers.csv").circular();

    /** 결제 요청 idempotencyKey는 (재사용되는 계정과 무관하게) 매 반복마다 고유해야 한다. */
    private static final AtomicLong idemSeq = new AtomicLong();

    private static Session withIdemKey(Session session) {
        return session.set("idemKey",
                System.currentTimeMillis() + "-" + idemSeq.getAndIncrement() + "-" + System.nanoTime());
    }

    /** 좌석 조회 응답에 담긴 seatId 목록에서 무작위로 1~2개를 골라 seatIdsJson 세션 변수에 넣는다. */
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

    private final ScenarioBuilder scenario = scenario("golden-path")
            .feed(buyerFeeder)
            .exec(GoldenPathSimulation::withIdemKey)
            // 1. 대기열 진입 — accessToken은 buyers.csv에서 그대로 공급받는다(로그인은 seed 단계에서 이미 끝남)
            .exec(http("queue-enter")
                    .post("/api/v1/events/" + EVENT_ID + "/queue/entries")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().in(201, 200)))
            // 2. 순번 폴링 — entryToken이 채워질 때까지 (최대 QUEUE_POLL_MAX회)
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
            // 3. 좌석 상태 조회 — 응답에서 seatId 목록을 뽑아 세션에 저장
            .exec(http("seat-list")
                    .get("/api/v1/events/" + EVENT_ID + "/seats?sectionId=" + SECTION_ID)
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .check(status().is(200))
                    .check(jsonPath("$[*].seatId").findAll().saveAs("seatIds")))
            .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
            // 4. 좌석 홀드 (지정석 1~2개). 경합으로 SEAT_ALREADY_HELD/락 타임아웃(409)이 정상적으로 날 수 있다.
            .exec(GoldenPathSimulation::pickSeats)
            .exec(http("seat-hold")
                    .post("/api/v1/events/" + EVENT_ID + "/seats/holds")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"sectionId\":" + SECTION_ID + ",\"seatIds\":#{seatIdsJson}}"))
                    .check(status().in(200, 409))
                    .check(status().saveAs("holdStatus")))
            // 5. 홀드에 성공한 유저만 결제 요청
            .doIf(session -> session.getInt("holdStatus") == 200)
            .then(exec(http("payment-request")
                    .post("/api/v1/reservations")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"eventId\":" + EVENT_ID + ",\"sectionId\":" + SECTION_ID
                            + ",\"seatIds\":#{seatIdsJson},\"idempotencyKey\":\"#{idemKey}\"}"))
                    .check(status().in(201, 409, 422))));

    /** inject.mode에 맞는 투입 프로파일. 클래스 Javadoc의 "대기열 진입 투입 방식" 참고. */
    private PopulationBuilder injectionProfile() {
        if ("atonce".equals(INJECT_MODE)) {
            return scenario.injectOpen(atOnceUsers(USERS));
        }
        int burstUsers = (int) Math.round(USERS * BURST_RATIO);
        int trickleUsers = USERS - burstUsers;
        if (trickleUsers <= 0) {
            return scenario.injectOpen(atOnceUsers(burstUsers));
        }
        return scenario.injectOpen(
                atOnceUsers(burstUsers),
                rampUsers(trickleUsers).during(Duration.ofSeconds(RAMP_SECONDS)));
    }

    {
        setUp(injectionProfile())
                .protocols(httpProtocol)
                // 카오스 중 장애 구간에서는 5xx/타임아웃이 예상되므로 어서션으로 실패시키지 않는다.
                // 정합성 검증(오버셀 0)은 테스트 후 DB/Redis 상태로 별도 확인한다.
                .maxDuration(Duration.ofMinutes(10));
    }
}
