# TicketRush — 테스트 결과 (실측값)

테스트 계획·목표·절차는 `test-plan.md`. 이 문서는 **실측값만** 담는다(단일 출처).
`portfolio.md`(스토리)와 `aws-spec.md`(D·E)는 여기서 숫자를 끌어다 쓴다.

각 결과는 **측정 일자 / 커밋 해시 / 환경**을 함께 적는다 — 코드가 바뀌면 숫자도 바뀌므로.

---

## 0. 측정 환경 스냅샷

| 항목 | 값 |
|---|---|
| 측정 일자 | 2026-09-03 |
| 커밋 | `ea2de2a` (`spring.data.redis.timeout=2000` 포함) |
| 로컬 하드웨어 | AMD Ryzen 5 5600 (6코어/12스레드) / RAM 16GiB |
| 백엔드 | `gradlew bootRun` (호스트), JVM 힙 기본값 |
| 인프라 | Docker Compose (MySQL 8.0 / Redis 7.2 / Kafka cp-kafka 7.7.0 / Kafka Connect debezium 2.6), 무제한(리허설 오버레이 미적용) |
| Gatling | io.gatling.gradle 3.15.1.3 |
| 홀드 TTL / 입장 토큰 TTL | 운영 기본값(10분, `seat.hold-ttl-millis` 미조정) |

---

## 1. 카오스 A-1 — Redis 다운

> 계획: `test-plan.md` 2번 시나리오 A-1. 합격 기준: 오버셀 0 / 복구 < 30초 / rebuild 마커 재설정.
> 스크린샷 `.claude/screenshots/tests/a1-redis-down/`: `4패널_전체사진.png` / `API응답시간.png` / `요청_에러율.png` / `HikariCP_커넥션풀.png`.

| 항목 | 목표 | 실측 | 판정 |
|---|---|---|---|
| 부하 (총 390 VU) | 버스트 105(오픈 순간 동시 진입) + 트리클 45(40초 분산) + 꼬리 240(초당 2명 × 120초, 복구 관찰용) | 총 3,134 요청 (OK 1,947 / KO 1,187) | — |
| 장애 지속 | ~60s | **61s** (11:38:22 → 11:39:23 KST / 02:38:22 → 02:39:23 UTC, `docker stop`→`start` 직접) | ✅ |
| **오버셀 (SQL a)** | 0행 | **0행** | ✅ **PASS** |
| 스탠딩 초과 (SQL b) | ≤ 정원 | 지정석 전용(스탠딩 없음) — N/A | — |
| **Redis 복구 → seat_status 재구성 완료** | < 30s | **~4초** (11:39:23 복구 → Lettuce 재연결 11:39:25.9 → rebuild 완료 11:39:26.95 `occupiedSeats=144`) | ✅ **PASS** |
| rebuild 마커 재설정 | 확인 | `system:rebuild_epoch:1` 존재, `rebuild:in_progress:*` 잔여 없음 | ✅ |
| rebuild 실행 횟수 | 1회 (가드) | **1회** + 락 경합 요청 `503` 3건 (부분 재구성 상태를 아무도 안 읽음) | ✅ |
| **최대 응답시간** | (참고 — 타임아웃 수정 효과) | **2,035ms** (P95 2,011 / P99 2,020, 전체). 성공분만: P95 400ms / P99 660ms | ✅ 2초 cap 확인 |
| Redis 커맨드 타임아웃 발생 | (참고) | `Redis command timed out` 505건, 전부 ~2초 만에 실패 (라운드가 없던 이전엔 Lettuce 기본 60초였음) | — |

