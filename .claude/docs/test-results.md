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

> 계획: `test-plan.md` 3번. 선행 코드 수정(DB 락 timeout) 완료 후 측정.
> 조건: 좌석 40개 / 동시 300명 / 램프 60초 / `group.hold.ratio=1.0`.

| 지표 | Redisson RLock | DB 비관적 락 | 비고 |
|---|---|---|---|
| 오버셀 (전제) | *(대기)* | *(대기)* | 하나라도 >0이면 탈락 |
| 처리량 (req/s) | *(대기)* | *(대기)* | |
| 그룹 홀드 P50 | *(대기)* | *(대기)* | |
| 그룹 홀드 P95 | *(대기)* | *(대기)* | |
| **그룹 홀드 P99** | *(대기)* | *(대기)* | 처리량과 동등 지표 |
| 락 실패율 (409/타임아웃) | *(대기)* | *(대기)* | |
| 락 실패 형태 | *(대기 — 즉시/대기 후)* | *(대기)* | 응답시간 분포로 판단 |
| 먼저 포화된 자원 | *(대기)* | *(대기)* | HikariCP pending / 스레드 / … |

**채택 결정**: *(대기)*
**근거**: *(대기 — 20% 규칙 적용 결과, P99가 갈렸는지)*
→ 확정 시 `decisions.md` 2번, `aws-spec.md` B-2, `portfolio.md` 반영.

---

## 4. 한계 테스트 — 동시 몇 명까지

> 계획: `test-plan.md` 4번. 계단식 부하로 종료 조건(P95>5s / 에러율>5% / 오버셀>0)에 걸리는 직전 단계.

| 단계 (동시) | 처리량 | P95 (홀드→결제) | 에러율 | 오버셀 | HikariCP pending | 비고 |
|---|---|---|---|---|---|---|
| 100 | *(대기)* | | | | | |
| 200 | *(대기)* | | | | | |
| 400 | *(대기)* | | | | | |
| … | | | | | | |

**한계치(로컬)**: 동시 *(대기)* 명
**그때의 병목**: *(대기)*

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
