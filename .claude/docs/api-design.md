# TicketRush API 설계

## 공통 규칙

Base URL은 `/api/v1`로 통일한다. 인증이 필요한 요청은 `Authorization: Bearer {accessToken}` 헤더로 JWT를 전달한다. 좌석 조회/선택/홀드와 결제 요청 API는 추가로 `X-Entry-Token` 헤더로 입장 토큰을 전달해야 한다 — 대기열을 통과한 사용자만 이 구간에 접근할 수 있도록 하기 위해서다(decisions.md 4번). 단, PG 웹훅은 이 토큰 검증 대상이 아니다(PG가 서버-to-서버로 호출하는 별도 채널이라 브라우저가 들고 있는 토큰과 무관해야 함). 모든 응답은 JSON이다.

Refresh Token은 httpOnly Cookie(Secure 속성 포함)로 전달한다(decisions.md 3번, 사용자 확인 완료) — 응답 헤더의 `Set-Cookie`로 내려주며 JSON 바디에는 포함하지 않는다. 구현상 쿠키 이름은 `refreshToken`, `Path`는 `/api/v1/auth`로 제한해(인증 API 외의 요청에는 실려가지 않게) `SameSite=Lax`로 발급한다. `Secure`는 기본 켜짐이지만 https가 아닌 로컬 개발에서는 환경변수(`REFRESH_COOKIE_SECURE=false`)로 끌 수 있다 — 켜둔 채로는 http에서 쿠키가 오가지 않아 로컬 테스트가 불가능하기 때문이다. 프론트엔드가 다른 오리진에 놓이면 `SameSite=None`+`Secure`로 바꿔야 한다(프론트 스택 미정이라 보류). 서버는 Redis(`refresh_token:{accountId}`, redis-design.md 9번)에 발급값을 저장해 `/auth/refresh` 요청마다 대조 검증하고, 로그아웃/재로그인 시 무효화한다. 계정당 Refresh Token은 1개만 유지되므로(다중 기기 로그인 미지원, 사용자 확인 완료) 다른 기기에서 로그인하면 기존 기기는 이후 재발급이 실패한다.

---

## 1. 인증 (Auth)

회원가입 시 `role`을 `BUYER` 또는 `ORGANIZER` 중 직접 선택한다. `BUYER`는 가입 즉시 로그인 가능하지만, `ORGANIZER`는 `ADMIN` 승인 전까지 로그인이 막힌다(decisions.md 12번, db-schema.md `account.status` 참고) — 콘서트 등록 권한이라 아무나 가입 즉시 쓸 수 있게 두면 악용 위험이 있기 때문이다(사용자 확인 완료). 승인 전 로그인 시도는 `ACCOUNT_PENDING` 에러를 반환하고, 이 에러를 받았을 때 어떤 안내 문구/팝업을 보여줄지는 프론트엔드가 정한다 — 백엔드는 "승인 대기 중"이라는 상태만 신호로 전달한다.

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| POST | /api/v1/auth/signup | 회원가입 (`role`: `BUYER` \| `ORGANIZER`, `ORGANIZER`는 `PENDING`으로 생성) | 없음 |
| POST | /api/v1/auth/login | 로그인 (`PENDING` 계정은 `ACCOUNT_PENDING` 에러) | 없음 |
| POST | /api/v1/auth/refresh | Access Token 재발급 (httpOnly Cookie의 Refresh Token을 Redis 저장값과 대조) | 없음 (Refresh Token Cookie 필요) |
| POST | /api/v1/auth/logout | 로그아웃 (Redis `refresh_token:{accountId}` 삭제로 즉시 무효화) | 인증 필요 |

