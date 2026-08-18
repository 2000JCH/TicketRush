# TicketRush — DB 스키마

## 설계 원칙

`SEAT_HELD`는 DB에 저장하지 않는다. decisions.md 1번("`HELD` 상태는 영속화하지 않는다")과 5번("`PAYMENT_REQUESTED` 상태는... `reservation` 행을... DB에 동기 INSERT한다")을 종합하면, 결제 요청 전 홀드 단계는 Redis(`seat_status:{eventId}` Hash, redis-design.md 참고)에만 존재하고 `reservation` 행 자체가 아직 생기지 않은 상태다. 따라서 `reservation.status`는 `PAYMENT_REQUESTED`부터 시작하며 `SEAT_HELD`는 ENUM에 넣지 않는다.

soft delete는 적용하지 않는다. `reservation`은 상태 컬럼(`SEAT_RELEASED`, `PAYMENT_FAILED`)이 이미 이력을 표현하고, `event`/`section`/`seat`는 이 프로젝트 범위에서 삭제 요구사항이 정의된 바 없다.

---

## 테이블 목록

1. account
2. event
3. section
4. seat
5. reservation
6. reservation_seat
7. outbox_events

---

## 1. account

인증 계정 테이블. JWT 인증(decisions.md 3번)의 유일한 계정 테이블이며, `role`로 `BUYER`/`ORGANIZER`/`ADMIN`을 구분한다. `ORGANIZER`는 가입 즉시 활동할 수 없고 `ADMIN` 승인이 필요하다 — 콘서트 등록 권한이라 아무나 바로 쓸 수 있게 두면 악용 위험이 있기 때문(사용자 확인 완료). `status`가 `PENDING`이면 로그인 자체를 막는다(api-design.md `ACCOUNT_PENDING` 에러 참고). `BUYER`는 가입 즉시 `ACTIVE`로 시작해 승인 절차가 없다. Refresh Token은 이 테이블에 저장하지 않는다 — Redis(`refresh_token:{accountId}`, redis-design.md 9번, decisions.md 3번)에만 저장하기로 확정했다(사용자 확인 완료).

```sql
CREATE TABLE account (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  email      VARCHAR(100) NOT NULL UNIQUE,
  password   VARCHAR(255) NOT NULL,
  role       ENUM('BUYER', 'ORGANIZER', 'ADMIN') NOT NULL,
  status     ENUM('PENDING', 'ACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
```

- `status`: 애플리케이션 레벨에서 `role = 'ORGANIZER'`로 가입할 때만 `PENDING`으로 INSERT하고, 그 외(`BUYER`)는 기본값 `ACTIVE`를 그대로 쓴다. `ADMIN` 계정은 셀프 가입 대상이 아니므로(운영자가 직접 생성) 이 흐름과 무관하다.

---

## 2. event

콘서트(이벤트) 테이블. decisions.md 12번의 멀티 이벤트 모델을 담는다. `organizer_id`는 `ORGANIZER` 역할 계정만 참조하도록 애플리케이션 레벨에서 검증한다(DB 제약으로는 role을 강제하지 않음). `open_at`은 선착순 오픈 시각으로 대기열(4번) 스케줄러가 이 시각을 기준으로 동작한다. 공연장/공연 일시 등 세부 항목은 decisions.md에서 다루지 않은 범위라 최소 컬럼만 우선 반영했다 — 필요해지면 추가.

```sql
CREATE TABLE event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  organizer_id BIGINT       NOT NULL,
  name         VARCHAR(200) NOT NULL,
  open_at      DATETIME     NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (organizer_id) REFERENCES account(id)
);
```

---

## 3. section