**관찰 메모**:
- **핵심: 폭주 + Redis 완전 유실(디스크 저장 off) 상황에서도 오버셀 0.** rebuild가 DB(`PAYMENT_CONFIRMED` + 타임아웃 안 지난 `PAYMENT_REQUESTED`)를 기준으로 `seat_status:1`을 다시 채웠고, 그 뒤 꼬리 부하가 잡은 좌석까지 `HSETNX` + `reservation_seat` 2차 방어선으로 충돌 없이 처리됐다. 종료 시 DB 활성 좌석 248개 / Redis HELD 필드 245개(±3, rebuild 스냅샷과 이후 유입 사이의 정상 시차) — 오버셀 SQL은 0행.
- **rebuild 가드 작동**: Redis가 빈 상태로 복구된 직후 여러 요청이 동시에 "마커 없음"을 감지 → 1건만 락을 잡고 재구성, 나머지는 `503 SERVICE_TEMPORARILY_UNAVAILABLE`(3건 기록)로 즉시 실패. 매진(409)과 구분되는 별도 상태.
- **타임아웃 수정 효과 확인 (`spring.data.redis.timeout=2000`, 커밋 `81fb8b7`)**: 장애 중 Redis 커맨드가 60초 매달리지 않고 2초에 끊겨 `QueryTimeoutException` → 500으로 빠르게 실패(505건). `API응답시간.png`의 P99가 장애 구간 내내 **~2.1초 평평한 천장**을 만든 뒤 복구와 함께 뚝 떨어지는 게 이 cap이다.
- **KO 1,187건 분해**: `404 QUEUE_ENTRY_NOT_FOUND` **941건(79%)** — Redis가 대기열 Sorted Set(`queue:{eventId}`)도 함께 잃어, 입장 토큰을 받았던 사용자가 재진입해야 함. **decisions.md 1번의 "알려진 한계"** (`요청_에러율.png`의 복구 직후 빨간 언덕이 이것). / `500` 장애 중 Redis 타임아웃 **~240건** / `503` rebuild 락 경합 3건 / `400 INVALID_INPUT` 3건(장애 중 `GET /seats` 실패 → Gatling이 빈 `seatIds`로 홀드 시도, 스크립트 부작용). **진짜 서버 버그성 실패는 0.**
- **HikariCP는 거의 무변화**(최대 10 → 사용 중 최대 1, 여유 최소 9): Redis 장애는 DB 커넥션 풀에 영향 없음 — `HikariCP_커넥션풀.png`.
- **Kafka Consumer Lag 0 유지**: Redis 장애가 Kafka 경로로 번지지 않음 — 4패널 전체 캡처.
- **개선 여지(안 고침)**: 장애 중 Redis 타임아웃이 일반 `500`으로 나간다 — `503`(일시적 이용 불가)이 더 적절. 다음 라운드 후보(decisions.md 11번).
- **도구 메모**: `scripts/chaos-redis.ps1`의 Pumba `stop --restart`가 이 환경에서 "no containers to stop"으로 불안정 → 이번엔 `docker stop`/`docker start`를 직접 썼다. Pumba 경로는 별도 점검 필요.

---

## 2. 카오스 A-2 — Kafka 브로커 다운

> 계획: `test-plan.md` 2번 시나리오 A-2. 합격 기준: 결제 요청 5xx 0 / 이벤트 유실 0 / lag 0 도달 < 60초.
> 측정 환경은 0번과 동일(2026-09-03, 커밋 `ea2de2a`). 스크린샷 `.claude/screenshots/tests/a2-kafka-down/`:
> `4패널_전체사진.png` / `API응답시간.png` / `요청_에러율.png` / `밀린_메시지.png`(Kafka Consumer Lag) / `HikariCP_커넥션풀.png`. 모두 시간범위 `12:38:00~12:43:15`.

| 항목 | 목표 | 실측 | 판정 |
|---|---|---|---|
| 부하 (총 390 VU) | 버스트 105 + 트리클 45 + 꼬리 240 + `fail-payments.ps1`(FailRatio 0.8, 3초 간격) 병행 | Gatling 2,210 요청 전부 OK / 웹훅 238건 전송 | — |
| 장애 지속 | ~90s | **91s** (12:39:09 → 12:40:40 KST / 02:39:09 → 02:40:40 UTC, `docker stop`→`start`) | ✅ |
| **장애 중 `payment-request` 5xx** | 0건 | **0건** (301건 전부 201) | ✅ **PASS** |
| **장애 중 웹훅(`/payments/webhook`) 5xx** | 0건 | **0건** (계속 200 → `outbox_events` 계속 INSERT) | ✅ **PASS** |
| 장애 중 미처리 이벤트 (참고, 유실 아님) | — | PAYMENT_FAILED 16건이 DB에 쌓인 채 대기 (컨슈머가 브로커 없어 소비 불가) | — |
| **복구 후 이벤트 유실** | 0 | **0** — outbox 239행 = SEAT_RELEASED 239건, PAYMENT_FAILED 0으로 드레인 | ✅ **PASS** |
| **복구 → Consumer lag 0 도달** | < 60s | **~15초** (커넥터 재시작 후) — `CURRENT-OFFSET 239 = LOG-END-OFFSET 239, LAG 0` | ✅ **PASS** |
| 장애 중 API P99 | (참고) | **54~110ms** (평상시와 동일 — Kafka 다운이 사용자 경로에 영향 없음) | ✅ |
| 오버셀 (SQL a) | 0행 | **0행** | ✅ |

