# TicketRush — Redis 키 설계

## 설정

AOF/RDB 영속성 옵션은 켜지 않는다. decisions.md 1번의 결론을 그대로 따른다 — `HELD`는 휘발돼도 되는 임시 상태이고, `PAYMENT_CONFIRMED`는 이미 Outbox 패턴으로 DB에 원자적으로 저장되므로 Redis 자체의 영속성이 정합성에 필요하지 않다. 재시작/재연결 시엔 `system:rebuild_epoch` 마커 유무로 판단해 필요할 때만 DB 기준 rebuild 잡을 실행한다(decisions.md 1번).

---

## Key 목록

### 1. 대기열 순번

```
key:   queue:{eventId}
type:  Sorted Set (member=accountId, score=진입시각)
TTL:   없음 (매진/이벤트 종료 시 일괄 DEL)
```

decisions.md 4번. `ZRANK`로 순번 조회, Scheduler가 `ZRANGE`로 상위 N명을 뽑아 입장 토큰을 발급한다. 개별 이탈은 추정하지 않고 매진/종료 시점에 이벤트 전체를 일괄 만료시킨다.

---

### 2. 입장 토큰

```
key:   entry_token:{eventId}:{accountId}
type:  String
value: 토큰 값 (클라이언트는 `X-Entry-Token` 헤더로 이 값을 그대로 전달, api-design.md 3번 참고)
TTL:   좌석 홀드 TTL과 동일
```

decisions.md 4번. 대기열 통과 시 발급되고, 좌석(또는 그룹) 홀드 성공 시점에 홀드 만료 시각에 맞춰 TTL을 재발급(갱신)한다. 토큰이 실제로 만료되면 순번을 유지한 채 재발급하지 않고 대기열에 새로 진입시킨다(공정성 원칙). 좌석 조회/선택/홀드 API에만 적용되고 결제 확정 웹훅에는 적용되지 않는다.

---

### 3. 좌석 상태 + 스탠딩 잔여 수량 (통합 Hash)

```
key:    seat_status:{eventId}
type:   Hash
fields: seat:{seatId}      → "AVAILABLE" | "HELD"   (필드 없음 = AVAILABLE로 간주, 메모리 절약)
        standing:{sectionId} → 잔여 수량 (Integer as string, 항상 명시적으로 세팅)
        meta:initialized     → "1" (초기화 완료 표시, 항상 세팅)
TTL:    없음 (이벤트 종료 시 DEL)
```

**`meta:initialized` 필드를 두는 이유(구현 단계에서 발견)**: 지정석은 "필드 없음 = AVAILABLE"이라 등록 시점에 세팅할 필드가 없고, 스탠딩 구역이 하나도 없는 이벤트(전 좌석 지정석)는 세팅할 필드가 아예 0개가 된다. Redis는 필드가 없는 Hash를 보관하지 않으므로 이 경우 키 자체가 생기지 않고, 그러면 정상적으로 등록된 이벤트가 decisions.md 1번의 "키 없음 = 데이터 유실" 판정에 걸려 오탐이 난다. 초기화되었다는 사실 자체를 남기는 필드를 항상 하나 넣어 키의 존재를 보장한다.

decisions.md 1번(rebuild 원자적 스왑)과 방금 확정한 "`standing:remaining`을 좌석 상태 Hash에 통합" 결정을 반영했다. 지정석과 스탠딩이 한 이벤트 안에 혼합되는 게 기본 시나리오(decisions.md 3번 프로젝트 정의)라, 이벤트 단위 Hash 하나로 묶어야 rebuild `RENAME` 한 번으로 지정석·스탠딩이 동시에 스왑되고 "한쪽만 복구된" 중간 상태가 생기지 않는다.

- 지정석: `HGET seat_status:{eventId} seat:{seatId}` → 값이 없거나 `AVAILABLE`이면 선택 가능. 결제 확정 시에도 값은 그대로 `HELD`로 유지되고(영구), TTL이 없는 `hold:{eventId}:{seatId}` 키로 "임시 홀드"와 "확정 판매"를 구분한다(아래 4번 참고).
- 스탠딩: `HINCRBY seat_status:{eventId} standing:{sectionId} -{quantity}` — Redis 싱글 스레드 특성상 단일 명령이 원자적이라 별도 락이 불필요하다(decisions.md 1번). 결과가 음수면 `HINCRBY`로 롤백 후 매진 응답.
- **rebuild 적용 방식**: 새 키(`seat_status:{eventId}:rebuilding` 등 임시 이름)에 DB 기준으로 좌석/스탠딩 상태를 다 채운 뒤 `RENAME`으로 한 번에 교체(decisions.md 1번). rebuild 중 대상 키가 없는 요청은 `rebuild:in_progress:{eventId}` 플래그(6번 참고)로 "일시 이용 불가"와 "진짜 에러"를 구분한다.

