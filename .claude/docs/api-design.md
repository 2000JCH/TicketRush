# TicketRush API 설계

## 공통 규칙

Base URL은 `/api/v1`로 통일한다. 인증이 필요한 요청은 `Authorization: Bearer {accessToken}` 헤더로 JWT를 전달한다. 좌석 조회/선택/홀드와 결제 요청 API는 추가로 `X-Entry-Token` 헤더로 입장 토큰을 전달해야 한다 — 대기열을 통과한 사용자만 이 구간에 접근할 수 있도록 하기 위해서다(decisions.md 4번). 단, PG 웹훅은 이 토큰 검증 대상이 아니다(PG가 서버-to-서버로 호출하는 별도 채널이라 브라우저가 들고 있는 토큰과 무관해야 함). 모든 응답은 JSON이다.

Refresh Token은 httpOnly Cookie(Secure 속성 포함)로 전달한다(decisions.md 3번, 사용자 확인 완료) — 응답 헤더의 `Set-Cookie`로 내려주며 JSON 바디에는 포함하지 않는다. 구현상 쿠키 이름은 `refreshToken`, `Path`는 `/api/v1/auth`로 제한해(인증 API 외의 요청에는 실려가지 않게) `SameSite=Lax`로 발급한다. `Secure`는 기본 켜짐이지만 https가 아닌 로컬 개발에서는 환경변수(`REFRESH_COOKIE_SECURE=false`)로 끌 수 있다 — 켜둔 채로는 http에서 쿠키가 오가지 않아 로컬 테스트가 불가능하기 때문이다. 프론트엔드(React/Vite, `localhost:5173`)는 로컬에서 백엔드와 포트만 다른 오리진이지만 도메인은 같은 `localhost`라 SameSite 기준으로는 같은 사이트에 해당하며, 실제로 `SameSite=Lax`인 채로 CORS(`credentials: include`)를 붙여 로그인/재발급 흐름이 정상 동작하는 것까지 확인했다(2026-08-23). 다만 3주차 AWS 배포 시 프론트엔드와 백엔드가 서로 다른 도메인에 놓이면(진짜 "다른 사이트") 그때는 `SameSite=None`+`Secure`로 바꿔야 한다. 서버는 Redis(`refresh_token:{accountId}`, redis-design.md 9번)에 발급값을 저장해 `/auth/refresh` 요청마다 대조 검증하고, 로그아웃/재로그인 시 무효화한다. 계정당 Refresh Token은 1개만 유지되므로(다중 기기 로그인 미지원, 사용자 확인 완료) 다른 기기에서 로그인하면 기존 기기는 이후 재발급이 실패한다.

---

## 1. 인증 (Auth)

회원가입 시 `role`을 `BUYER` 또는 `ORGANIZER` 중 직접 선택한다. `BUYER`는 가입 즉시 로그인 가능하지만, `ORGANIZER`는 `ADMIN` 승인 전까지 로그인이 막힌다(decisions.md 12번, db-schema.md `account.status` 참고) — 콘서트 등록 권한이라 아무나 가입 즉시 쓸 수 있게 두면 악용 위험이 있기 때문이다(사용자 확인 완료). 승인 전 로그인 시도는 `ACCOUNT_PENDING` 에러를 반환하고, 이 에러를 받았을 때 어떤 안내 문구/팝업을 보여줄지는 프론트엔드가 정한다 — 백엔드는 "승인 대기 중"이라는 상태만 신호로 전달한다.

| 메서드 | 엔드포인트 | 설명 | 권한 |
|---|---|---|---|
| POST | /api/v1/auth/signup | 회원가입 (`role`: `BUYER` \| `ORGANIZER`, `ORGANIZER`는 `PENDING`으로 생성) | 없음 |
| POST | /api/v1/auth/login | 로그인 (`PENDING` 계정은 `ACCOUNT_PENDING` 에러) | 없음 |
| POST | /api/v1/auth/refresh | Access Token 재발급 (httpOnly Cookie의 Refresh Token을 Redis 저장값과 대조) | 없음 (Refresh Token Cookie 필요) |
| POST | /api/v1/auth/logout | 로그아웃 (Redis `refresh_token:{accountId}` 삭제로 즉시 무효화) | 인증 필요 |
| GET | /api/v1/accounts/me | 로그인한 본인 계정 정보 조회 ("내 정보" 화면용) | 인증 필요 |

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

