# TicketRush — 애플리케이션 아키텍처

이 문서는 애플리케이션 레벨의 컴포넌트 구성과 핵심 요청 흐름을 다룬다.
"왜 이렇게 결정했는가"는 `decisions.md`, 데이터 모델 상세는 `db-schema.md` / `redis-design.md`를 참고.
배포/인프라(AWS, Docker Compose, RDS 등)는 이 문서의 범위 밖이며 `decisions.md` 10번에서 다룬다.

## 설계 목표

오픈 순간 폭주하는 트래픽 속에서도 오버셀 없이 정합성을 보장하는 것이 핵심 목표다. 이를 위해 좌석 동시성 제어는 Redis의 원자적 연산(`DECR`, Lua 스크립트)으로 대부분 처리하여 DB 부하를 낮추고, DB에는 좌석 홀드를 뚫고 결제 요청 단계까지 도달한 소수의 요청만 도달한다 (decisions.md 7번). 결제 확정 이후의 후속 작업(정산, 알림)은 Kafka로 분리하여 사용자 응답 속도를 지키고 부가 시스템 장애가 결제 자체에 전파되지 않게 막는다 (decisions.md 6, 7번).

## 1. 컴포넌트 구성

| 구성 요소 | 기술 | 역할 |
|---|---|---|
| API Server | Spring Boot | 인증/인가(JWT, `BUYER`/`ORGANIZER`/`ADMIN`), 대기열 순번·입장 토큰 관리, 좌석 조회/홀드, 결제 요청/웹훅 처리, Saga 상태 전이(Choreography, 별도 조율자 없음) |
| Rate Limiter | Nginx | 대기열 진입 API 앞단 1차 방어. 공정성 보장(순서 유지)은 담당하지 않음 — 그 역할은 Redis 대기열이 맡는다 (decisions.md 4번) |
| Cache | Redis | 대기열 순번(Sorted Set), 좌석 상태(`AVAILABLE`/`HELD`) 및 스탠딩 잔여 수량, 홀드 TTL, 요청 멱등성 키(`SETNX`). 싱글 스레드 특성을 이용해 대부분의 동시성 제어를 락 없이 처리 (decisions.md 1번) |
| Database | MySQL | `reservation`/`reservation_seat`, `outbox_events` 등 정합성의 원천(source of truth). 결제 확정은 반드시 여기 동기 기록됨 |
| CDC | Debezium | MySQL binlog 변경 감지 → Kafka 발행. Outbox 패턴으로 DB 트랜잭션과 이벤트 발행의 원자성 확보 (decisions.md 6번) |
| Message Broker | Kafka | DB 부하 경감이 아니라, 결제확정 "이후" 후속 작업(정산/알림)을 사용자 응답과 분리하는 역할 (decisions.md 7번) |
| PG 연동 | 포트원 경유 | 결제 승인 처리, 웹훅으로 결과 통지. 웹훅 멱등성은 API 서버가 DB 상태 조회로 보장 (decisions.md 5번) |

## 2. 핵심 요청 흐름

### 2-1. 대기열 진입 → 입장 토큰 발급

대기열은 공정성("먼저 온 사람이 먼저 산다")을 보장하기 위한 것으로, Nginx의 Rate Limiting과는 역할이 다르다 — Rate Limiting은 순서 무관하게 초과 요청을 거절할 뿐이다 (decisions.md 4번).

```
1. 클라이언트 대기열 진입 요청
   └── Nginx Rate Limit 통과분만 API 서버로 전달

2. Redis 순번 등록
   └── ZADD queue:{eventId} (member=userId, score=진입시각)

3. 클라이언트에 순번 응답
   └── ZRANK queue:{eventId} {userId}

4. Scheduler 주기 실행 (상위 N명 입장 토큰 발급)
   ├── ZRANGE queue:{eventId} 0 N-1 조회
   └── 입장 토큰 발급 (TTL = 좌석 홀드 TTL과 동일)

5. 클라이언트는 순번 폴링 → 입장 토큰 수신
```

토큰이 실제로 만료되면(방치) 순번을 유지한 채 재발급하지 않고 대기열에 새로 진입한다 — 순번 유지 재발급을 허용하면 "토큰만 쥐고 자리 비워두는" 방식으로 대기열 우회가 가능해지기 때문 (decisions.md 4번).

### 2-2. 좌석 조회 및 홀드

좌석 동시성 제어는 자원 유형에 따라 세 갈래로 나뉜다. 스탠딩(수량제)과 지정석(개별 자원)은 성격이 달라 하나로 통합하지 않았고, 지정석 중에서도 단일 좌석은 Redis 싱글 스레드의 원자성만으로 락 없이 처리해 락이 필요한 범위를 최소화했다 (decisions.md 1번).

