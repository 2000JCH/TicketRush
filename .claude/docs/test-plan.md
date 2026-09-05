# TicketRush — 테스트 계획

이 프로젝트의 핵심 주장은 **"오픈 폭주 트래픽에서도 오버셀 없이, 장애가 나도 안 깨진다"를 실측으로 증명한다**
(decisions.md 8번)이다. 이 문서는 그 실측을 **하기 전에** 목표 수치·시나리오·절차·합격 기준을 못박아
둔다 — 결과가 나온 뒤에 "이 정도면 괜찮은 것 같다"고 합리화하지 않기 위해서다.

## 문서 역할 구분

| 문서 | 담는 것 |
|---|---|
| **이 문서 (`test-plan.md`)** | 테스트 **전**: 목표 수치, 시나리오, 실행 절차, 합격 기준 |
| `test-results.md` | 테스트 **후**: 실측값 (단일 출처) |
| `decisions.md` | **왜** 이렇게 테스트하는가 (도구 선택 이유, 분산락 채택 규칙) |
| `aws-spec.md` | AWS 인스턴스 스펙 분석 + 배포 후 실측(D·E)은 `test-results.md`에서 가져옴 |
| `portfolio.md` | 결과를 "문제와 해결" 스토리로 정리 |

---

## 0. 테스트 환경

| 항목 | 값 |
|---|---|
| 로컬 (평소) | Windows PC 1대 + Docker Compose (MySQL / Redis / Kafka(KRaft) / Kafka Connect / Nginx / Prometheus / Grafana), 백엔드는 호스트에서 `gradlew bootRun`, 리소스 제한 없음 |
| 로컬 (리허설) | 아래 0-1번 — AWS 스펙만큼 CPU/메모리를 미리 제한해서 로컬에서 먼저 재현 |
| AWS | EC2 `m6i.xlarge`(잠정) + RDS(MySQL). 카오스는 로컬만, AWS는 부하 테스트만 재측정 (decisions.md 10번) |
| 부하 도구 | Gatling (`io.gatling.gradle` 3.15.1.3), `GoldenPathSimulation`. BUYER 계정은 `seed-load-test.ps1`가 미리 가입·로그인까지 끝내 `buyers.csv`로 공급(측정 구간에서 signup/login 제외) |
| 장애 주입 | Pumba (`gaiaadm/pumba:0.11.6`), `scripts/chaos-{redis,kafka}.ps1` |
| 관찰 | Grafana 대시보드 `ticketrush-load` (응답시간 P50/P95/P99 · 상태코드별 요청/에러율 · Kafka Consumer lag · HikariCP) |

### 0-1. 로컬 리허설 (AWS 스펙 제한, `docker-compose.rehearsal.yml`)

AWS는 EC2 한 대(4 vCPU/16 GiB)에 돈을 태우기 전에, 로컬 PC를 그 스펙만큼 미리 제한해서 같은 조건으로
먼저 돌려본다 — 특히 **4번 한계 테스트**는 결과가 "노트북 성능"이 아니라 "AWS에서 몇 명까지 버틸지"에
가까워야 의미가 있고, AWS에 올린 뒤에 한계가 로컬보다 훨씬 낮게 나오면 요금이 나가는 상태에서 원인
분석·재배포를 반복해야 한다(2026-09-01 확정, 사용자 문제 제기).

- 평소 개발(`docker-compose.yml` 단독)에는 전혀 영향 없음 — 리허설은 완전히 별도 오버레이 파일.
- 리허설에서는 앱도 `ticketrush-backend/Dockerfile`로 빌드해 컨테이너로 띄운다(평소엔 `gradlew bootRun`).
- 예산 분배: EC2 몫(4 vCPU/16 GiB, `aws-spec.md` B-1)을 app(2/6g)+kafka(1/4g)+kafka-connect(0.5/2g)+
  redis(0.25/1g)+nginx(0.25/1g)가 나눠 쓰고, RDS 몫(2 vCPU/8 GiB, `aws-spec.md` B-2 db.m6i.large
  기준)은 mysql이 별도로 쓴다 — 실제로도 RDS는 EC2와 분리된 컴퓨트라 예산을 안 나누기 때문. 분산락
  벤치마크에서 DB 락이 채택되면 RDS 후보가 `db.r6i.large`(16 GiB)로 바뀌므로 그때 mysql의
  `mem_limit`도 16g로 올려 재확인한다. Prometheus/Grafana는 관찰 도구일 뿐 AWS EC2 박스에 포함되는
  구성요소가 아니라 제한 대상에서 뺐다.