**내 계정 정보 응답 (`GET /api/v1/accounts/me`)** — 관리자 화면에서 쓰는 `AccountResponse`와 같은 형태다. 비밀번호는 절대 담지 않는다. 경로가 `/auth/*`가 아니라 `/accounts/me`인 이유는 "인증"이 아니라 계정 조회이기 때문이며, 패키지는 CLAUDE.md 규칙대로 `domain/account`에 둔다.
```json
{ "accountId": 3, "email": "buyer@example.com", "role": "BUYER", "status": "ACTIVE", "createdAt": "2026-09-06T22:54:56" }
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

**2주차 "좌석 상태 모델(단일 좌석 흐름)" 단계의 구현 범위(사용자 확인 완료)**: `seatIds`가 2개인 그룹 홀드 요청은 아직 지원하지 않는다 — 분산락 벤치마크(decisions.md 2번) 이후 단계에서 구현하며, 지금은 `INVALID_INPUT`으로 거절한다. `GET .../seats`는 `SEATED` 구역만 조회 가능하고 `STANDING` 구역을 지정하면 `INVALID_INPUT`으로 거절한다(잔여 수량은 이벤트 상세 조회로 확인).

**2주차 "홀드 TTL/만료 처리" 단계에서 `holdExpiresAt`이 실제 값으로 채워짐**: 홀드 성공 시 홀드 TTL(`seat.hold-ttl-millis`)만큼 뒤의 시각이 채워진다(redis-design.md 4-1번). 방치된 홀드는 이 시각이 지나면 자동으로 `AVAILABLE`로 돌아간다 — Redis Keyspace Notification이 아니라 "만료 시각순 정렬 집합(`hold_schedule`) + 주기적 스케줄러" 방식으로 처리한다(구현 단계 재설계, 사용자 확인 완료. 이유는 redis-design.md 4-1번 참고).

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
| GET | /api/v1/payments/config | 프론트 PortOne SDK 호출용 `storeId`·채널키(카드/카카오페이) 조회 | 인증 필요 |
| POST | /api/v1/reservations | 결제 요청 (`PAYMENT_REQUESTED` 생성) | 인증 + 입장 토큰 |
| POST | /api/v1/payments/webhook | PG(포트원) 웹훅 수신 | 없음 (서명 검증) |
| GET | /api/v1/reservations/me | 내 예약 목록 조회 | 인증 필요 |
| GET | /api/v1/reservations/{reservationId} | 예약 상세/상태 조회 (결제 결과 폴링용) | 인증 필요 |
| POST | /api/v1/reservations/{reservationId}/cancel | 예약 취소 (MVP: 전액 취소만, decisions.md 9번) | 인증 필요 |

**결제창 연동 흐름(2026-09-06, 프론트 PortOne V2 SDK 연동)**: 프론트가 `GET /payments/config`로 `storeId`·채널키를 받고 → `POST /reservations`로 `PAYMENT_REQUESTED` 예약을 만들며 `pgPaymentId`·`amount`·`orderName`을 받아 → `PortOne.requestPayment()`로 결제창(카드=토스페이먼츠 채널 / 카카오페이=간편결제 채널)을 띄운다. 결제 완료/실패의 최종 확정은 SDK 콜백이 아니라 **포트원 웹훅**(`POST /payments/webhook`)으로 서버 상태가 바뀌고, 프론트는 `GET /reservations/{id}` 폴링으로 그 결과를 반영한다. **웹훅은 공개 URL이 필요해 localhost로는 도달하지 못한다 — 로컬 데모는 결제창까지만 확인되고, "결제 완료" 확정과 웹훅 서명 실측 검증은 AWS 배포 후에 한다**(progress.md 추적). `storeId`/채널키는 원래 브라우저에 노출되는 공개 식별자이고, 시크릿(`PORTONE_API_SECRET`/`PORTONE_WEBHOOK_SECRET`)은 이 응답에 포함하지 않는다.

**PortOne SDK 설정 응답 (`GET /api/v1/payments/config`)**
```json
{ "storeId": "store-...", "cardChannelKey": "channel-key-...", "easyPayChannelKey": "channel-key-..." }
```

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
{ "reservationId": 501, "status": "PAYMENT_REQUESTED", "pgPaymentId": "TICKETRUSH-501", "amount": 300000, "orderName": "OO 콘서트" }
```
- `pgPaymentId`(3주차 결제 연동에서 추가): 프론트가 포트원 V2 결제창 SDK를 호출할 때 `paymentId`로 그대로 넘겨야 하는 값. 서버가 `"TICKETRUSH-{reservationId}"` 형식으로 생성한다(db-schema.md `reservation.pg_payment_id` 참고).
- `amount`·`orderName`(2026-09-06 결제창 연동에서 추가): 프론트가 `PortOne.requestPayment()`의 `totalAmount`/`orderName`으로 그대로 넘긴다. 금액을 프론트에서 다시 계산하면 서버 확정값과 어긋날 수 있어 서버가 확정한 값을 함께 내려준다. `orderName`은 이벤트명이다.