---

### 4. 홀드 TTL 추적

```
key:   hold:{eventId}:{seatId}                       (지정석)
       hold:{eventId}:{accountId}:{sectionId}         (스탠딩)
type:  String
value: accountId (지정석) / quantity (스탠딩)
TTL:   홀드 TTL(5~10분)
```

그룹 홀드(좌석 최대 2개, 사용자 확인 완료)면 이 키가 좌석 개수만큼(최대 2개) 동시에 생기고, 그룹 단위 분산락(7번)이 이들을 원자적으로 만든다 — 하나만 성공하고 하나는 실패하는 부분 성공은 없다.

`seat_status:{eventId}` Hash 자체는 필드 단위 TTL을 걸 수 없으므로(Redis Hash는 필드별 만료를 지원하지 않음), 만료 판단의 원천은 이 별도 키가 맡는다. 만료 시 Redis Keyspace Notification(`expired` 이벤트)을 구독하는 Consumer가:

- 지정석: `seat_status:{eventId}`에서 `seat:{seatId}` 필드를 삭제(AVAILABLE로 복귀)
- 스탠딩: `HINCRBY seat_status:{eventId} standing:{sectionId} {quantity}`로 되돌림

**이 키의 TTL은 3단계로 바뀐다** — `PERSIST`(영구 제거)가 아니라 매번 재설정(RE-EXPIRE)한다는 게 핵심이다:

1. 홀드 성공 시: TTL = 홀드 TTL(5~10분)
2. 결제 요청(`PAYMENT_REQUESTED`) 시: TTL을 **결제 처리 타임아웃**(PG 응답 대기 한도, 홀드 TTL과는 별개의 짧은 값 — 구체적 수치는 미정)으로 재설정
3. 결제 확정(`PAYMENT_CONFIRMED`) 시: `PERSIST`로 **TTL만** 제거 (키 자체는 그대로 남아있어야 함 — 3번의 "TTL 없는 `hold` 키 = 확정 판매" 구분 방식이 이 키의 존재를 전제로 하기 때문. 다시는 만료되면 안 됨)

이렇게 하는 이유: 2단계에서 `PERSIST`로 아예 없애버리면 **PG가 웹훅을 끝내 보내지 않는 타임아웃 상황을 아무도 감지하지 못한다** — decisions.md 5번이 "결제 실패/타임아웃 시 좌석 자동 반납"이라고 명시했는데, TTL을 없애버리면 타임아웃을 감지할 메커니즘 자체가 사라지기 때문이다. 3단계까지 TTL을 유지하면 동일한 Keyspace Notification Consumer가 "홀드 방치 만료"와 "결제 요청 후 응답 없음 타임아웃"을 같은 방식으로 처리할 수 있다 — 후자의 경우 Redis 쪽 롤백에 더해 `reservation`을 `PAYMENT_FAILED → SEAT_RELEASED`로 UPDATE하는 것까지 이 Consumer가 담당한다(DB 행이 이미 존재하므로, 1단계 만료와 달리 여기선 DB 업데이트가 실제로 일어난다).

**미정 사항**:
- 결제 처리 타임아웃의 구체적인 수치(PG 응답을 얼마나 기다릴지)
- 홀드 TTL이 결제 처리 시간과 정확히 어떻게 경합하는지(예: 결제 요청이 TTL 만료 시각 직전에 들어오는 경우의 원자성 보장 방식) — 구현 단계에서 Lua 스크립트로 "TTL 확인 + 재설정"을 원자적으로 묶는 방식 등을 검토할 것

---

### 5. 요청 멱등성

```
key:   idempotency:{idempotencyKey}
type:  String (SETNX)
value: "1" 또는 reservation 임시 식별자
TTL:   홀드 TTL과 동일
```

decisions.md 5번. 결제 요청 API가 PG를 호출하기 직전 `SETNX`로 선점하고, 실패하면 중복 요청으로 간주해 거절한다. Redis가 죽어 이 키가 유실되는 상황에 대비한 2차 방어선은 `reservation.idempotency_key` DB UNIQUE 제약이다(db-schema.md 참고).