구역(등급) 테이블. decisions.md 12번의 "구역(등급) → 행 → 좌석번호" 격자 구조에서 최상위 단위다. `type`이 `SEATED`면 `row_count`/`seats_per_row`로 이벤트 등록 시 `seat` 행을 자동 생성하고 `total_quantity`는 쓰지 않는다(NULL). `type`이 `STANDING`이면 반대로 `row_count`/`seats_per_row`는 NULL이고 `total_quantity`만 쓴다 — 실시간 잔여 수량의 원천은 Redis(`seat_status:{eventId}` Hash)이고, 이 컬럼은 rebuild 시 재계산 기준값 역할을 한다. 두 컬럼 세트가 동시에 채워지는 걸 막는 제약은 MySQL 버전에 따라 CHECK 지원 여부가 갈려 애플리케이션 레벨 검증으로 처리한다.

```sql
CREATE TABLE section (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  event_id       BIGINT       NOT NULL,
  name           VARCHAR(50)  NOT NULL,
  type           ENUM('SEATED', 'STANDING') NOT NULL,
  price          INT          NOT NULL,
  row_count      INT,
  seats_per_row  INT,
  total_quantity INT,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  FOREIGN KEY (event_id) REFERENCES event(id)
);
```

---

## 4. seat

개별 좌석 테이블. `SEATED` 구역에만 존재한다(`STANDING` 구역은 이 테이블에 행을 만들지 않음). 좌석의 판매 상태(`AVAILABLE`/`HELD`)는 여기 두지 않는다 — 상태의 원천은 Redis(decisions.md 1번)이고, 이 테이블은 "이 좌석이 존재한다"는 정적 사실만 담는다. 실제 판매 확정 여부는 `reservation_seat.seat_id`로 역참조해서 판단한다(5·6번 참고).

```sql
CREATE TABLE seat (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  section_id BIGINT   NOT NULL,
  row_no     INT      NOT NULL,
  seat_no    INT      NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_seat (section_id, row_no, seat_no),
  FOREIGN KEY (section_id) REFERENCES section(id)
);
```

---

## 5. reservation

예약/결제 부모 테이블. **예약 1건 = 좌석 1개였던 이전 설계에서, "예약 1건 = 결제 시도 1건(좌석 1~2개 포함 가능)"으로 바뀌었다** — 그룹 홀드(최대 2매, 사용자 확인 완료)를 지원하려면 한 번의 결제 시도에 좌석이 여러 개 들어갈 수 있어야 하는데, 기존처럼 `seat_id` 컬럼 하나로는 이걸 표현할 수 없기 때문이다. 그래서 지정석의 개별 좌석 정보는 자식 테이블 `reservation_seat`(6번)로 분리했다. `quantity`는 지정석이면 포함된 좌석 개수(1~2), 스탠딩이면 구매 수량(1~2)이다 — 어느 쪽이든 한 계정이 한 이벤트에서 살 수 있는 총 매수 상한(2매, 아래 참고)과 같은 단위로 취급된다.

**계정당 이벤트별 누적 2매 제한**: `idx_account_event_status` 인덱스로 "이 계정이 이 이벤트에서 이미 `PAYMENT_CONFIRMED`한 `quantity` 합"을 조회해서, 새 예약 시도의 `quantity`를 더했을 때 2를 넘으면 거절한다(애플리케이션 레벨 검사). 이 검사는 별도 락 없이도 안전한데, "계정당 동시 진행 예약 1건 제한"(redis-design.md `active_reservation:{eventId}:{accountId}` 참고)이 이미 같은 계정의 예약 시도를 순차적으로만 진행되게 강제하기 때문이다 — 검사 시점에 같은 계정의 또 다른 시도가 동시에 끼어들 수 없다.