```
1. 클라이언트 좌석 홀드 요청 (입장 토큰 포함)
   └── 입장 토큰 검증

2. 사재기 방지 검증 (decisions.md 1번 "사재기 방지 정책")
   ├── SETNX active_reservation:{eventId}:{accountId} → 실패하면 이미 진행 중인 예약이 있는 것 (거절)
   └── 요청 수량이 2매 초과, 또는 (기존 확정 매수 + 요청 수량) > 2면 거절

3. 좌석 유형별 분기
   ├── 스탠딩 (수량제)
   │   └── HINCRBY seat_status:{eventId} standing:{sectionId} -{quantity} (좌석 상태 Hash에 통합, redis-design.md 참고)
   ├── 지정석 단일 좌석
   │   └── Lua 스크립트로 AVAILABLE → HELD 원자 전이 (락 없음)
   └── 지정석 다중 좌석 (그룹 선택, 최대 2매)
       ├── 그룹 단위 분산락 획득 (Redisson RLock vs DB 락, 벤치마크로 확정 예정 — decisions.md 2번)
       └── 그룹 좌석 AVAILABLE → HELD (전부 성공 또는 전부 실패)

4. 입장 토큰 TTL을 홀드 만료 시각에 맞춰 갱신
   └── 홀드는 멀쩡한데 토큰만 먼저 만료돼 결제 시작이 막히는 상황 방지

5. "홀드 성공" 응답 (SEAT_HELD) → 클라이언트에 즉시 전달
```

### 2-3. 결제 요청 → 확정/실패 → 후속 이벤트

요청 멱등성은 Redis `SETNX`(1차)와 DB 유니크 제약(2차, db-schema.md 참고)으로 이중 방어한다 — Redis 하나에만 의존하면 장애 시 재시도 요청이 멱등성 체크를 그냥 통과해 PG를 중복 호출할 위험이 있기 때문이다 (decisions.md 5번). 결제 확정 DB 기록과 이벤트 발행은 Outbox 패턴으로 원자성을 확보한다 (decisions.md 6번).

```
[동기 구간 — 클라이언트 응답 전]
1. 클라이언트 결제 요청

2. 요청 멱등성 확보
   └── SETNX 멱등성 키 (TTL = 홀드 TTL과 동일)

3. reservation INSERT (PAYMENT_REQUESTED, requested_at 기록) + reservation_seat INSERT (지정석이면 좌석마다 1행, db-schema.md 5·6번)

4. PG 결제 승인 요청

[비동기 구간 — PG 웹훅 수신 이후]
5. 웹훅 멱등성 확인 (DB 상태 조회)

6. 결제 결과 분기
   ├── 성공
   │   ├── reservation + reservation_seat UPDATE (PAYMENT_CONFIRMED, 같은 트랜잭션)
   │   ├── outbox_events INSERT (같은 트랜잭션)
   │   ├── Debezium이 binlog 변경 감지 → Kafka 발행
   │   ├── 정산/알림 Consumer가 후속 처리 (보류 중 — decisions.md 7번, progress.md 참고)
   │   └── active_reservation:{eventId}:{accountId} DEL (다음 시도 가능하게, 단 누적 2매 제한은 별도로 검사)
   └── 실패/타임아웃
       ├── reservation + reservation_seat UPDATE (PAYMENT_FAILED, 같은 트랜잭션)
       ├── 좌석 반납 (SEAT_RELEASED, Saga 보상 트랜잭션)
       └── active_reservation:{eventId}:{accountId} DEL
```

## 3. 예약 상태 전이 (Saga)

```
SEAT_HELD → PAYMENT_REQUESTED → PAYMENT_CONFIRMED                (정상, 웹훅 성공)
SEAT_HELD → PAYMENT_REQUESTED → PAYMENT_FAILED → SEAT_RELEASED   (보상, 웹훅 실패/타임아웃)
SEAT_HELD → SEAT_RELEASED                                        (홀드 TTL 만료)
```

**`SEAT_HELD`는 DB `reservation.status`에 저장되지 않는 Redis 전용 상태다** (db-schema.md 설계 원칙 참고) — `reservation` 행은 `PAYMENT_REQUESTED`부터 생긴다. 그래서 `SEAT_HELD → SEAT_RELEASED`(홀드 TTL 만료) 전이는 DB 업데이트가 아니라 Redis 쪽 상태 롤백만 의미하고, DB에서 실제로 관측되는 상태 전이는 `PAYMENT_REQUESTED → PAYMENT_CONFIRMED` / `PAYMENT_REQUESTED → PAYMENT_FAILED → SEAT_RELEASED` 두 갈래뿐이다.

Choreography 방식(중앙 조율자 없음)을 채택 — 이미 Kafka Consumer 기반 이벤트 구조를 쓰고 있어 별도 조율 컴포넌트 없이 자연스럽게 확장 가능하기 때문 (decisions.md 5번).

## 4. 이 문서에서 다루지 않는 것

- **배포/인프라 구성**(EC2, Docker Compose, RDS, 검토 중인 EKS/ElastiCache/MSK 등) → `decisions.md` 10번
- **Redis/MySQL 상세 키·테이블 설계** → `redis-design.md`, `db-schema.md`
- **Redis 장애 시 rebuild(리컨실리에이션) 흐름** → `decisions.md` 1번에 상세 기술, 핵심 요청 흐름이 아닌 장애 대응 절차라 이 문서에서는 다이어그램으로 다루지 않음
- **API 요청/응답 스펙** → `api-design.md`