---

### 6. Rebuild 상태 마커

```
key:   system:rebuild_epoch
type:  String
value: rebuild 완료 시각 또는 임의 마커 값
TTL:   없음

key:   rebuild:in_progress:{eventId}
type:  String ("1")
TTL:   짧은 TTL 병행 (안전장치) + RENAME 완료 시 명시적 DEL
```

decisions.md 1번. `system:rebuild_epoch`는 전역 마커로, 앱 기동/재연결 시 이 마커가 살아있으면 데이터가 안전하다는 뜻이라 rebuild를 스킵한다. 없으면(진짜 데이터 유실) 이벤트별로 `rebuild:in_progress:{eventId}`를 세팅하고 rebuild를 실행한다.

**스코프를 이벤트 단위로 정한 이유**: `seat_status:{eventId}` Hash도 이벤트 단위로 `RENAME` 스왑되므로(3번), 플래그도 같은 스코프여야 앞뒤가 맞는다. 전역 플래그로 두면 한 이벤트의 rebuild 중에 트래픽과 무관한 다른 콘서트 조회 API까지 "일시 이용 불가"로 막아버리는 부작용이 생긴다(decisions.md 1번에서 이미 지적된 문제).

rebuild 실행 자체(다중 인스턴스 환경에서 단일 인스턴스만 수행)는 decisions.md 2번에서 채택되는 분산락 기술을 재사용해서 가드한다.

---

### 7. 그룹 좌석 홀드 락

```
key:   (미정 — decisions.md 2번 벤치마크로 Redisson RLock vs DB 비관적 락 확정 후 구체화)
```

다중좌석 동시선택(그룹 홀드, 최대 2매) 시에만 필요하고, 단일 좌석 홀드(Lua 스크립트)는 이 락 대상이 아니다(decisions.md 1, 2번). 벤치마크 결과 Redisson RLock이 채택되면 이 섹션에 실제 키 패턴(`lock:group:{eventId}:...` 등)을 채운다.

---

### 8. 계정당 동시 진행 예약 제한

```
key:   active_reservation:{eventId}:{accountId}
type:  String (SETNX)
value: 이번 시도로 홀드한 seatId 목록 또는 sectionId+quantity (해제 시 무엇을 되돌릴지 참고용)
TTL:   홀드 TTL과 동일하게 시작, 결제 요청 시 결제 처리 타임아웃으로 재설정, 결제 확정/실패/해제 시 즉시 DEL (4번 `hold` 키와 동일한 생명주기)
```

**"한 계정은 한 이벤트에 대해 동시에 진행 중인 예약 시도를 1건만 가질 수 있다"**는 사용자 확인 규칙(사재기 방지)을 강제하는 키다. 좌석 홀드 요청이 들어오면 `SETNX`로 이 키를 먼저 선점하고, 이미 존재하면(다른 시도가 진행 중) `ACTIVE_RESERVATION_EXISTS` 에러로 거절한다(api-design.md 참고). 좌석 홀드 자체(1~2개 그룹)는 이 키와 별개로 여전히 허용된다 — 이 키가 막는 건 "별도의 두 번째 시도"이지 "한 시도 안의 여러 좌석"이 아니다.

**해제 시점(4개 모두 이 키를 DEL한다)**:
- 사용자가 명시적으로 홀드 해제(`DELETE .../seats/holds`) 호출 시
- 홀드 TTL 만료(방치) 시 — 4번의 Keyspace Notification Consumer가 좌석 상태 롤백과 함께 처리
- 결제 처리 타임아웃(웹훅 무응답) 시 — 역시 4번의 동일 Consumer가 처리
- 결제 확정(`PAYMENT_CONFIRMED`) 또는 명시적 실패(웹훅이 실패를 알려준 경우) 시 — 해당 시도가 "끝났으므로" 다음 시도를 허용해야 함(단, 확정된 경우는 db-schema.md `idx_account_event_status` 누적 조회가 별도로 "이미 2매 다 샀는지"를 막는다)

---

### 9. Refresh Token

```
key:   refresh_token:{accountId}
type:  String
value: 발급된 Refresh Token 값
TTL:   Refresh Token 만료 기간
```