```sql
CREATE TABLE reservation (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  account_id      BIGINT       NOT NULL,
  event_id        BIGINT       NOT NULL,
  section_id      BIGINT       NOT NULL,
  quantity        INT          NOT NULL,
  amount          INT          NOT NULL,
  status          ENUM('PAYMENT_REQUESTED', 'PAYMENT_CONFIRMED', 'PAYMENT_FAILED', 'SEAT_RELEASED')
                  NOT NULL DEFAULT 'PAYMENT_REQUESTED',
  idempotency_key VARCHAR(64)  NOT NULL,
  pg_payment_id   VARCHAR(64),
  requested_at    DATETIME     NOT NULL,
  confirmed_at    DATETIME,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_idempotency_key (idempotency_key),
  KEY idx_requested_at (requested_at),
  KEY idx_event_status (event_id, status),
  KEY idx_account_event_status (account_id, event_id, status),
  CHECK (quantity BETWEEN 1 AND 2),
  FOREIGN KEY (account_id) REFERENCES account(id),
  FOREIGN KEY (event_id)   REFERENCES event(id),
  FOREIGN KEY (section_id) REFERENCES section(id)
);
```

- `idempotency_key`: 결제 요청 API가 Redis `SETNX`에 쓴 값과 동일한 값을 그대로 저장한다. 그룹(좌석 2개)이어도 하나의 결제 시도이므로 키는 1개다.
- `quantity`: 좌석 개수(지정석) 또는 구매 수량(스탠딩), 1~2. `CHECK` 제약으로 DB 레벨에서도 상한을 강제한다(MySQL 8.0.16+에서 지원 — 이전 버전이면 애플리케이션 레벨 검증으로 대체).
- `requested_at`: `PAYMENT_REQUESTED` INSERT 시 함께 기록되며, rebuild(decisions.md 1번)가 "TTL 안 지난 결제 진행 중" 좌석/스탠딩을 판단하는 기준이 되므로 인덱스를 걸었다.
- `idx_event_status`: rebuild가 이벤트 단위로 "점유 중"(`PAYMENT_CONFIRMED` + TTL 안 지난 `PAYMENT_REQUESTED`) 행을 조회할 때 사용.
- `pg_payment_id`: 포트원 결제 트랜잭션 식별자. 웹훅 멱등성 자체는 `status` 조회로 판단하지만(decisions.md 5번), PG사 문의·정산 대사 시 참조용으로 저장한다.

---

## 6. reservation_seat

지정석 예약에 포함된 개별 좌석을 담는 자식 테이블. `reservation`(5번) 1건에 좌석이 1~2개 연결될 수 있다. 스탠딩 예약은 이 테이블에 행을 만들지 않는다 — 풀(pool) 수량이라 좌석 단위로 "중복 점유 방지" 불변조건 자체가 없고, 오버셀 방지는 Redis `DECR`/`INCR`가 전담하기 때문이다(1번 참고).

**`status`를 부모 `reservation.status`와 별도로 이 테이블에도 둔 이유**: MySQL의 `STORED GENERATED` 컬럼은 같은 행 안의 다른 컬럼만 참조할 수 있어, 부모 테이블의 `status`를 직접 참조하는 생성 컬럼을 자식 테이블에 만들 수 없다. 그래서 `status`를 이 테이블에도 그대로 복제해두고, 상태가 바뀔 때마다 애플리케이션이 `reservation.status`와 `reservation_seat.status`를 **같은 트랜잭션 안에서** 함께 UPDATE한다. 이렇게 하면 예약 이전(decisions.md 5번)에 이미 있던 `active_seat_id` 패턴(같은 좌석에 대해 진행 중인 시도가 동시에 두 개 이상 있는 것을 DB 레벨에서 막는 장치)을 그대로 재사용할 수 있다.

```sql
CREATE TABLE reservation_seat (
  id             BIGINT NOT NULL AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  seat_id        BIGINT NOT NULL,
  status         ENUM('PAYMENT_REQUESTED', 'PAYMENT_CONFIRMED', 'PAYMENT_FAILED', 'SEAT_RELEASED') NOT NULL,
  active_seat_id BIGINT AS (
                   CASE WHEN status IN ('PAYMENT_REQUESTED', 'PAYMENT_CONFIRMED') THEN seat_id END
                 ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_active_seat (active_seat_id),
  FOREIGN KEY (reservation_id) REFERENCES reservation(id),
  FOREIGN KEY (seat_id)        REFERENCES seat(id)
);
```