- 실행:
  ```powershell
  docker compose -f docker-compose.yml -f docker-compose.rehearsal.yml up -d --build
  powershell -File scripts\register-outbox-connector.ps1   # 컨테이너 새로 뜰 때마다 재등록 필요
  ```
  Gatling은 `-DbaseUrl=http://localhost:8081`(Nginx 경유)로 실제 트래픽 경로와 동일하게 실행한다.
- 빌드·기동·헬스체크(app 2 vCPU/6 GiB, mysql 2 vCPU/8 GiB로 제한 적용 확인 + Nginx 경유 응답)까지
  검증 완료(2026-09-01).

---

## 1. 목표 수치 (기준선)

목표치는 "이만큼 처리해야 한다"는 요구사항이 아니라 **"이 선을 넘으면 문제라고 미리 정한 가설"** 이다.
실 트래픽 데이터가 없는 1인 프로젝트라 일부는 임의적이지만, 각 값에 근거를 붙여 면접에서 설명 가능하게 한다.

### 1-1. 정합성 (절대 기준, 협상 불가)

| 지표 | 목표 | 근거 |
|---|---|---|
| 오버셀 | **0건** — 한 좌석이 두 예약에 활성으로 잡히지 않는다 | 이 프로젝트의 존재 이유. 장애 중/후에도 동일 |
| 스탠딩 초과 판매 | **0건** — 확정 수량 합 ≤ 구역 총량 | 위와 동일 |
| 이벤트 유실 | **0건** — outbox에 들어간 `PAYMENT_FAILED`는 결국 좌석 반납까지 도달 | decisions.md 6·8번 (at-least-once + 멱등 소비자) |

### 1-2. 성능 (로컬, 동시 300명 기준)

| 지표 | 목표 | 근거 |
|---|---|---|
| 지속 가능 동시 사용자 | **300명** (`atOnceUsers` 상당) | classq(참고 프로젝트)가 쓴 값이라 비교 가능. 좌석 40개에 300명이면 경합이 충분히 생김. PC 1대(앱+DB+Redis+Kafka 공존)로는 이 이상 밀면 "노트북 성능"만 재게 됨 — 실제 한계는 4번(한계 테스트)이 따로 찾는다 |
| P95 — 좌석 조회 (`GET /seats`) | **< 1,000ms** | 읽기 경로. 사용자가 좌석표를 보는 첫 화면이라 느리면 이탈 |
| P95 — 홀드→결제 요청 | **< 2,000ms** | 쓰기 경로. 티켓팅 UX상 몇 초는 "자리 잡는 중"으로 참지만 그 이상은 멈춘 줄 안다. classq 로컬이 2,845ms였고 그것을 "고쳐야 할 문제"로 판단했음 |
| **P99 — 그룹 홀드 (`POST /seats/holds`, 좌석 2개)** | **< 3,000ms** | 멘토 피드백: 사용자가 체감하는 건 평균이 아니라 "제일 느렸던 사람"이고, 하위 1%가 몇 초씩 기다리면 창을 닫는다. P95보다 한 칸 느슨하게 잡되 3초를 상한으로 |
| 에러율 (5xx + 락 획득 타임아웃) | **< 1%** | 경합으로 인한 409(`SEAT_ALREADY_HELD` 등)는 시스템이 정상 동작한 결과라 에러에서 제외. 진짜 실패(5xx)와 락 타임아웃만 카운트 |

### 1-3. 장애 복구 (카오스 테스트)