**회원가입 성공 응답** (201)
```json
{ "accountId": 3, "role": "ORGANIZER", "status": "PENDING" }
```
- `status`를 함께 돌려주는 이유: `ORGANIZER`는 가입 직후 `PENDING`이라 바로 로그인할 수 없다. 클라이언트가 "승인 대기 중" 안내를 띄울지 판단하려면 가입 응답만으로 상태를 알 수 있어야 한다(구현 단계에서 추가).
- `role`에 `ADMIN`을 보내면 `INVALID_INPUT`으로 거절한다 — `ADMIN`은 셀프 가입 대상이 아니라 운영자가 직접 생성한다(db-schema.md 1번). 구현상으로는 앱 기동 시 `ADMIN` 계정이 없으면 환경변수(`ADMIN_EMAIL`/`ADMIN_PASSWORD`) 기준으로 1개 자동 생성한다(사용자 확인 완료) — `ADMIN`이 없으면 `ORGANIZER` 승인이 불가능해 이벤트 등록까지 연쇄적으로 막히기 때문이다.

**로그인 성공 응답** (Refresh Token은 `Set-Cookie` 헤더로 별도 전달, 바디에는 미포함)
```json
{ "accessToken": "eyJhbGci..." }
```

**로그인 실패 응답 (ORGANIZER 승인 대기 중)**
```json
{ "code": "ACCOUNT_PENDING", "message": "관리자 승인 대기 중입니다." }
```

---

## 2. 이벤트 (Event)

이벤트 등록 시 구역(섹션) 배열을 함께 받아, `SEATED` 구역은 `rowCount`/`seatsPerRow`로 좌석을 자동 생성하고 `STANDING` 구역은 `totalQuantity`만 저장한다(decisions.md 12번, db-schema.md `section`/`seat` 참고). 등록과 동시에 `section.total_quantity` 값을 기준으로 Redis `seat_status:{eventId}` Hash도 초기화한다.

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| GET | /api/v1/events | 이벤트 목록 조회 (오픈 시각순) | 없음 |
| GET | /api/v1/events/{eventId} | 이벤트 상세 조회 (구역 목록 포함) | 없음 |
| POST | /api/v1/events | 이벤트 등록 (구역/좌석 격자 포함) | ORGANIZER |
| PUT | /api/v1/events/{eventId} | 이벤트 전체 교체 (**오픈 전에만**) | ORGANIZER (본인 이벤트) |
| DELETE | /api/v1/events/{eventId} | 이벤트 삭제 (**오픈 전에만**) | ORGANIZER (본인 이벤트) |

**규모 상한(사용자 확인 완료, decisions.md 12번)**: 이벤트 전체 좌석 수는 **70,000석**(국내 최대 공연장인 잠실올림픽주경기장 약 69,000석 기준), 구역은 **200개**까지다(잠정값 — 3주차 부하 테스트에서 조정). 초과하면 `INVALID_INPUT`과 함께 어떤 한도를 얼마나 넘었는지 응답한다(예: `"이벤트 전체 좌석 수는 70,000석을 넘을 수 없습니다. (요청: 300,000석)"`). 공연 규모별로 다른 상한을 두지는 않는다 — 막으려는 대상은 "이 공연은 몇 석인가"가 아니라 자릿수 입력 실수와 악의적 요청이다.

**수정/삭제는 예매 시작(`openAt`) 전에만 허용한다(사용자 확인 완료)**. 판매가 시작된 뒤 좌석을 바꾸면 이미 팔린 좌석의 예약 기록·Redis 좌석 상태와 어긋나기 때문이다. 오픈 후 시도하면 `EVENT_ALREADY_OPENED`. 수정은 부분 수정이 아니라 **전체 교체**로, 등록(`POST`)과 같은 형식의 본문을 받아 기존 구역/좌석을 모두 지우고 새로 만든다 — 오픈 전이라 예약이 존재하지 않아 안전하고, 등록 로직을 그대로 재사용할 수 있다. 다른 `ORGANIZER`의 이벤트는 수정/삭제할 수 없다(`FORBIDDEN`). 목록 조회 응답에는 구역을 포함하지 않는다(이벤트마다 구역을 조회하면 쿼리가 이벤트 수만큼 늘어남).

**이벤트 등록 요청 body**
```json
{
  "name": "2026 콘서트",
  "openAt": "2026-09-01T20:00:00",
  "sections": [
    { "name": "VIP", "type": "SEATED", "price": 150000, "rowCount": 5, "seatsPerRow": 20 },
    { "name": "스탠딩", "type": "STANDING", "price": 99000, "totalQuantity": 2000 }
  ]
}
```