**관찰 메모**:
- **핵심: Kafka가 91초 완전히 죽었는데 사용자 경로는 무영향.** `요청_에러율.png`는 장애 구간에도 초록(200·201)만 쌓이고 `401`·`503`·5xx 전부 0(`409`는 0.02 req/s = 정상적 좌석 경합). `API응답시간.png`의 P99는 장애 구간과 복구 후가 **구분 안 될 만큼 동일**(둘 다 ~60ms). 결제 요청·확정은 DB 트랜잭션 + outbox INSERT라 Kafka와 동기적으로 얽히지 않기 때문.
- **이벤트 유실 0의 근거**: 장애 중 `markPaymentFailed`가 계속 성공(웹훅 200) → `outbox_events`에 행이 쌓임(DB라 안전). 컨슈머는 그동안 아무것도 처리 못 해 PAYMENT_FAILED 16건이 대기 상태로 남음. 복구 후 Debezium이 밀린 행을 발행 → 컨슈머가 소비 → 전부 `SEAT_RELEASED`. 최종 `outbox 239 = SEAT_RELEASED 239`, `stuck_failed = 0`.
- **Debezium 커넥터가 자동 복구되지 않음**: Kafka 다운 시 커넥터가 `UNASSIGNED` 상태로 떨어지고, 브로커가 돌아와도 그대로 멈춰 있었다. `docker compose restart kafka-connect` + `register-outbox-connector.ps1` 재실행으로 복구. lag 0 도달 시간(~15초)은 이 수동 재시작 이후 기준이다. **개선 포인트**: 운영이라면 Connect 헬스체크 + 자동 재시작(또는 `errors.retry.timeout` 조정)이 필요.
- **부하 프로파일의 40초 공백(`nothingFor`)이 장애 구간 중간(12:39:25~12:40:05)에 겹쳤다.** 장애 앞 36초(버스트 잔여)와 12:40:15 이후(꼬리 부하)는 트래픽으로 덮였지만, 다음 라운드에는 이 공백을 없애/줄여 장애 전 구간을 끊김 없이 덮는 게 낫다. 판정에는 영향 없음(에러 0, 유실 0).
- **도구**: `fail-payments.ps1` 신규 — Gatling 골든패스는 결제 요청까지만 하므로, `PAYMENT_REQUESTED` 예약을 주기적으로 조회해 일부에 서명된 `Transaction.Failed` 웹훅을 쏴서 outbox→Kafka 경로를 실제로 태운다. test-plan.md A-2의 원래 검증 SQL(`SELECT status FROM outbox_events`)은 존재하지 않는 컬럼 참조라 함께 수정(outbox_events는 INSERT 전용, 발행 상태 컬럼 없음).

---

## 3. 분산락 벤치마크 — Redisson vs DB 비관적 락

> 계획: `test-plan.md` 3번. 선행 코드 수정(DB 락 3초 timeout + `GROUP_HOLD_LOCK_TIMEOUT` 매핑, 커밋 `8570d91`) 완료 후 측정.
> **측정 2026-09-03, 커밋 `8570d91`. 조건은 0번과 다름**: 좌석 **4개** / 동시 **300명 완전 동시(`atonce`)** / `group.hold.ratio=1.0` / `QUEUE_ADMIT_COUNT=1000`(대기열을 즉시 통과시켜 좌석 홀드 시점 경합을 최대화). A안(`group-hold.lock-strategy=redis`)·B안(`db`) 각각 새 이벤트로 실행.
> 40석 + 대기열 정상 투입으로 처음 돌렸을 땐 좌석 홀드 시점이 ~10초에 분산돼 락 경합이 안 생겨(P99 27ms, 락 타임아웃 0) 무효 — 좌석 수를 줄이고 대기열을 열어 재실행했다.

