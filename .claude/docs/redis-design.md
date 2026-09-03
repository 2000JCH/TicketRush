# TicketRush — Redis 키 설계

## 설정

AOF/RDB 영속성 옵션은 켜지 않는다. decisions.md 1번의 결론을 그대로 따른다 — `HELD`는 휘발돼도 되는 임시 상태이고, `PAYMENT_CONFIRMED`는 이미 Outbox 패턴으로 DB에 원자적으로 저장되므로 Redis 자체의 영속성이 정합성에 필요하지 않다. 재시작/재연결 시엔 `system:rebuild_epoch` 마커 유무로 판단해 필요할 때만 DB 기준 rebuild 잡을 실행한다(decisions.md 1번).

**"홀드 TTL/만료 처리" 단계에서 실제로 `docker-compose.yml`에 반영(사용자 확인 완료)**: `redis-server --save ""`로 AOF뿐 아니라 이미지 기본값으로 켜져 있던 RDB 스냅샷도 함께 껐다. "혹시 몰라서" 켜두고 싶을 수 있는 지점이지만, 결제가 실제로 진행 중인 상태는 Redis가 아니라 `PAYMENT_REQUESTED` INSERT 시점부터 MySQL이 담당하고(decisions.md 5번) Redis 재시작 시 그 MySQL 기준으로 rebuild하므로, Redis 자체의 디스크 복구는 불필요한 비용(대량 접속 시 쓰기 오버헤드)만 될 뿐이라고 판단했다.

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
  - **홀드 전이 구현(구현 단계에서 확정)**: `AVAILABLE → HELD`는 `HSETNX seat_status:{eventId} seat:{seatId} HELD`로 구현했다(decisions.md 1번 — Lua 스크립트가 아니라 `HSETNX`로 단순화). `HSETNX`는 필드가 없을 때만 값을 쓰는 단일 명령이라 Redis 싱글 스레드 특성상 그 자체로 원자적이다. 해제는 `HDEL`로 필드를 지워 "필드 없음 = AVAILABLE" 규약으로 돌아간다.
- 스탠딩: `HINCRBY seat_status:{eventId} standing:{sectionId} -{quantity}` — Redis 싱글 스레드 특성상 단일 명령이 원자적이라 별도 락이 불필요하다(decisions.md 1번). 결과가 음수면 `HINCRBY`로 롤백 후 매진 응답.
- **rebuild 적용 방식**: 새 키(`seat_status:{eventId}:rebuilding` 등 임시 이름)에 DB 기준으로 좌석/스탠딩 상태를 다 채운 뒤 `RENAME`으로 한 번에 교체(decisions.md 1번). rebuild 중 대상 키가 없는 요청은 `rebuild:in_progress:{eventId}` 플래그(6번 참고)로 "일시 이용 불가"와 "진짜 에러"를 구분한다.

---

### 4. 홀드 TTL 추적

```
key:   hold:{eventId}:{seatId}                       (지정석)
       hold:{eventId}:{accountId}:{sectionId}         (스탠딩)
type:  String
value: accountId (지정석) / quantity (스탠딩)
TTL:   홀드 TTL(5~10분) — 아래 4-1번 `hold_schedule`이 만료 처리의 실제 주체이므로,
       이 키의 TTL은 스케줄러가 영원히 멈추는 극단적인 상황에서도 메모리가 무한히
       쌓이지 않도록 하는 보조 안전장치일 뿐이다.
```

그룹 홀드(좌석 최대 2개, 사용자 확인 완료)면 이 키가 좌석 개수만큼(최대 2개) 동시에 생기고, 그룹 단위 분산락(7번)이 이들을 원자적으로 만든다 — 하나만 성공하고 하나는 실패하는 부분 성공은 없다.

`seat_status:{eventId}` Hash 자체는 필드 단위 TTL을 걸 수 없으므로(Redis Hash는 필드별 만료를 지원하지 않음), 만료 판단의 원천은 원래 이 별도 키였다. 하지만 실제 만료 "처리"(좌석 상태 롤백)를 무엇이 트리거하는지는 4-1번 참고 — Redis의 자체 만료 알림(Keyspace Notification)이 아니라 우리가 직접 관리하는 스케줄로 대체했다.

---

### 4-1. 홀드 만료 스케줄 (구현 단계에서 설계 변경)