**이벤트 상세 조회 응답 (주요 필드)**
```json
{
  "id": 1,
  "name": "2026 콘서트",
  "openAt": "2026-09-01T20:00:00",
  "sections": [
    { "id": 10, "name": "VIP", "type": "SEATED", "price": 150000, "rowCount": 5, "seatsPerRow": 20 },
    { "id": 11, "name": "스탠딩", "type": "STANDING", "price": 99000, "remainingQuantity": 1832 }
  ]
}
```

- `remainingQuantity`(스탠딩 구역에만 존재): Redis `seat_status:{eventId}` Hash의 `standing:{sectionId}` 필드 기반 실시간 값.

---

## 3. 대기열 (Queue)

대기열 진입 자체는 인증만 있으면 되고 입장 토큰은 필요 없다(입장 토큰을 발급받기 위한 절차이므로). 순번 조회는 클라이언트가 폴링하는 용도이며, Scheduler가 상위 N명에게 입장 토큰을 발급하면 이 응답에 토큰이 채워진다(decisions.md 4번).

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| POST | /api/v1/events/{eventId}/queue/entries | 대기열 진입 | 인증 필요 |
| GET | /api/v1/events/{eventId}/queue/entries/me | 내 순번 + 입장 토큰 조회 (폴링) | 인증 필요 |

**순번 조회 응답**
```json
{ "rank": 1523, "entryToken": null }
```
```json
{ "rank": 0, "entryToken": "fd6af477-4069-4075-8a1d-c68e849bc927" }
```
- `entryToken`이 `null`이면 아직 대기 중, 값이 있으면 좌석 API 접근 가능(`X-Entry-Token` 헤더로 전달).
- `entryToken` 값은 무작위 UUID다(구현 단계에서 확정) — 검증은 헤더 값과 Redis 저장값의 단순 대조라 JWT처럼 서명·클레임이 필요 없다.

---

## 4. 좌석 (Seat)

이 구간의 모든 API는 `X-Entry-Token` 헤더가 필수다. 좌석 홀드는 지정석 단일/그룹, 스탠딩을 하나의 엔드포인트로 처리하고 서버가 내부적으로 분기한다(architecture.md 2-2, decisions.md 1·2번) — `seatIds`가 1개면 Lua 스크립트 단일 전이, 2개면 그룹 분산락, `sectionId`+`quantity`만 오면 스탠딩 `HINCRBY`.

**사재기 방지 검증이 홀드 요청마다 먼저 실행된다** (decisions.md 1번 "사재기 방지 정책", 사용자 확인 완료):
1. `seatIds`/`quantity`가 2를 초과하면 거절 (`QUANTITY_LIMIT_EXCEEDED`) — 한 건의 예약에 담을 수 있는 최대 매수
2. 이미 이 계정이 이 이벤트에 대해 진행 중인 예약(홀드~결제)이 있으면 거절 (`ACTIVE_RESERVATION_EXISTS`) — 동시에 1건만 허용
3. 이 계정이 이 이벤트에서 이미 확정 구매한 매수 + 이번 요청 매수가 2를 초과하면 거절 (`QUANTITY_LIMIT_EXCEEDED`) — 이벤트당 누적 상한

"몇 매 사겠다"를 사전에 선언받는 화면/API는 두지 않는다 — 실제 매수는 홀드 요청에 담긴 좌석/수량 개수로 정해진다(위 상한 안에서 자유). 대기 화면에 "1인당 최대 2매"라는 안내 문구를 두는 정도로 충분하며, 이건 프론트엔드 영역이라 이 문서에서 다루지 않는다.

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| GET | /api/v1/events/{eventId}/seats?sectionId={sectionId} | 좌석 상태 조회 | 인증 + 입장 토큰 |
| POST | /api/v1/events/{eventId}/seats/holds | 좌석 홀드 (지정석 단일/그룹, 스탠딩) | 인증 + 입장 토큰 |
| DELETE | /api/v1/events/{eventId}/seats/holds | 내 홀드 해제 (다른 좌석 다시 고르기, 진행 중인 예약도 함께 해제) | 인증 + 입장 토큰 |