decisions.md 3번(사용자 확인 완료). 클라이언트에는 httpOnly Cookie로 전달하고, `/auth/refresh` 요청마다 쿠키 값과 이 키의 값을 대조해 검증한다(불일치/키 없음이면 재로그인 필요). 로그인/재발급 성공 시 `SET`(기존 값 덮어씀), 로그아웃 시 `DEL`로 즉시 무효화한다.

**Refresh Token 값은 JWT로 만든다(구현 단계에서 확정)**: `/auth/refresh`는 인증 헤더 없이 호출되므로, 서버가 쿠키 값만 보고 `accountId`를 알아내야 이 키를 조회할 수 있다. 불투명한 랜덤 문자열을 쓰면 "토큰 → accountId" 역인덱스를 Redis에 하나 더 둬야 해서, subject에 `accountId`가 들어있는 JWT를 쓰는 쪽이 키 개수가 줄어든다. 단 JWT의 `iat`/`exp`는 초 단위라 같은 초에 두 번 발급하면 토큰 문자열이 완전히 같아져 회전(재발급 시 기존 토큰 무효화)이 무의미해지므로, 발급마다 고유한 `jti` 클레임을 넣어 항상 다른 값이 나오게 한다.

**계정당 1개만 유지 → 다중 기기 로그인 미지원(사용자 확인 완료)**: 키가 `accountId` 단위라 새 로그인이 기존 값을 덮어쓴다. 즉 다른 기기에서 로그인하면 이전 기기의 Refresh Token은 자동으로 무효화되어, 그 기기는 이후 `/auth/refresh`가 실패하고 재로그인이 필요해진다.

---

## 전체 Key 요약

| Key | 초기화 시점 | 갱신 시점 | TTL |
|---|---|---|---|
| `queue:{eventId}` | 첫 진입 시 | 진입마다 `ZADD` | 매진/종료 시 일괄 DEL |
| `entry_token:{eventId}:{accountId}` | 대기열 통과 시 | 홀드 성공 시 TTL 갱신 | 홀드 TTL과 동일 |
| `seat_status:{eventId}` | 이벤트 등록 시 전체 AVAILABLE(필드 미설정)로 초기화 | 홀드/해제/rebuild 시 | 없음 |
| `hold:{eventId}:{seatId}` / `hold:{eventId}:{accountId}:{sectionId}` | 홀드 성공 시 | 결제 요청 시 결제 처리 타임아웃으로 재설정, 결제 확정 시 PERSIST | 홀드 TTL → (결제 요청 시) 결제 처리 타임아웃 → (확정 시) 없음 |
| `idempotency:{key}` | 결제 요청 시 `SETNX` | — | 홀드 TTL과 동일 |
| `system:rebuild_epoch` | rebuild 완료 시 | 재연결마다 확인 | 없음 |
| `rebuild:in_progress:{eventId}` | rebuild 시작 시 | RENAME 완료 시 DEL | 짧은 TTL(안전장치) |
| 그룹 좌석 락 | 미정 | 미정 | 미정 |
| `active_reservation:{eventId}:{accountId}` | 홀드 성공 시 `SETNX` | `hold` 키와 동일한 시점에 재설정/DEL | `hold` 키와 동일 |
| `refresh_token:{accountId}` | 로그인 성공 시 `SET` | 재발급 시 `SET`(덮어씀), 로그아웃 시 `DEL` | Refresh Token 만료 기간 |

---

## 장애 대비

Redis가 재시작되거나 재연결되면 `system:rebuild_epoch` 마커 유무로 데이터 유실 여부를 먼저 판별한다(decisions.md 1번). 유실이 확인되면 이벤트별로 `rebuild:in_progress:{eventId}`를 세팅하고, DB(`reservation`의 `PAYMENT_CONFIRMED` + TTL 안 지난 `PAYMENT_REQUESTED`)를 기준으로 `seat_status:{eventId}`를 새 키에 재구성한 뒤 `RENAME`으로 원자적으로 교체한다. rebuild 중 대상 키가 없는 조회/홀드 요청은 플래그가 켜져 있으면 "일시 이용 불가"로, 플래그 없이 키만 없으면 에러+알림으로 처리한다(lazy 초기화 금지 — 오버셀로 이어질 수 있어 원천 차단).

## 남은 항목 (progress.md에서 계속 추적)

- 홀드 TTL과 결제 처리 시간의 경합 처리(4번 "미정 사항" 참고)
- 그룹 좌석 홀드 락의 구체적 키 패턴 — decisions.md 2번 벤치마크 완료 후