```
key:    hold_schedule
type:   Sorted Set (member=홀드 식별 문자열, score=만료 시각 epoch millis)
member: "SEAT:{eventId}:{accountId}:{sectionId}:{seatId}"           (지정석)
        "STANDING:{eventId}:{accountId}:{sectionId}:{quantity}"     (스탠딩)
TTL:    키 자체에는 없음 (개별 원소는 처리 완료 시 ZREM으로 제거)
```

**원래 설계였던 "Redis Keyspace Notification(`expired` 이벤트)을 구독하는 Consumer" 방식을 구현 단계에서 이 방식으로 교체했다(사용자 확인 완료)** — 두 가지 문제 때문이다:

1. **pub/sub 유실 위험**: Keyspace Notification은 그 순간 리스너가 연결되어 있어야만 받을 수 있는 실시간 방송이라, 앱이 배포/재시작으로 잠깐 끊긴 사이 좌석이 만료되면 그 알림은 재전송 없이 영구히 사라진다. 그러면 실제로는 아무도 안 잡고 있는 좌석이 `seat_status` Hash엔 영원히 `HELD`로 남아, 매진이 아닌데 팔리지 않는 "유령 좌석"이 생긴다.
2. **만료 시점엔 값을 읽을 수 없음**: `expired` 이벤트는 만료된 키 "이름"만 알려주고, 그 시점엔 값이 이미 삭제된 뒤라 읽을 수 없다. 스탠딩 홀드를 되돌리려면 quantity를 알아야 하는데, 원래 설계(`hold:{eventId}:{accountId}:{sectionId}`의 값에 quantity 저장)로는 만료 시점에 이 정보를 더 이상 얻을 방법이 없었다.

`hold_schedule`은 Redis의 만료 알림에 의존하지 않고, "만료 시각순으로 정렬된 할 일 목록"을 애플리케이션이 직접 관리해 두 문제를 모두 피한다 — member 문자열 자체에 롤백에 필요한 정보(eventId/accountId/sectionId/seatId 또는 quantity)를 전부 담아두므로 값을 따로 읽을 필요가 없고, 정렬 집합은 평범한 데이터라 앱이 잠깐 죽었다 살아나도 다음 스케줄 실행 때 밀린 항목을 그대로 이어서 처리한다(유실 없음 — 단, Redis 자체가 죽으면 다른 홀드 데이터와 마찬가지로 이 목록도 통째로 사라지는 것은 그대로다. 이건 decisions.md 1번에서 이미 감수하기로 한 위험과 같은 종류라 새로운 위험이 아니다).

**처리 흐름**(`HoldExpiryScheduler`, 대기열 입장 토큰 발급(4번)과 동일한 `@Scheduled` 폴링 패턴):

1. `ZRANGEBYSCORE hold_schedule -inf {현재 시각}` (배치 크기 제한)으로 만료 시각이 지난 항목들을 가져온다.
2. 각 member를 파싱해 지정석이면 `seat_status:{eventId}`의 `seat:{seatId}` 필드를 삭제, 스탠딩이면 `HINCRBY ... standing:{sectionId} {quantity}`로 되돌린다.
3. `hold:{eventId}:{seatId}` (또는 `hold:{eventId}:{accountId}:{sectionId}`) 키와 `active_reservation:{eventId}:{accountId}` 키를 `DEL`한다.
4. `hold_schedule`에서 처리한 member를 `ZREM`한다.

**명시적 해제(`DELETE .../seats/holds`)도 반드시 같은 member를 `hold_schedule`에서 `ZREM`해야 한다** — 그렇지 않으면, 사용자가 좌석을 풀고 다른 사용자가 같은 좌석을 새로 홀드한 뒤 원래 예약의 스케줄 항목이 뒤늦게 처리되면서 방금 생긴 새 홀드를 잘못 해제해버리는 사고가 생긴다.

**3단계 TTL 재설정 → ZADD/ZREM으로 대응(연동은 다음 단계에서 구현)**: 원래 설계의 "결제 요청 시 TTL 재설정 / 결제 확정 시 PERSIST"는 이 방식에서 각각 다음과 대응된다 — 결제 요청 시에는 같은 member로 `ZADD`(score를 결제 처리 타임아웃 시각으로 덮어씀, upsert라 자동으로 재설정된다), 결제 확정 시에는 `ZREM`(스케줄에서 완전히 제거해 다시는 만료되지 않게 함 — 원래 설계의 `PERSIST`와 같은 효과). 다만 결제 요청/확정 API 자체가 아직 없어 이 연동은 Saga/결제 연동 단계에서 이어서 구현한다.