| 지표 | Redisson RLock | DB 비관적 락 | 비고 |
|---|---|---|---|
| **오버셀 (전제)** | **0** | **0** | 둘 다 통과 |
| 그룹 홀드 성공 | 2건 (좌석 4개) | 2건 | 동일 |
| 처리량 (Gatling req/s) | 238 | 238 | **동일** |
| 그룹 홀드(`seat-hold`) P50 | 22ms | 20ms | 동일 |
| 그룹 홀드 P95 | 43ms | 42ms | 동일 |
| **그룹 홀드 P99** | **59ms** | **80ms** | 절대값 둘 다 <100ms, Global P99는 1,032 vs 1,018ms로 거의 동일 |
| 락 실패율 (`seat-hold` 409) | 278/300 | 301/300(다음 스크레이프 포함) | 대부분 `SEAT_ALREADY_HELD`(좌석 선점) |
| 락 획득 타임아웃(`GROUP_HOLD_LOCK_TIMEOUT`) | **0건** | **0건** | 홀드 액션(HSETNX 1회)이 짧아 3초 대기까지 안 감 |
| 락 실패 형태 | 즉시 (409, <100ms) | 즉시 (409, <100ms) | 동일 |
| **먼저 포화된 자원** | **없음** — HikariCP pending 0 | **HikariCP** — pending **147**, active 10/10(풀 만석) | ← **유일한 실질 차이** |

**채택 결정: Redisson RLock** (현재 기본값 유지).

**근거**:
- 성능은 사실상 동등 — 처리량 동일, Global P99 거의 동일(1,032 vs 1,018ms), 오버셀 0, 락 타임아웃 0. `decisions.md` 2번의 "처리량·P99 20% 이내" 조건 충족.
- 2번의 tie-breaker는 "동등하면 DB 락 채택(추가 인프라 불필요)"인데, **우리는 이미 Redis를 코어로 쓴다**(seat_status·queue·hold). "Redisson = 추가 인프라"가 성립하지 않아 이 tie-breaker가 적용되지 않는다.
- 유일한 실질 차이는 **커넥션 풀 압박**: DB 락은 `REQUIRES_NEW` 트랜잭션마다 HikariCP 커넥션을 점유해 300 동시 요청에서 pending이 **147**까지 쌓였다(Redisson은 0). 이번 규모에선 타임아웃·실패 0으로 버텼지만, 부하가 커지거나(한계 테스트) RDS가 작으면 커넥션 고갈로 먼저 무너질 자원이다.
- Redis는 이미 운영 대상이라 Redisson 채택에 따르는 운영 부담 증가가 없다.

→ `decisions.md` 2번, `aws-spec.md` B-2(RDS는 `db.m6i.large` 유지 — DB 락이었으면 `db.r6i.large` 상향 필요), `portfolio.md`에 반영.

**왜 pending 147인데 응답시간 차이는 거의 없었나 (면접 대비)**:
- **풀이 빨리 순환한다.** 커넥션 하나를 쥐는 시간은 `SELECT FOR UPDATE` + `HSETNX` + 롤백 = ~10ms. 커넥션 10개면 초당 ~1,000회 대여 → pending 147은 ~150~400ms 만에 빠진다. 300명이 몰린 1초 안쪽의 순간 스파이크라 P99 전체를 못 움직인다.
- **더 큰 공통 병목에 묻혔다.** Global P99 ~1초는 락이 아니라 300명이 동시에 때린 `GET /seats`(Redis HGETALL + 좌석 DB 조회) + 대기열 폴링 + Tomcat 스레드 경합에서 나온다 — Redisson이든 DB 락이든 똑같이 걸린다. 락 자체의 차이(`seat-hold` P99 59 vs 80ms, ~20ms)는 이 공통 병목 밑에 파묻힌다.

**그래서 이 규모(300명)에선 "차이 거의 없음"이 결론이고, DB 락을 안 고른 이유는 "지금 느려서"가 아니라 "확장하면 먼저 무너지는 실패 모드가 있어서"다**:
- 동시 인원이 늘수록 pending 큐가 길어지고 커넥션 대기가 선형으로 늘다가, 대기가 **HikariCP 획득 타임아웃(기본 30초)**을 넘는 순간 `Connection is not available` **에러**가 터진다(느려짐이 아니라 절벽).
- 복합 악화: 부하가 세면 인기 좌석 행에 `FOR UPDATE`가 몰려 DB 행 락 대기 → 커넥션을 더 오래 쥠 → 풀 순환이 느려짐 → 연쇄. 풀을 키워도 MySQL `max_connections`(기본 ~150) 상한 + 커넥션 증가 시 DB 자원 경합이라 깔끔하게 확장이 안 된다.
- Redisson은 DB 커넥션을 아예 안 쓰므로(Redis 전용 풀, HikariCP와 별개) 이 실패 모드 자체가 없다.