| 지표 | 목표 | 근거 |
|---|---|---|
| Redis 재시작 → 정상 응답 복귀 | **< 30초** | 40석 이벤트의 rebuild(DB→Redis 재구성)는 실제로는 수 초면 끝난다. 30초는 "이건 쉽게 넘겨야 정상"인 넉넉한 상한 — 넘으면 rebuild 로직에 문제 |
| Redis 장애 중·후 오버셀 | **0건** | 1-1번. `PAYMENT_REQUESTED`/`PAYMENT_CONFIRMED` 좌석은 DB 기준 재구성돼 재판매 안 됨 (decisions.md 1번) |
| Kafka 복구 → Consumer lag 0 도달 | **< 60초** | 90초 장애 동안 쌓인 outbox 백로그를 따라잡는 시간. 브로커가 돌아오면 컨슈머가 earliest부터 밀린 것을 소비 |
| Kafka 장애 중 결제 확정 실패 | **0건** | 결제 확정은 DB 트랜잭션 + outbox INSERT라 Kafka와 무관하게 완료돼야 함 (decisions.md 8번) |

### 1-4. AWS (배포 후 SLO)

로컬 1-2번 목표를 **AWS `m6i.xlarge` + RDS 환경에서 달성**하는 것을 SLO로 한다. 구체 수치는 로컬 실측 →
`aws-spec.md` D(예측표) → E(SLO) 순으로 산출하고, 배포 후 재측정으로 검증한다. decisions.md 11번의
"성능/처리량 목표치 미정" 항목이 여기서 닫힌다.

---

## 2. 카오스 테스트

### 공통 준비

1. `docker compose up -d` + 백엔드 `gradlew bootRun`
2. `powershell -File scripts/seed-load-test.ps1 -Rows 20 -SeatsPerRow 20 -BuyerCount 400` → `eventId`, `sectionId` 확보 (400석 / BUYER 400명 사전 로그인)
3. Grafana(`localhost:3000`, admin/admin) → 대시보드 `TicketRush — 부하/카오스 관찰` 열어두기
4. 장애 주입 시점을 Grafana 타임라인에 annotation으로 남기기 (`scripts/chaos-timeline.log`의 UTC 시각)

### 카오스 부하 규모 (A-1·A-2 공통)

`run-gatling.ps1 -Users 150 -RampSeconds 40 -TailSeconds 120` 은 **총 390 VU**를 투입한다:

| 구간 | 인원 | 방식 |
|---|---|---|
| 버스트 | **105명** (`-Users` 150 × `-BurstRatio` 0.7) | 오픈 순간처럼 완전 동시 큐 진입 (`atOnceUsers`) |
| 트리클 | **45명** (150 − 105) | 40초에 걸쳐 분산 유입 (`rampUsers`) |
| 꼬리 | **240명** (초당 2명 × 120초) | 위가 끝나고 40초 뒤부터 지속 — 장애 **복구 후**에도 트래픽이 이어져 Grafana에 "정상 복귀"가 찍히게 하기 위함 |

동시 부하 피크는 버스트 105 + 트리클 45 ≈ **150명**이 만들고, 꼬리는 초당 2명 도착이라 어느 순간에도 10~20명 수준의 얇은 흐름이다. 카오스는 "몇 명까지 버티나"(→ 4번 한계 테스트)가 아니라 "장애가 나도 정합성이 깨지지 않나"가 목적이므로, 좌석 400개에 경합이 충분히 생기는 이 규모로 고정한다. `-BuyerCount`는 390 이상이어야 계정이 재사용되지 않는다.

### 시나리오 A-1 — Redis 다운  ✅ 실행 완료 (2026-09-03, test-results.md 1번)

| 순서 | 동작 |
|---|---|
| 1 | `powershell -File scripts/run-gatling.ps1 -EventId <id> -SectionId <sid> -Users 150 -RampSeconds 40 -TailSeconds 120` (총 390 VU — 위 "카오스 부하 규모" 표 참고) |
| 2 | 좌석 홀드 트래픽이 흐르기 시작하면: `powershell -File scripts/chaos-redis.ps1 -DurationSec 60` — **단, Pumba `stop --restart`가 이 환경에서 불안정("no containers to stop")하므로 실제로는 `docker stop ticketrush-redis` → 60초 → `docker start ticketrush-redis`를 직접 썼다.** stop/start UTC 시각은 `scripts/chaos-timeline.log`에 기록 |
| 3 | Grafana 관찰: 정지 중 에러율 급등 → 재시작 후 rebuild → 정상 복귀까지 시간 측정. `chaos-timeline.log`의 시각으로 annotation, 4패널 캡처 → `.claude/screenshots/tests/a1-redis-down/` |
| 4 | Gatling 종료 후 정합성 검증 (아래 SQL, event_id 스코프) |

