package simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 골든 패스 부하 시나리오 — 회원가입 → 로그인 → 대기열 진입/폴링 → 좌석 조회 → 좌석 홀드 → 결제 요청.
 *
 * <p>두 곳에 쓰인다(decisions.md 2·8번):
 * <ul>
 *   <li><b>카오스 테스트</b>: 중간 규모(동시 100~200)로 돌리는 동안 Pumba로 Redis/Kafka를 죽이고,
 *       오버셀 0·결제 무손실이 유지되는지 본다. 처리량 자체가 목표가 아니다.
 *   <li><b>부하 테스트 / 분산락 벤치마크</b>: {@code -Dgroup.hold.ratio=1.0}으로 좌석 2개 그룹 홀드
 *       비중을 높이고, {@code group-hold.lock-strategy}를 redis/db로 바꿔가며 처리량과 P99를 비교한다.
 * </ul>
 *
 * <p><b>사전 조건</b>: 대상 이벤트·SEATED 구역이 오픈된 상태로 있어야 한다. {@code scripts/seed-load-test.ps1}로
 * 만들고 출력된 event.id / section.id 를 넘긴다. 좌석 ID는 시나리오가 좌석 조회 응답에서 직접 뽑는다.
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
    /** 대기열 폴링 최대 시도 횟수 (1초 pause와 곱해 대기 상한이 된다). */
    private static final int QUEUE_POLL_MAX = Integer.getInteger("queue.poll.max", 30);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections()
            .userAgentHeader("Gatling/GoldenPathSimulation");

    /** 가상 유저마다 고유한 이메일. 실행마다 접두사(runTag)를 바꿔 이전 실행 계정과 충돌하지 않게 한다. */
    private static final Iterator<Map<String, Object>> feeder = new Iterator<>() {
        private final AtomicLong seq = new AtomicLong();
        private final long runTag = System.currentTimeMillis() / 1000;

        @Override public boolean hasNext() { return true; }

        @Override public Map<String, Object> next() {
            long n = seq.getAndIncrement();
            return Map.of(
                    "email", "load-" + runTag + "-" + n + "@ticketrush.test",
                    "password", "loadtest1234",
                    "idemKey", runTag + "-" + n + "-" + System.nanoTime());
        }
    };

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
            .feed(feeder)
            // 1. 회원가입 (BUYER). 이미 있으면 409지만 계속 진행할 수 있게 상태코드를 넓게 허용.
            .exec(http("signup")
                    .post("/api/v1/auth/signup")
                    .body(StringBody("{\"email\":\"#{email}\",\"password\":\"#{password}\",\"role\":\"BUYER\"}"))
                    .check(status().in(201, 409)))
            // 2. 로그인 → accessToken
            .exec(http("login")
                    .post("/api/v1/auth/login")
                    .body(StringBody("{\"email\":\"#{email}\",\"password\":\"#{password}\"}"))
                    .check(status().is(200))
                    .check(jsonPath("$.accessToken").saveAs("accessToken")))
            .exitHereIfFailed()
            // 3. 대기열 진입
            .exec(http("queue-enter")
                    .post("/api/v1/events/" + EVENT_ID + "/queue/entries")
                    .header("Authorization", "Bearer #{accessToken}")
                    .check(status().in(201, 200)))
            // 4. 순번 폴링 — entryToken이 채워질 때까지 (최대 QUEUE_POLL_MAX회)
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
            // 5. 좌석 상태 조회 — 응답에서 seatId 목록을 뽑아 세션에 저장
            .exec(http("seat-list")
                    .get("/api/v1/events/" + EVENT_ID + "/seats?sectionId=" + SECTION_ID)
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .check(status().is(200))
                    .check(jsonPath("$[*].seatId").findAll().saveAs("seatIds")))
            .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
            // 6. 좌석 홀드 (지정석 1~2개). 경합으로 SEAT_ALREADY_HELD/락 타임아웃(409)이 정상적으로 날 수 있다.
            .exec(GoldenPathSimulation::pickSeats)
            .exec(http("seat-hold")
                    .post("/api/v1/events/" + EVENT_ID + "/seats/holds")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"sectionId\":" + SECTION_ID + ",\"seatIds\":#{seatIdsJson}}"))
                    .check(status().in(200, 409))
                    .check(status().saveAs("holdStatus")))
            // 7. 홀드에 성공한 유저만 결제 요청
            .doIf(session -> session.getInt("holdStatus") == 200)
            .then(exec(http("payment-request")
                    .post("/api/v1/reservations")
                    .header("Authorization", "Bearer #{accessToken}")
                    .header("X-Entry-Token", "#{entryToken}")
                    .body(StringBody("{\"eventId\":" + EVENT_ID + ",\"sectionId\":" + SECTION_ID
                            + ",\"seatIds\":#{seatIdsJson},\"idempotencyKey\":\"#{idemKey}\"}"))
                    .check(status().in(201, 409, 422))));

    {
        setUp(scenario.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(RAMP_SECONDS))))
                .protocols(httpProtocol)
                // 카오스 중 장애 구간에서는 5xx/타임아웃이 예상되므로 어서션으로 실패시키지 않는다.
                // 정합성 검증(오버셀 0)은 테스트 후 DB/Redis 상태로 별도 확인한다.
                .maxDuration(Duration.ofMinutes(10));
    }
}