**좌석 상태 조회 응답 (SEATED 구역)**
```json
[
  { "seatId": 101, "rowNo": 1, "seatNo": 1, "status": "AVAILABLE" },
  { "seatId": 102, "rowNo": 1, "seatNo": 2, "status": "HELD" }
]
```

**좌석 홀드 요청 body (지정석)**
```json
{ "sectionId": 10, "seatIds": [101, 102] }
```

**좌석 홀드 요청 body (스탠딩)**
```json
{ "sectionId": 11, "quantity": 2 }
```

**홀드 성공 응답**
```json
{ "status": "SEAT_HELD", "holdExpiresAt": "2026-09-01T20:05:00" }
```

**홀드 실패 응답 (매진과 "일시 이용 불가"를 구분 — decisions.md 1번)**
```json
{ "code": "SEAT_ALREADY_HELD", "message": "이미 선택된 좌석입니다." }
```
```json
{ "code": "STANDING_SOLD_OUT", "message": "매진되었습니다." }
```
```json
{ "code": "SERVICE_TEMPORARILY_UNAVAILABLE", "message": "일시적으로 이용이 어렵습니다. 잠시 후 다시 시도해주세요." }
```
```json
{ "code": "ACTIVE_RESERVATION_EXISTS", "message": "이미 진행 중인 예매가 있습니다." }
```
```json
{ "code": "QUANTITY_LIMIT_EXCEEDED", "message": "1인당 최대 2매까지 구매 가능합니다." }
```
- `SERVICE_TEMPORARILY_UNAVAILABLE`은 Redis rebuild 진행 중(`rebuild:in_progress:{eventId}`)일 때만 반환된다 — 실제로는 재고가 남아있을 수 있으므로 `STANDING_SOLD_OUT`/좌석 매진 응답과 반드시 구분한다(redis-design.md 3·6번).

---

## 5. 결제 / 예약 (Payment / Reservation)

결제 요청 API는 `X-Entry-Token`이 필요하다(decisions.md 4번 — 홀드는 멀쩡한데 토큰만 먼저 만료돼 결제가 막히는 상황을 이 헤더 검증으로 방지). 요청 멱등성은 서버가 발급하는 `idempotencyKey`가 아니라 **클라이언트가 매 요청마다 새로 생성해 보내는 값**으로 처리한다 — 같은 값으로 재시도하면 서버가 Redis `SETNX`/DB UNIQUE 제약으로 중복을 걸러낸다(decisions.md 5번). PG 웹훅은 포트원이 서버-to-서버로 호출하며, JWT 대신 **포트원 웹훅 서명 검증**으로 요청 출처를 확인한다(포트원이 페이로드에 포함하는 서명 값을 서버가 검증 — PG 연동의 표준 관행이라 별도 결정 없이 반영).

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| POST | /api/v1/reservations | 결제 요청 (`PAYMENT_REQUESTED` 생성 + PG 호출) | 인증 + 입장 토큰 |
| POST | /api/v1/payments/webhook | PG(포트원) 웹훅 수신 | 없음 (서명 검증) |
| GET | /api/v1/reservations/me | 내 예약 목록 조회 | 인증 필요 |
| GET | /api/v1/reservations/{reservationId} | 예약 상세/상태 조회 (결제 결과 폴링용) | 인증 필요 |
| POST | /api/v1/reservations/{reservationId}/cancel | 예약 취소 (MVP: 전액 취소만, decisions.md 9번) | 인증 필요 |

**결제 요청 body**
```json
{
  "eventId": 1,
  "sectionId": 10,
  "seatIds": [101, 102],
  "idempotencyKey": "c1a2-..."
}
```
스탠딩은 `seatIds` 대신 `quantity`를 보낸다. `seatIds`/`quantity` 모두 최대 2까지만 허용된다(4번 사재기 방지 검증 참고).

**결제 요청 응답 (즉시 반환 — 동기 구간, architecture.md 2-3 참고)**
```json
{ "reservationId": 501, "status": "PAYMENT_REQUESTED" }
```