**정합성 검증 SQL** (`docker exec -e MYSQL_PWD=root ticketrush-mysql mysql -uroot ticketrush -e "..."`):

> **2026-09-01 정정**: 아래는 원래 `rs.status='ACTIVE'`(존재하지 않는 enum 값이라 항상 0행 = 거짓 통과)와
> `rs.section_id`(존재하지 않는 컬럼)를 쓰고 있었다 — 실제 실행 중 발견해 고쳤다. `reservation_seat.status`는
> `reservation.status`와 같은 enum(`PAYMENT_REQUESTED`/`PAYMENT_CONFIRMED`/`PAYMENT_FAILED`/`SEAT_RELEASED`)을
> 쓰고, 구역은 `seat.section_id`를 거쳐야 조인된다.

```sql
-- (a) 한 좌석에 활성 예약 2건 이상 → 오버셀. rs.status가 아니라 r.status로 거른다 — 결제 실패
-- (markPaymentFailed) 시점에 부모(reservation)는 즉시 PAYMENT_FAILED가 되지만 자식(reservation_seat)은
-- Kafka Consumer가 releaseAfterFailure를 처리할 때까지 잠깐 PAYMENT_REQUESTED로 남아있을 수 있어서다.
SELECT rs.seat_id, COUNT(*) c
FROM reservation_seat rs JOIN reservation r ON r.id = rs.reservation_id
WHERE r.status IN ('PAYMENT_REQUESTED','PAYMENT_CONFIRMED')
GROUP BY rs.seat_id HAVING c > 1;              -- 0행 = 통과

-- (b) 확정 매수가 구역 정원을 넘는지 (reservation_seat -> seat -> section 순으로 조인)
SELECT s.id, s.total_quantity,
       (SELECT COUNT(*) FROM reservation_seat rs JOIN seat sk ON sk.id = rs.seat_id
        WHERE sk.section_id = s.id AND rs.status='PAYMENT_CONFIRMED') AS confirmed
FROM section s WHERE s.id=<sid>;               -- confirmed <= total_quantity
```

**합격 기준**: (a)(b) 모두 통과 + Redis 복구~정상 응답 < 30초 + rebuild 마커(`system:rebuild_epoch:{eventId}`) 재설정 확인.
장애 중 진행되던 좌석 홀드가 유실된 것은 **알려진 한계로 허용**(decisions.md 1번).

### 시나리오 A-2 — Kafka 브로커 다운

| 순서 | 동작 |
|---|---|
| 1 | `run-gatling.ps1 ... -Users 150 -RampSeconds 40 -TailSeconds 120` (총 390 VU — 위 "카오스 부하 규모" 표) |
| 1-1 | **동시에** `powershell -File scripts/fail-payments.ps1 -EventId <id> -DurationSec 300` — outbox→Kafka 경로를 태우려면 장애 중 `PAYMENT_FAILED` 전이가 실제로 일어나야 한다. Gatling 골든패스는 결제 요청까지만 하므로, 이 스크립트가 주기적으로 `PAYMENT_REQUESTED` 예약을 조회해 일부에 서명된 `Transaction.Failed` 웹훅(`/api/v1/payments/webhook`)을 쏜다 → 각 건이 `markPaymentFailed`에서 `outbox_events` INSERT |
| 2 | 좌석 홀드 트래픽이 흐르기 시작하면: `docker stop ticketrush-kafka` → 90초 → `docker start` (A-1과 같은 이유로 Pumba 대신 docker 직접) |
| 3 | 복구 후 커넥터가 떨어졌으면 `docker compose restart kafka-connect` → 필요 시 `register-outbox-connector.ps1` 재등록 |
| 4 | Grafana "밀린 메시지(Kafka Lag)" 패널로 복구 후 0 도달 시간 측정 |