**예약 상세 조회 응답 (결제 결과는 클라이언트가 이 엔드포인트를 폴링해서 확인, `GET /reservations/{id}`와 `GET /reservations/me`가 공용)**
```json
{
  "reservationId": 501,
  "eventId": 1,
  "eventName": "OO 콘서트",
  "status": "PAYMENT_CONFIRMED",
  "quantity": 2,
  "amount": 300000,
  "seats": [
    { "sectionName": "R석", "rowNo": 3, "seatNo": 1 },
    { "sectionName": "R석", "rowNo": 3, "seatNo": 2 }
  ],
  "requestedAt": "2026-09-01T20:01:03",
  "confirmedAt": "2026-09-01T20:03:12"
}
```
- `status`는 db-schema.md `reservation.status`와 동일한 값(`PAYMENT_REQUESTED`/`PAYMENT_CONFIRMED`/`PAYMENT_FAILED`/`SEAT_RELEASED`)이다. `reservationId`는 결제 요청(`POST /api/v1/reservations`) 응답에서 처음 발급된다 — 좌석만 찜한 `SEAT_HELD` 단계는 DB 행이 아직 없어(db-schema.md 설계 원칙 참고) 조회할 `reservationId` 자체가 존재하지 않는다. `GET /reservations/me`는 이 형태의 배열을 반환한다(구현 단계에서 확정 — 원래 예시엔 `eventId`/`quantity`/`requestedAt`이 없었으나, 목록에서 "어느 이벤트의 몇 매짜리 예약인지" 구분하려면 필요해 추가함).
- `eventName`·`seats`(2026-09-06 "내 예약" 화면 개선에서 추가): "어느 콘서트의 몇 번 자리인지"를 화면에서 보여주기 위함. `seats`는 지정석 예약의 개별 좌석(구역명·행·번, 행-번 순 정렬)이고, **스탠딩 예약은 빈 배열**이라 `quantity`로만 표시한다. 목록 조회(`/me`)는 좌석을 예약별로 한 번에 가져오는 fetch join으로 N+1을 피한다.

**예약 취소 응답**: 위와 동일한 상세 조회 형식을 그대로 반환한다(`status: "SEAT_RELEASED"`). `PAYMENT_CONFIRMED` 상태에서만 취소할 수 있고, 그 외 상태에서 시도하면 `RESERVATION_NOT_CANCELLABLE`(409)이다.