**미정 사항**:
- 결제 처리 타임아웃의 구체적인 수치(PG 응답을 얼마나 기다릴지)
- Scheduler가 이미 만료 대상으로 집어든(2번 단계 진행 중인) 순간과 결제 요청이 같은 member를 재스케줄(`ZADD`)하는 순간이 겹치는 경우의 원자성 보장 방식 — 결제 연동 단계에서 구체화할 것 (기존 "Lua로 TTL 확인+재설정" 대신 이 스케줄 방식에 맞는 처리 순서를 정해야 함)

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
key:   system:rebuild_epoch:{eventId}
type:  String
value: rebuild 완료 시각(epoch millis)
TTL:   없음

key:   rebuild:in_progress:{eventId}
type:  String ("1")
TTL:   짧은 TTL(안전장치) + rebuild 완료 시 명시적 DEL
```

decisions.md 1번. `system:rebuild_epoch:{eventId}`가 있으면(=재연결일 뿐 데이터 유실 아님) 그 이벤트는 rebuild를 스킵해도 안전하다. 없으면(진짜 데이터 유실) `rebuild:in_progress:{eventId}` 락을 잡고 rebuild를 실행한다.

**이벤트별 스코프인 이유**: `seat_status:{eventId}` Hash가 이벤트 단위이므로(3번), 마커도 같은 스코프여야 한다. 전역 마커였다면 이벤트 A 하나만 rebuild하고도 마커가 세팅돼 아직 확인 안 된 이벤트 B/C까지 "안전하다"고 잘못 판정하게 된다(decisions.md 1번에서 이미 지적된 문제이자, 실제 구현이 아래 트리거 방식을 바꾸며 확정된 부분).

**구현(`SeatStatusRebuildService`, 2026-09-01)이 원안과 다른 점 — 카오스 테스트 A-1 준비 중 원안이 코드에 전혀 반영돼 있지 않았던 걸 발견해 구현하며 단순화함. A-1 실행 결과는 test-results.md 1번**:
- **트리거**: 원안(앱 기동 1회 + Redis 재연결 이벤트 리스너)은 그 시점에 "활성 이벤트"를 전부 나열해야 하는 부담이 있었다. 대신 좌석 조회/홀드/해제/결제확정/취소 등 `seat_status`를 실제로 읽거나 쓰는 요청이 들어올 때마다 그 이벤트 하나만 확인하는 방식으로 바꿨다 — 이벤트 목록을 나열할 필요가 없고, 트래픽 없는 이벤트는 rebuild할 이유도 없다.
- **원자적 교체**: 원안은 새 스테이징 키에 채운 뒤 `RENAME`으로 스왑했다. 실제로는 `rebuild:in_progress:{eventId}` 락이 이미 "rebuild 중에는 아무도 이 Hash를 읽지 않는다"를 보장하므로(락을 못 잡은 요청은 `SERVICE_TEMPORARILY_UNAVAILABLE`로 즉시 실패), 라이브 키를 직접 지우고 다시 채워도 동일한 안전성을 더 단순하게 얻는다.
- **범위 밖으로 남긴 것**: `hold_schedule`(만료 스케줄) 자체는 재구성하지 않는다 — 장애 중 방치된 홀드/결제 요청은 이번 rebuild로 "점유 중"까지는 정확히 반영되지만, 스스로 만료되는 스케줄은 유실된 채 남는다(다음 결제 시도 실패나 수동 정리로만 해소, 알려진 한계).

rebuild 락 자체는 단일 사이즈(짧은 TTL의 Redis `SETNX`)만으로 충분하다고 판단했다 — decisions.md 2번의 분산락 벤치마크(Redisson RLock vs DB 비관적 락)는 그룹 좌석 홀드처럼 "정합성 + 성능"을 동시에 따져야 하는 자리에 쓰는 것이고, rebuild 가드는 저빈도(마커 없을 때만) 실행이라 그 벤치마크 대상이 아니다.

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
       구현 단계에서 확정한 실제 인코딩: "SEAT:{sectionId}:{seatId}" (지정석) / "STANDING:{sectionId}:{quantity}" (스탠딩)
TTL:   홀드 TTL과 동일하게 시작. 4번과 마찬가지로 실제 만료 처리는 `hold_schedule`(4-1번)이 담당하고,
       이 키의 TTL은 보조 안전장치일 뿐이다. 결제 확정/실패/명시적 해제 시에는 즉시 DEL한다.
```