**검증 SQL** — `outbox_events`에는 발행 상태 컬럼이 없다(INSERT 전용, Debezium이 binlog로 읽음). "전부 발행됨"은 **모든 outbox 행이 결국 좌석 반납까지 도달했는지**로 확인한다:
```sql
-- 이 이벤트에서 실패 처리된 예약 수 = outbox 행 수
SELECT
  (SELECT COUNT(*) FROM outbox_events o
     WHERE o.aggregate_type='reservation'
       AND o.aggregate_id IN (SELECT id FROM reservation WHERE event_id=<id>)) AS outbox_rows,
  SUM(status='PAYMENT_FAILED') AS stuck_failed,      -- 복구 후 0이어야 함 (컨슈머가 다 처리)
  SUM(status='SEAT_RELEASED')  AS released           -- outbox_rows 와 일치해야 함
FROM reservation WHERE event_id=<id>;
```
```
# Consumer lag (복구 후 0 확인)
docker exec ticketrush-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group ticketrush-reservation
```

**합격 기준**: 장애 중 `payment-request` 5xx 0건(결제 요청 API가 Kafka와 무관하게 계속 동작) + 장애 중에도 `Transaction.Failed` 웹훅이 정상 200(= `outbox_events` 계속 쌓임, 유실 아님) + 복구 후 `stuck_failed = 0` & `released = outbox_rows` + Consumer lag 0 도달 < 60초.

---

## 3. 분산락 벤치마크 (부하 테스트)  ✅ 완료 (2026-09-03, test-results.md 3번 — Redisson 채택)

decisions.md 2번의 채택 기준을 실제 숫자에 적용해 **Redisson RLock vs DB 비관적 락** 중 하나를 고른다.
이 프로젝트에서 가장 강한 소재라 여기에 시간을 쓴다.

### 3-1. 선행 작업 (코드, 벤치마크 전 필수) — ✅ 커밋 `8570d91`

`SeatRepository.findAllByIdInForUpdate`에 `lock.timeout` 힌트 3,000ms(`group-hold.lock-wait-millis`와
동일) → `SELECT ... FOR UPDATE WAIT 3`. `DbPessimisticLockGroupHoldLockStrategy`가 락 획득 실패 예외를
`GROUP_HOLD_LOCK_TIMEOUT`(409)으로 매핑. 이전엔 timeout 미지정이라 MySQL 기본 `innodb_lock_wait_timeout`
(50초) 블로킹 후 일반 500으로 새서 Redisson과 실패 방식이 달랐다(멘토 피드백).

### 3-2. 실행 — 실제로 한 것

```powershell
# 좌석 4개(경합 최대화), 대기열을 즉시 통과시켜(QUEUE_ADMIT_COUNT=1000) 좌석 홀드 시점에 경합 집중
powershell -File scripts/seed-load-test.ps1 -Rows 1 -SeatsPerRow 4 -BuyerCount 300

# (A) Redisson — GROUP_HOLD_LOCK_STRATEGY=redis QUEUE_ADMIT_COUNT=1000 QUEUE_ADMIT_INTERVAL=500 로 기동
powershell -File scripts/run-gatling.ps1 -EventId <idA> -SectionId <sid> -Users 300 -GroupHoldRatio 1.0 -InjectMode atonce

# (B) DB 비관적 락 — GROUP_HOLD_LOCK_STRATEGY=db (나머지 동일) 로 재기동, 새 이벤트로 재시드
powershell -File scripts/run-gatling.ps1 -EventId <idB> -SectionId <sid> -Users 300 -GroupHoldRatio 1.0 -InjectMode atonce
```

**주의(실행에서 배운 것)**: 처음엔 40석 + 대기열 정상 투입(`QUEUE_ADMIT_COUNT` 기본 100)으로 돌렸는데,
대기열 입장(초당 100명)과 폴링 1초 간격 때문에 좌석 홀드 시점이 ~10초에 분산돼 **같은 좌석 쌍을 동시에
노리는 요청이 거의 없어 락 경합이 안 생겼다**(P99 27ms, 락 타임아웃 0). 좌석 수를 4개로 줄이고
`QUEUE_ADMIT_COUNT`를 크게 잡아 대기열을 병목에서 빼야 홀드 시점 경합이 제대로 재현된다.