**웹훅 요청 (`POST /api/v1/payments/webhook`)**: 포트원 V2가 [Standard Webhooks](https://www.standardwebhooks.com/) 스펙을 쓰는 것으로 확인해(1주차 스모크테스트 로그에 `webhook-signature` 헤더가 그대로 찍힘) 그 방식으로 서명 검증을 구현했다 — `webhook-id`/`webhook-timestamp`/`webhook-signature` 헤더 + HMAC-SHA256(secret은 `whsec_` 접두사 + base64), 타임스탬프 5분 이상 벗어나면 서명이 맞아도 거절(재전송 공격 방지). **시크릿은 2026-08-27에 사용자가 콘솔에서 찾아 `.env`에 반영했지만, 실결제 이벤트로 이 가정을 검증한 적은 아직 없다**(콘솔 "호출 테스트"는 서명 헤더 없이 옴) — 재확인 필요(progress.md 추적). 시크릿이 비어있으면(`.env`에 `PORTONE_WEBHOOK_SECRET` 없음) 모든 웹훅을 거절한다(빈 시크릿으로 통과시키는 게 인증을 끈 것보다 더 위험하기 때문).
- 처리하는 `type`: `Transaction.Paid` → `confirmPayment`(→ `PAYMENT_CONFIRMED`), `Transaction.Failed` → `markPaymentFailed`(→ `PAYMENT_FAILED`, 이후 Kafka Consumer가 `releaseAfterFailure`를 트리거해 좌석을 반납한다, 아래 "Kafka exactly-once 연동" 참고). 그 외 `type`(`Transaction.Ready`/`Transaction.Cancelled` 등)은 로그만 남기고 무시한다.
- 웹훅 body의 `data.paymentId`로 `reservation.pg_payment_id`를 역조회해 어느 예약인지 찾는다. **단순화(알려진 한계)**: 웹훅 body의 값을 그대로 신뢰한다 — 더 엄격하게 하려면 포트원 결제 조회 API(GetPayment)로 서버 대 서버 재검증을 해야 한다. 프론트 결제창 SDK는 2026-09-06에 연동됐지만 로컬은 웹훅이 도달하지 못해 실웹훅 자체가 아직 미검증이라(위 "결제창 연동 흐름" 참고), 재검증은 AWS에서 실웹훅이 확인된 뒤에 붙인다(`PORTONE_API_SECRET`은 `.env`에 준비됨).

**Kafka exactly-once 연동(3주차, decisions.md 6번)**: `markPaymentFailed`가 같은 트랜잭션에서 `outbox_events`(`event_type=PAYMENT_FAILED`)에도 INSERT하고, Debezium(Outbox Event Router SMT)이 이를 감지해 Kafka 토픽 `ticketrush.reservation.events`로 발행한다. Spring Kafka `@KafkaListener`(`PaymentFailedConsumer`)가 이를 소비해 `releaseAfterFailure`(좌석 반납)를 호출한다 — decisions.md 5번 Choreography(중앙 조율자 없이 Kafka Consumer로 다음 단계를 잇는 방식)를 그대로 구현한 것이며, `markPaymentFailed`/`releaseAfterFailure`를 애초에 두 메서드로 나눠뒀던 이유가 바로 이것이다. `PAYMENT_CONFIRMED` 쪽은 정산/알림 기능 자체가 보류 중이라(아래 "남은 항목" 참고) outbox 이벤트를 만들지 않는다.
- Kafka/Debezium은 at-least-once 전달만 보장하지만, `releaseAfterFailure`가 이미 상태 체크(`status == PAYMENT_FAILED`)로 멱등하므로 재전달돼도 안전하다 — "at-least-once + 멱등 소비자"로 exactly-once 효과를 얻는다. decisions.md 6번이 말하는 진짜 Kafka 트랜잭션 API(consume-transform-**produce**)는 이 컨슈머처럼 재발행 단계가 없는 흐름에는 쓰지 않는다.
- **로컬 개발 환경 주의**: Kafka Connect(Debezium)가 outbox_events의 스키마 변경 이벤트를 `topic.prefix`와 같은 이름의 토픽("ticketrush")에 발행하려다, 브로커의 `auto.create.topics.enable=false` 설정 때문에 그 토픽이 없어 무한 재시도에 빠지는 문제를 실제로 겪었다(2026-08-27) — 커넥터 설정에 `include.schema.changes=false`를 추가하고, `docker-compose.yml`의 `KAFKA_AUTO_CREATE_TOPICS_ENABLE`을 `true`로 바꿔 해결했다(progress.md에 상세 기록).

---

## 6. 관리자 (Admin)

`ADMIN` 콘솔은 2026-09-06 저녁에 "B그룹"으로 정리됐다(사용자 확인 완료). 계정 관리(승인/거절/정지)와 콘서트 현황 조회로 구성된다. 정산/알림은 여전히 보류(decisions.md 7번). 모든 경로는 `SecurityConfig`의 `/api/v1/admin/**` → `hasRole("ADMIN")` 규칙이 접근을 통제한다.

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | /api/v1/admin/accounts/pending | 승인 대기 중인 ORGANIZER 목록 (가입 순) |
| GET | /api/v1/admin/accounts | 전체 회원 목록 — `role`/`status`/`email`(부분일치) 필터 + 페이지네이션(`page`/`size`, 최근 가입 순) |
| GET | /api/v1/admin/accounts/{accountId}/reservations | 특정 회원의 예매 내역 (5번의 예약 상세 형식 배열) |
| PATCH | /api/v1/admin/accounts/{accountId}/approve | ORGANIZER 승인 (`PENDING` → `ACTIVE`) |
| DELETE | /api/v1/admin/accounts/{accountId} | ORGANIZER 승인 거절 — PENDING ORGANIZER 행 삭제(재가입 가능), 204 |
| PATCH | /api/v1/admin/accounts/{accountId}/suspend | 계정 정지(소프트 삭제) — `ACTIVE` → `SUSPENDED` |
| PATCH | /api/v1/admin/accounts/{accountId}/reactivate | 정지 해제 — `SUSPENDED` → `ACTIVE` |
| GET | /api/v1/admin/events/stats | 콘서트별 판매 현황(전 콘서트 한 번에) |

**계정 응답** — `pending`/`approve`/`suspend`/`reactivate`가 모두 아래 `AccountResponse` 형식을 쓴다(목록은 배열, 단건은 객체). 비밀번호는 절대 담지 않는다.
```json
{ "accountId": 4, "email": "organizer@example.com", "role": "ORGANIZER", "status": "ACTIVE", "createdAt": "2026-08-17T13:23:21" }
```

**회원 목록 응답 (`GET /api/v1/admin/accounts`)** — 계정 수가 많아(수천~) 페이지네이션 필수. `content`만 도메인 객체이고 나머지는 페이지 메타.
```json
{ "content": [ /* AccountResponse[] */ ], "page": 0, "size": 20, "totalElements": 6364, "totalPages": 319 }
```

**계정 상태 전이 규칙(구현 단계 확정, 사용자 확인 완료 — 소프트 삭제 채택)**:
- 정지는 상태를 `SUSPENDED`로 바꾸고 Redis `refresh_token:{accountId}`를 삭제한다 → 재로그인·토큰 재발급이 즉시 막힌다(`ACCOUNT_SUSPENDED`, 403). **이미 발급된 Access Token은 만료 전까지 유효**하다 — 즉시 차단하려면 JWT 필터가 매 요청 DB를 조회해야 해서 성능 트레이드오프가 있어 하지 않았다(알려진 한계).
- `ADMIN` 계정은 정지할 수 없다(`INVALID_ACCOUNT_STATE`, 409). 이미 `SUSPENDED`인 계정 재정지, `ACTIVE`인 계정 정지 해제도 같은 코드로 거절한다.
- 거절(`DELETE`)은 아직 한 번도 활성화된 적 없는 PENDING ORGANIZER만 대상이라 행을 삭제한다(예약/이벤트가 없어 FK 문제 없음). 그 외 상태는 `INVALID_ACCOUNT_STATE`.
- 하드 삭제(임의 계정 행 DELETE)는 예약·매출 통계가 왜곡돼 채택하지 않았다(decisions.md).

**콘서트별 판매 현황 응답 (`GET /api/v1/admin/events/stats`)** — 전 콘서트를 한 번에 배열로 반환한다(관리자 "콘서트 현황" 화면이 목록형이라, 원래 설계의 이벤트당 조회 대신 목록으로 바꿈).
```json
[
  { "eventId": 12, "eventName": "OO 콘서트", "openAt": "2026-09-05T22:24:47",
    "capacity": 200, "sold": 145, "remaining": 55,
    "confirmedReservations": 130, "confirmedAmount": 14500000 }
]
```
- `capacity` = 지정석 좌석 수(DB `seat`) + 스탠딩 총 수용 인원(`section.total_quantity` 합). `sold` = **`PAYMENT_CONFIRMED` 예약의 `quantity` 합**(취소·실패·진행 중은 제외). `remaining`은 0에서 클램프.
- 이벤트별로 쿼리를 반복하지 않고 5개 집계(GROUP BY)를 한 번에 읽어 메모리에서 합친다. 실시간 홀드 현황(Redis)은 이 화면에 넣지 않는다 — "지금 몇 자리 팔렸나"는 확정 기준으로 보는 게 매출과 일관되기 때문(원래 설계의 Redis 기반 per-section occupancy는 보류).

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
| ACCOUNT_SUSPENDED | 403 | 정지된 계정의 로그인/토큰 재발급 시도 (구현 단계에서 추가, B그룹) |
| INVALID_ACCOUNT_STATE | 409 | 현재 계정 상태에서 불가능한 관리자 작업 (ADMIN 정지 / 이미 정지된 계정 재정지 / ACTIVE 계정 정지 해제 / PENDING 아닌 계정 거절) (구현 단계에서 추가, B그룹) |
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
| RESERVATION_NOT_CANCELLABLE | 409 | `PAYMENT_CONFIRMED`가 아닌 예약을 취소 시도 (구현 단계에서 추가) |
| INVALID_WEBHOOK_SIGNATURE | 401 | PG 웹훅 서명 검증 실패, 헤더 누락, 타임스탬프 만료, 시크릿 미발급 (구현 단계에서 추가) |
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