**한계(측정)**: 두 실행 모두 ~15초로 짧아 Grafana 시계열엔 pending 스파이크가 순간값으로만 잡힌다(`max_over_time`으로 확인). 표로 대체하고 스크린샷은 생략(classq 부하 비교표와 동일 방식). 홀드 액션이 짧은 우리 설계상 락이 오래 점유되지 않아 락 기술 차이가 크지 않다는 점 자체가 결과의 일부다. 이 규모로는 DB 락이 실제로 커넥션 고갈되는 지점까지 못 밀었으므로, 필요하면 600~1,000명으로 재측정해 절벽을 실측할 수 있다.

---

## 4. 한계 테스트 — 동시 몇 명까지 (1차: 2026-09-04)

> 계획: `test-plan.md` 4번. **측정 환경이 0번과 다르다** — 리허설 스택(`docker-compose.rehearsal.yml`
> + `docker-compose.capacity.yml`, AWS 축소 스펙 리소스 제한), 앱도 컨테이너(`Dockerfile`).

### 4-0. 측정 환경

| 항목 | 값 |
|---|---|
| 측정 일자 | 2026-09-04 (커밋: 이 세션, `8f4215a` 이후 미커밋 상태에서 측정) |
| 하드웨어 | 이 세션 PC (RAM 16 GiB) — **section 0의 5600 머신과 다름** |
| Docker Desktop VM | `~/.wslconfig` `memory=8GB` + `autoMemoryReclaim=dropcache` (신규 — 없으면 VM이 스택 캐시를 안 돌려줘 호스트가 굶음) |
| 리허설 예산 | app 2 vCPU / 2400m · mysql 1.5 vCPU / 2200m · kafka 0.75/800m · connect 0.5/560m · redis 0.5/300m · nginx 0.5/160m (합 ~7 GiB — 이 PC 16 GiB에 맞춰 원안 24 GiB에서 축소) |
| 부하 시나리오 | 1차 계단식은 `CapacitySimulation`(closed model), 버스트는 `GoldenPathSimulation -InjectMode atonce` |
| 계정 풀 | `seed-buyers-parallel.mjs` / `login-buyers.mjs`로 3,000~6,000 BUYER 사전 로그인 (JWT_ACCESS_EXPIRATION=4h로 리허설 중 만료 방지) |

### 4-1. 계단식 1차 (버림 — 설계 결함)

`CapacitySimulation -Mode double -Max 800` (동시 100→200→400→800, 각 30초). **결과는 못 씀:**
이 시스템은 계정 1개당 이벤트 1건만 진행(`PAYMENT_REQUESTED` → `ACTIVE_RESERVATION_EXISTS`) → **800 계정이 ~50초에 소진**되어 이후 단계는 대부분 빈 409 hold + 대기열 폴링. 고부하가 실제로 안 만들어졌다.

- 47,050 요청 / KO 116 (0.25%, 전부 계정·좌석 소진에 따른 404·409) / **오버셀 0 / 5xx 0**
- **건진 것**: 동시 100명(정상 여정)에서 이미 **HikariCP 풀(기본 10) 포화, pending 49~80**. 400 구간 pending 189. → 병목이 DB 커넥션 풀.
- → 방식을 "버스트"(N명 완전 동시, 각자 1회)로 전환.

### 4-2. 버스트 (1,500명 완전 동시, `atonce`)

| 런 | 설정 | 경로 | queue-enter OK/KO | seat-hold OK | payment OK/KO | Global P95 / P99 | 오버셀 | 5xx |
|---|---|---|---|---|---|---|---|---|
| **버스트1** | HikariCP 10, accept 100 | `:8080` 앱 직결 | 736 / **764** (Conn refused) | 737 | 691 / 0 | 5,495 / 9,434 ms | **0** | **0** |
| **버스트A** | HikariCP 20, accept 2000 | `:8080` 앱 직결 | 823 / **677** (Conn refused) | 823 | 780 / 0 | 3,827 / 7,810 ms | **0** | **0** |
| **버스트B** | HikariCP 20, accept 2000 | `:8081` **nginx 경유** | 998 / **502** (Conn refused) | 1,245 | 898 / 134 (404) | 15,090 / 18,126 ms¹ | **0** | **0** |

¹ 버스트B의 높은 P95/P99는 앱 지연이 아니라 (1) 통과 인원이 많아 대기열이 ~1,000 깊이로 쌓여 폴링 대기가 길어진 것 + (2) Gatling이 Conn-refused를 30회 재시도한 시간. 앱 자체 5xx·오버셀은 0.