`-InjectMode atonce`로 300명 전원 완전 동시 투입 — 실제 오픈 순간의 "동시 클릭" 재현(2026-09-01 확정).
`-BuyerCount`는 `-Users`(300) 이상이어야 계정이 재사용되지 않는다. 로그인 BCrypt는 `buyers.csv`
사전 로그인으로 측정 구간에서 이미 빠져 있다.

### 3-3. 비교 지표와 판정

| 지표 | 출처 | 판정 규칙 (decisions.md 2번) |
|---|---|---|
| 오버셀 0 | 2번 SQL | **전제** — 하나라도 오버셀 나면 그 방식 탈락 |
| 처리량 (req/s) | Gatling 리포트 | 아래와 함께 봄 |
| **그룹 홀드 P99** | Grafana, `uri` 변수로 `/api/v1/events/*/seats/holds` 필터 | 처리량과 **동등 지표** |
| 락 실패율 + 형태 | Grafana "상태코드별" 패널 | 409가 즉시인지(대기 없음) 대기 후인지 응답시간 분포로 확인 |

- 처리량·P99 둘 다 20% 이내 차이 → **DB 락 채택**(추가 인프라 불필요, 운영 단순)
- 처리량과 P99가 다른 방향을 가리키면 → **P99 우선** (폭주 중 상위 1%가 몇 초씩 기다리는 쪽은 탈락)
- 락 획득 실패/타임아웃 에러율이 한쪽에서 5%p 이상 높으면 감점

**실제 판정(2026-09-03)**: 처리량·Global P99 둘 다 20% 이내(동등) → 규칙상 DB 락 방향이지만, tie-breaker의
근거("Redisson = 추가 인프라")가 **우리는 Redis를 이미 코어로 써서 성립하지 않음**. 유일한 실질 차이인
HikariCP pending(Redisson 0 / DB 락 147)을 "먼저 포화된 자원"으로 반영해 **Redisson 채택**. 상세는
test-results.md 3번. Grafana 스크린샷은 두 실행이 ~15초로 짧아 표로 대체.

**산출물**(완료): `decisions.md` 2번, `test-results.md` 3번, `aws-spec.md` B-2(RDS `db.m6i.large` 확정),
`portfolio.md` 소재 7.

---

## 4. 한계 테스트 (동시 몇 명까지 버티나)

> **1차(localhost) + 4-4(Gatling-in-container) 실행 완료 2026-09-04** — `test-results.md` 4번/
> 4-4번. 1차는 Docker Desktop 포트 프록시(~800~1,000 연결에서 붕괴)에 막혀 진짜 숫자를 못 냈고,
> 4-4가 프록시를 우회해 **이 축소 스펙(2 vCPU)에서 450~500명 사이에 절벽이 있음**을 클린 재측정
> (매 실행 전 DB/Redis 리셋)으로 확정했다. 다음 단계(원인 진단 + 튜닝 + 재측정)는 progress.md.

### 4-1. 방식 (1차에서 확정된 것)

- **계단식(`incrementConcurrentUsers`)은 이 시스템에 안 맞는다.** 계정 1개당 이벤트 1건만 진행
  가능(`ACTIVE_RESERVATION_EXISTS`) → 계정 풀이 ~50초에 소진되어 뒷 단계가 빈 409가 된다.
- **버스트 모델을 쓴다**: `GoldenPathSimulation -InjectMode atonce -Users N` — N명이 오픈 순간처럼
  완전 동시에 몰리고 각자 딱 1회 여정. 대기열이 100명/초로 메터링하는 걸 감당하나 + 대기자 전원의
  1초 폴링 부하를 견디나를 본다. N을 키워가며 종료 조건(4-2)에 걸리는 지점을 찾는다.