**예약 상세 조회 응답 (결제 결과는 클라이언트가 이 엔드포인트를 폴링해서 확인)**
```json
{
  "reservationId": 501,
  "status": "PAYMENT_CONFIRMED",
  "amount": 300000,
  "confirmedAt": "2026-09-01T20:03:12"
}
```
- `status`는 db-schema.md `reservation.status`와 동일한 값(`PAYMENT_REQUESTED`/`PAYMENT_CONFIRMED`/`PAYMENT_FAILED`/`SEAT_RELEASED`)이다. `reservationId`는 결제 요청(`POST /api/v1/reservations`) 응답에서 처음 발급된다 — 좌석만 찜한 `SEAT_HELD` 단계는 DB 행이 아직 없어(db-schema.md 설계 원칙 참고) 조회할 `reservationId` 자체가 존재하지 않는다.

---

## 6. 관리자 (Admin)

`ADMIN`의 기능은 두 가지다: `ORGANIZER` 가입 승인(decisions.md 12번 — 이전까지는 역할만 정의되고 실제 쓰임이 없었다)과 이벤트 판매 현황 모니터링. 정산/알림 등 다른 관리 기능은 지금 보류하고, Kafka 소비자 구조를 확장 가능한 상태로만 열어둔다(decisions.md 7번).

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| GET | /api/v1/admin/accounts/pending | 승인 대기 중인 ORGANIZER 목록 조회 | ADMIN |
| PATCH | /api/v1/admin/accounts/{accountId}/approve | ORGANIZER 승인 (`PENDING` → `ACTIVE`) | ADMIN |
| GET | /api/v1/admin/events/{eventId}/stats | 실시간 판매 현황 + 좌석 점유율 조회 | ADMIN |

**승인 대기 목록 / 승인 응답** (구현 단계에서 확정 — 두 API가 같은 형식을 쓴다. 목록은 배열, 승인은 단건)
```json
{ "accountId": 4, "email": "organizer@example.com", "role": "ORGANIZER", "status": "ACTIVE", "createdAt": "2026-08-17T13:23:21" }
```
- 목록은 **먼저 가입한 순서**로 정렬한다(오래 기다린 사람이 위로). 관리자 전용 화면이라 트래픽이 적어 페이징은 두지 않는다.
- 승인 응답으로 갱신된 계정을 그대로 돌려주므로, 관리자 화면은 재조회 없이 해당 행만 갱신하면 된다.
- `BUYER`/`ADMIN` 계정에 승인을 시도하면 `INVALID_INPUT`으로 거절한다 — 승인 절차가 있는 역할은 `ORGANIZER`뿐이다.

**판매 현황 조회 응답**
```json
{
  "sections": [
    { "sectionId": 10, "name": "VIP", "type": "SEATED", "totalSeats": 100, "occupiedSeats": 42, "occupancyRate": 0.42 },
    { "sectionId": 11, "name": "스탠딩", "type": "STANDING", "totalQuantity": 2000, "remainingQuantity": 1832, "occupancyRate": 0.084 }
  ],
  "confirmedTicketCount": 38,
  "inProgressTicketCount": 4
}
```

- **데이터 원천**: 이 API는 새로운 데이터를 만들지 않고 이미 있는 값을 그대로 읽기만 한다. `occupiedSeats`(지정석)는 Redis `seat_status:{eventId}` Hash에서 `HELD`로 표시된 `seat:*` 필드 개수, `totalSeats`는 DB `seat` 테이블의 구역별 행 수. `remainingQuantity`(스탠딩)는 Redis `standing:{sectionId}` 필드 값. `confirmedTicketCount`/`inProgressTicketCount`는 DB `reservation`에서 `event_id`+`status`별 `quantity` 합계(`idx_event_status` 인덱스 활용, 추가 인덱스 불필요).
- **"실시간"은 폴링으로 처리한다** — 화면이 자동으로 갱신되는 웹소켓/SSE 방식은 만들지 않는다. 관리자 화면이 몇 초 간격으로 이 API를 다시 호출하는 것으로 충분하다(트래픽이 적은 관리자 전용 화면이라 부담이 없고, 값 자체는 Redis/DB를 그때그때 읽으므로 매 호출이 항상 최신 값이다). 화면 자동 갱신이 나중에 필요해지면 이 엔드포인트를 그대로 두고 프론트에서 폴링 주기만 조절하면 된다.