**버스트 중 리소스**(버스트A 모니터): app CPU **~197%** (2코어 cap에 붙음, 매 버스트 동일) / HikariCP active 20/20 · **pending 156~169** (풀을 10→20으로 올려도 pending은 ~160대 — mysql 1.5 vCPU가 뒤에서 못 따라옴) / 호스트 여유 메모리 최저 **375 MB**(버스트B, 스왑 직전까지 감).

### 4-3. 핵심 결론

1. **"Connection refused"는 TicketRush가 아니라 Docker Desktop의 유저랜드 포트 프록시다.**
   - `:8080`(앱)이든 `:8081`(nginx)이든 1,500 동시 연결 중 ~500~760개가 똑같이 거부됨
   - `server.tomcat.accept-count` 100→2000으로 올려도 거의 무변화 (764 → 677) — Tomcat 수용 큐는 컨테이너 **안**에 있고, 연결은 그 앞(Windows↔WSL2 프록시)에서 죽음
   - `localhost:포트` → Windows 프록시 프로세스 → WSL2 VM → 컨테이너. 이 프록시 하나가 커넥션 스톰을 못 받아 RST
   - **AWS엔 없다** — 앱이 진짜 리눅스에서 포트 직접 바인딩, ALB/nginx가 앞단. 버스트B(nginx 경유)가 앱 직결보다 더 많이 통과시킨 것도 방증(앱은 한 번도 연결 거부 안 함)
2. **정합성은 완벽하다.** 계단식 + 버스트 3회, 어떤 조건에서도 **오버셀 0 / 5xx 0**. 프록시를 통과한 요청은 전부 정상 처리 (버스트B: 좌석 홀드 1,245건 전원 성공, 결제 898건 성공).
3. **앱의 병목축 = CPU → DB CPU.** 모든 버스트에서 app CPU가 2코어 cap(197%)에 붙었고, HikariCP pending은 **풀을 10→20으로 올려도 ~160~190 그대로** — 풀 크기가 레버가 아니라 그 뒤 mysql CPU(1.5 vCPU)가 실질 상한이라는 뜻. → `aws-spec.md` C의 "병목축" = **CPU 우선**. HikariCP는 기본값 10 유지(공식 가이드도 "풀은 작게"), AWS 재측정에서 10 vs 20 비교로 이 결론만 확인. AWS `m6i.xlarge`(4 vCPU) + `db.m6i.large`(2 vCPU 전용)에서 완화 예상.
4. **payment 404 (버스트B 134건)**: `ACTIVE_HOLD_NOT_FOUND` 계열 — 대기열이 깊어 seat-hold ~ payment 사이 지연이 커지며 홀드가 스케줄러에 스윕된 것으로 추정. 5xx 아님, 정합성엔 영향 없음(오버셀 0). 재현 시 원인 확정 필요.

**한계치(로컬)**: **localhost 기반 Gatling으로는 못 냄** — Docker 프록시가 ~800~1,000 동시 연결에서 먼저 무너짐(테스트 환경 한계, decisions.md에 기록 예정). 프록시를 통과한 범위에서는 앱이 CPU-bound로 버텼고 correctness 무손상.
**그때의 병목**: 앱 CPU (2 vCPU) → HikariCP/mysql.
**진짜 숫자**: (a) AWS 재측정(section 5, 프록시 없음) 또는 (b) Gatling을 컨테이너 안에서 실행(`test-plan.md` 4-4). 둘 중 하나로 확정.

---

## 5. AWS 배포 후 재측정

> 계획: `test-plan.md` 1-4번. 로컬 예측표(`aws-spec.md` D)와 대조.

| 시나리오 | 로컬 실측 | AWS 예측 (`aws-spec.md` D) | AWS 실측 | 예측 정확도 |
|---|---|---|---|---|
| 골든 패스 P95 (300 동시) | *(대기)* | *(대기)* | *(대기)* | *(대기)* |
| 처리량 (300 동시) | *(대기)* | *(대기)* | *(대기)* | *(대기)* |
| 한계 동시 사용자 | *(대기)* | *(대기)* | *(대기)* | *(대기)* |

**SLO 달성 여부**(`test-plan.md` 1-2번 목표를 AWS에서): *(대기)*
→ 확정 시 `aws-spec.md` E, `portfolio.md` 반영.