- **계정·좌석은 N보다 넉넉히**: 좌석 ≥ N×1.5, 계정 ≥ N. (`CapacitySimulation.java`는 계단식용으로
  남겨둠 — AWS 재측정에서 프록시 없이 다시 쓸 수 있음.)

### 4-2. 종료 조건 (셋 중 하나라도 → 그 직전 N이 한계치)

| 조건 | 임계 |
|---|---|
| P95 (홀드→결제, 서버측 Prometheus) | > 5,000ms |
| 에러율 (5xx + 락 타임아웃, **경합 409·404 제외**) | > 5% |
| 오버셀 | 1건이라도 |

### 4-3. 1차 결과 요약 (상세는 test-results.md 4번)

- 계단식(버림) + 버스트 1,500명 × 3회(설정·경로 바꿔가며): **오버셀 0 / 5xx 0** (전부).
- **"Connection refused"의 정체 = Docker Desktop 유저랜드 포트 프록시** (Windows↔WSL2). `:8080`(앱)이든
  `:8081`(nginx)이든 ~500~760/1500 동일하게 거부, `accept-count` 100→2000도 무효. **AWS엔 없다.**
- 병목축: **app CPU (2 vCPU cap, 매 버스트 197% 고정) → HikariCP/mysql**. pending은 풀 10·20 무관 ~160~190.
- → `aws-spec.md` C: 병목축 = CPU 우선. `m6i.xlarge`(4 vCPU) + `db.m6i.large`에서 완화 예상.

### 4-4. Gatling-in-container 재측정 (실행 완료, 상세는 test-results.md 4-4)

Docker 포트 프록시를 우회하려면 Gatling도 컨테이너 네트워크 안에서 돌려 `nginx:80`을 직접 친다.
`docker-compose.capacity.yml`에 `gatling` 서비스로 구현됨(`image: eclipse-temurin:21-jdk`, 소스+
`gatling_gradle_cache` 볼륨 마운트, `profiles: ["gatling"]`로 평소엔 안 뜨게 함, `-DbaseUrl`은
env `GATLING_BASE_URL`로 주입).

```powershell
docker compose -f docker-compose.yml -f docker-compose.rehearsal.yml -f docker-compose.capacity.yml `
  --profile gatling run --rm gatling   # GATLING_EVENT_ID/GATLING_SECTION_ID/GATLING_USERS env로 파라미터