---

## 에러 응답 형식

```json
{ "code": "SEAT_ALREADY_HELD", "message": "이미 선택된 좌석입니다." }
```

| 코드 | HTTP | 설명 |
|---|---|---|
| INVALID_TOKEN | 401 | 위변조·형식 오류·만료된 토큰. Access Token뿐 아니라 `/auth/refresh`의 Refresh Token 검증 실패(쿠키 없음/위조/만료/Redis 저장값과 불일치)에도 쓴다 — 어느 쪽이든 사용자가 할 일은 재로그인으로 같기 때문에 코드를 나누지 않았다(구현 단계에서 확정) |
| UNAUTHORIZED | 401 | 인증 실패 |
| ACCOUNT_PENDING | 403 | ORGANIZER 가입 후 관리자 승인 대기 중 로그인 시도 |
| ENTRY_TOKEN_REQUIRED | 401 | `X-Entry-Token` 헤더 누락 |
| ENTRY_TOKEN_EXPIRED | 401 | 입장 토큰 만료 — 대기열 재진입 필요(decisions.md 4번) |
| QUEUE_ENTRY_NOT_FOUND | 404 | `GET /queue/entries/me` 조회 시 대기열에도 입장 토큰에도 기록이 없음 — 진입한 적이 없거나 토큰이 만료된 상태(구현 단계에서 추가) |
| FORBIDDEN | 403 | 권한 없는 접근 (예: BUYER가 이벤트 등록 시도) |
| ACCOUNT_NOT_FOUND | 404 | 계정 없음 (구현 단계에서 추가) |
| ACCOUNT_ALREADY_APPROVED | 409 | 이미 `ACTIVE`인 계정에 승인 재시도 (구현 단계에서 추가) |
| EVENT_NOT_FOUND | 404 | 이벤트 없음 |
| EVENT_ALREADY_OPENED | 409 | 이미 예매가 시작된 이벤트의 수정/삭제 시도 (구현 단계에서 추가) |
| SEAT_NOT_FOUND | 404 | 좌석 없음 |
| RESERVATION_NOT_FOUND | 404 | 예약 없음 (또는 아직 `SEAT_HELD` 단계라 DB 행 없음) |
| EMAIL_ALREADY_EXISTS | 409 | 이미 가입된 이메일로 회원가입 시도 (구현 단계에서 추가) |
| SEAT_ALREADY_HELD | 409 | 이미 다른 사용자가 선택 중인 좌석 |
| STANDING_SOLD_OUT | 409 | 스탠딩 매진 |
| SERVICE_TEMPORARILY_UNAVAILABLE | 503 | Redis rebuild 진행 중 — 매진과 구분되는 별도 상태 |
| ACTIVE_RESERVATION_EXISTS | 409 | 계정당 이벤트별 동시 진행 예약은 1건까지만 가능 |
| QUANTITY_LIMIT_EXCEEDED | 409 | 요청 매수가 2매 초과, 또는 기존 확정 매수와 합쳐 2매 초과 |
| DUPLICATE_PAYMENT_REQUEST | 409 | 동일 `idempotencyKey`로 중복 결제 요청 |
| PAYMENT_FAILED | 402 | PG 결제 승인 실패 |
| INVALID_INPUT | 400 | 요청 입력값 유효성 검증 실패 |
| INTERNAL_SERVER_ERROR | 500 | 서버 내부 오류 |

---

## 남은 항목 (progress.md에서 계속 추적)

- **정산/알림 기능은 보류** — decisions.md 7번의 Kafka 후속 처리 대상이지만 지금은 구현하지 않는다. 나중에 Consumer를 추가하는 식으로 확장 가능하도록만 열어둔다(테이블/API는 필요해지는 시점에 설계).
- 스탠딩 홀드/결제 요청 시 `quantity`가 여러 장이면 좌석처럼 개별 좌석 번호가 없어 입장권 자체를 어떻게 구분(예: QR 코드 개별 발급 여부)할지는 이 프로젝트 범위 밖으로 보고 다루지 않았다.