- `uq_active_seat`: 같은 좌석에 대해 `PAYMENT_REQUESTED`/`PAYMENT_CONFIRMED` 행이 동시에 두 개 이상 존재할 수 없다 — 이전 설계의 `active_seat_id`와 동일한 역할(2차 방어선)을 그대로 담당한다. MySQL UNIQUE는 NULL을 여러 번 허용하므로, 취소/환불된(`PAYMENT_FAILED`/`SEAT_RELEASED`) 좌석은 제약에 안 걸리고 재판매가 가능하다.

---

## 7. outbox_events

Outbox 패턴 테이블(decisions.md 6번). `reservation`을 `PAYMENT_CONFIRMED`로 UPDATE하는 트랜잭션과 같은 트랜잭션에서 INSERT되고, Debezium이 binlog 변경을 감지해 Kafka로 발행한다. 정리 정책(TTL/배치삭제)은 아직 미정(decisions.md 11번, progress.md에 추적 중)이라 지금은 무제한 적재로 둔다.

```sql
CREATE TABLE outbox_events (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  aggregate_type VARCHAR(50)  NOT NULL,
  aggregate_id   BIGINT       NOT NULL,
  event_type     VARCHAR(50)  NOT NULL,
  payload        JSON         NOT NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
```

---

## 테이블 관계 요약

```
account   ──organizer_id──>  event  ──event_id──>  section  ──section_id──>  seat
account   ──account_id───>   reservation
event     ──event_id─────>   reservation
section   ──section_id───>   reservation
reservation ──reservation_id──> reservation_seat ──seat_id──> seat

outbox_events → (reservation, 트랜잭션으로만 연결. FK 없음 — 결제확정 UPDATE와 outbox INSERT가
                 같은 DB 트랜잭션 안에 있다는 것으로 원자성을 보장하며, 조회 목적의 FK는 아님)
```

`reservation`은 `account`(구매자), `event`, `section` 세 곳을 참조한다. 지정석이면 `reservation_seat`(최대 2행)이 `seat`를 참조해 개별 좌석을 연결하고, 스탠딩이면 `reservation_seat` 행 자체가 없다(`quantity`만으로 수량 표현).

---

## Redis 연계 요약

상세 키 설계는 `redis-design.md` 참고. 이 테이블은 DB와 맞물리는 지점만 요약한다.

| Redis Key | DB 연계 |
|---|---|
| `seat_status:{eventId}` (Hash) | rebuild 시 `reservation`+`reservation_seat`에서 `PAYMENT_CONFIRMED` + TTL 안 지난 `PAYMENT_REQUESTED`를 조회해 재구성 |
| `idempotency:{key}` | `reservation.idempotency_key`와 동일한 값. Redis 유실 시 DB UNIQUE 제약이 2차 방어 |
| `hold:{eventId}:{seatId}` 등 | TTL 만료 시점에 따라 처리가 갈린다(redis-design.md 4번): `PAYMENT_REQUESTED` 이전 만료는 DB 행이 없어 Redis 쪽 상태 롤백만 발생. `PAYMENT_REQUESTED` 이후(결제 처리 타임아웃) 만료는 `reservation`/`reservation_seat`를 `PAYMENT_FAILED → SEAT_RELEASED`로 UPDATE까지 이어진다 — PG 웹훅이 명시적으로 실패를 알려주는 경우와 별개로, 웹훅 자체가 안 오는 타임아웃을 감지하는 유일한 경로다 |
| `active_reservation:{eventId}:{accountId}` | DB와 직접 연계 없음 — "계정당 동시 진행 예약 1건" 제한 전용(redis-design.md 8번). 이 키 덕분에 `idx_account_event_status` 누적 조회가 race 걱정 없이 안전해짐(5번 참고) |

## 남은 항목 (progress.md에서 계속 추적)

- Outbox 테이블 정리 정책은 decisions.md 11번 그대로 미정. Refresh Token은 DB 테이블 없이 Redis에만 저장하기로 확정(decisions.md 3번)