```

**필수: 매 실행 전 DB/Redis 리셋.** 리셋 없이 이벤트만 새로 만들어 연속 실행하면 N과 지연시간이
반비례하는 이상값이 나온다(1차 시도에서 실제로 겪음, test-results.md 4-4 참고 — 원인은 데이터
누적이 아니라 리셋 누락 자체로 결론). 리셋 절차:
1. `reservation`/`reservation_seat`/`outbox_events` TRUNCATE
2. 기존 테스트 `event`/`section`/`seat` DELETE
3. Redis `FLUSHALL` (refresh_token도 지워지지만 stateless AccessToken 검증엔 무관 — 계정 재로그인 불요)
4. 새 이벤트 생성(구역은 N×1.5 이상 여유 있게) 후 N을 늘려가며 재실행

주의: Gatling 컨테이너도 리허설 예산 안에서 CPU/메모리를 나눠 먹으므로 `cpus`/`mem_limit`을 앱
예산과 분리해 잡는다(현재 1.5 vCPU / 1024m).

### 4-5. 다음 세션 빠른 재기동 체크리스트 (설정에 시간 날리지 않도록)

**이미 되어 있는 것** (건드리지 말 것):
- `~/.wslconfig` (`memory=8GB`, `processors=8`, `[experimental] autoMemoryReclaim=dropcache`) — 있으면
  Docker VM이 캐시를 안 물고 있어 호스트가 안 굶는다. 없으면 부하 테스트 중 스왑.
- `docker-compose.rehearsal.yml` — 이 PC(16 GiB)에 맞춘 축소 예산 + `TZ=Asia/Seoul` +
  `JWT_ACCESS_EXPIRATION=14400000`(4h) + HikariCP/Tomcat 튜닝 노브(`HIKARI_POOL`/`TOMCAT_ACCEPT` env).
- `docker-compose.capacity.yml` — nginx를 rate-limit 없는 `nginx.capacity.conf`로 교체(한계 테스트 전용).
- 스크립트: `seed-buyers-parallel.mjs`(신규 계정+로그인, 병렬), `login-buyers.mjs`(기존 계정 토큰만 갱신,
  `buyer-emails.txt` 읽음 — PowerShell `seed-load-test.ps1`의 순차 시드는 이 PC에서 3000개에 30분 걸려 못 씀),
  `run-capacity.ps1`(계단식), `run-gatling.ps1`(버스트: `-InjectMode atonce`).

**재기동 순서** (mysql/redis 볼륨을 지우지 않았다면 이벤트·계정 데이터가 남아 있음):
```powershell
# 1. 스택 (앱 이미지 빌드 캐시 있으면 ~1분). HikariCP는 기본 10 유지 — env 미지정.
#    풀 크기 실험을 다시 할 때만 HIKARI_POOL=20 을 앞에 붙인다.
docker compose -f docker-compose.yml -f docker-compose.rehearsal.yml -f docker-compose.capacity.yml up -d --build
# 2. 앱 헬스 대기: curl http://localhost:8080/actuator/health  → 200
# 3. 포트 충돌 시: 다른 프로젝트 컨테이너(classq `app`, `mysql-container`)가 8080/3306을 쥐고 있을 수 있음
#    → docker update --restart=no <name> ; docker stop <name>   (이번 세션에 이미 정지·restart 해제해둠)
# 4. 이벤트 새로 필요하면 node 인라인 스크립트로 생성 (openAt는 KST로: Date.now()+30s+9h)
#    또는 seed-load-test.ps1 -BuyerCount 0 회피하고 이벤트만 만드는 방법 사용
# 5. 토큰 갱신 (계정이 이미 있으면): node scripts/login-buyers.mjs 3200   (~100초)
#    계정이 없으면:                  node scripts/seed-buyers-parallel.mjs 3000
# 6. 버스트: powershell -File scripts/run-gatling.ps1 -EventId <id> -SectionId <sid> -Users 1500 -InjectMode atonce -BaseUrl http://localhost:8081
# 7. 모니터: scratchpad burstmon.sh 패턴 (host avail / docker stats / hikari / prometheus P95)
```

**함정 모음** (이번 세션에 겪은 것):
- 앱 컨테이너 기본 TZ가 UTC → seed의 openAt(KST)와 9시간 어긋나 이벤트가 안 열림 → `TZ=Asia/Seoul` 필수(반영됨).
- JWT 30분이라 세션 대화 중 토큰 만료 → false 401 폭탄 → `JWT_ACCESS_EXPIRATION=4h`(반영됨).
- nginx.rehearsal.conf는 `upstream app:8080`을 기동 시 1회 해석 → app보다 먼저 뜨면 죽음 → `depends_on: [app]`(반영됨).
- Gatling 플러그인 기본 logback이 KO마다 요청 전문을 DEBUG 덤프 → 출력 파일 MB급 → `src/gatling/resources/logback.xml`로 http 클라이언트 로그 OFF(반영됨).
- PowerShell에서 `-File scripts\x.ps1`의 백슬래시가 Git Bash에서 먹힘 → 절대경로 + 슬래시로.

### 4-6. 관찰 포인트

- HikariCP `pending` / Tomcat `busy` / app CPU% / 호스트 여유 메모리(스왑 감시, < 500 MB면 중단) / Kafka lag.
- Grafana 4패널(`ticketrush-load`) — 단, 부하 중 `/actuator/prometheus` 스크레이프가 타임아웃날 수 있어
  (앱 CPU 포화) 시계열에 구멍이 생긴다. 수치는 Prometheus API 직접 쿼리로 보완.

---

## 참조

- 정상 동작 기준 상세: `decisions.md` 1번(Redis), 8번(Kafka)
- 분산락 채택 기준: `decisions.md` 2번
- 도구 선택 이유: `decisions.md` 8번(Pumba), 2번(Gatling)
- AWS 스펙: `aws-spec.md`