**"한 계정은 한 이벤트에 대해 동시에 진행 중인 예약 시도를 1건만 가질 수 있다"**는 사용자 확인 규칙(사재기 방지)을 강제하는 키다. 좌석 홀드 요청이 들어오면 `SETNX`로 이 키를 먼저 선점하고, 이미 존재하면(다른 시도가 진행 중) `ACTIVE_RESERVATION_EXISTS` 에러로 거절한다(api-design.md 참고). 좌석 홀드 자체(1~2개 그룹)는 이 키와 별개로 여전히 허용된다 — 이 키가 막는 건 "별도의 두 번째 시도"이지 "한 시도 안의 여러 좌석"이 아니다.

**해제 시점(모두 이 키를 DEL한다)**:
- 사용자가 명시적으로 홀드 해제(`DELETE .../seats/holds`) 호출 시
- 홀드 TTL 만료(방치) 시 — 4-1번의 `HoldExpiryScheduler`가 좌석 상태 롤백과 함께 처리
- 결제 처리 타임아웃(웹훅 무응답) 시 — 결제 연동 단계에서 같은 스케줄 방식으로 처리 예정
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
| `hold:{eventId}:{seatId}` / `hold:{eventId}:{accountId}:{sectionId}` | 홀드 성공 시 | `HoldExpiryScheduler` 처리 또는 명시적 해제 시 DEL | 홀드 TTL(보조 안전장치, 실제 만료 처리는 `hold_schedule`이 담당) |
| `hold_schedule` (Sorted Set) | 홀드 성공 시 `ZADD` | 만료 처리/명시적 해제 시 `ZREM`, 결제 요청 시 `ZADD`로 재스케줄 예정(다음 단계) | 없음(원소 단위로 관리) |
| `idempotency:{key}` | 결제 요청 시 `SETNX` | — | 홀드 TTL과 동일 |
| `system:rebuild_epoch:{eventId}` | 이벤트 등록 시 + rebuild 완료 시 | seat_status를 읽거나 쓰는 요청마다 존재 확인 | 없음 |
| `rebuild:in_progress:{eventId}` | rebuild 시작 시 | rebuild 완료 시 DEL | 짧은 TTL(안전장치) |
| 그룹 좌석 락 | 미정 | 미정 | 미정 |
| `active_reservation:{eventId}:{accountId}` | 홀드 성공 시 `SETNX` | `hold` 키와 동일한 시점(`HoldExpiryScheduler`/명시적 해제)에 DEL | `hold` 키와 동일(보조 안전장치) |
| `refresh_token:{accountId}` | 로그인 성공 시 `SET` | 재발급 시 `SET`(덮어씀), 로그아웃 시 `DEL` | Refresh Token 만료 기간 |

---

## 장애 대비

Redis가 재시작되면, `seat_status:{eventId}`를 읽거나 쓰는 요청이 들어올 때마다 그 이벤트의 `system:rebuild_epoch:{eventId}` 마커 유무로 데이터 유실 여부를 먼저 판별한다(decisions.md 1번, `SeatStatusRebuildService`). 마커가 없으면(진짜 유실) `rebuild:in_progress:{eventId}` 락을 잡고, DB(`reservation`의 `PAYMENT_CONFIRMED` + 결제 처리 타임아웃 안 지난 `PAYMENT_REQUESTED`)를 기준으로 `seat_status:{eventId}`를 다시 채운 뒤 마커를 세팅한다. 락을 못 잡은(=다른 요청이 이미 rebuild 중인) 요청은 `SERVICE_TEMPORARILY_UNAVAILABLE`(503)로 즉시 실패한다 — 매진과는 반드시 구분되는 별도 상태다(api-design.md 4번). 이 락이 "rebuild 중에는 아무도 이 Hash를 읽지 않는다"를 이미 보장하므로, lazy 초기화(오버셀로 이어질 수 있어 금지)나 별도 스테이징 키 없이도 안전하게 라이브 키를 직접 재구성한다(6번 참고 — 원안의 RENAME 스왑을 락으로 대신함).

## 남은 항목 (progress.md에서 계속 추적)

- 홀드 TTL과 결제 처리 시간의 경합 처리(4-1번 "미정 사항" 참고) + 결제 처리 타임아웃의 구체적 수치
- 그룹 좌석 홀드 락의 구체적 키 패턴 — decisions.md 2번 벤치마크 완료 후
