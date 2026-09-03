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
2. `powershell -File scripts/seed-load-test.ps1 -Rows 20 -SeatsPerRow 20` → `eventId`, `sectionId` 확보 (400석)
3. Grafana(`localhost:3000`, admin/admin) → 대시보드 `TicketRush — 부하/카오스 관찰` 열어두기
4. 장애 주입 시점을 Grafana 타임라인에 annotation으로 남기기 (수동)

### 시나리오 A-1 — Redis 다운

| 순서 | 동작 |
|---|---|
| 1 | `powershell -File scripts/run-gatling.ps1 -EventId <id> -SectionId <sid> -Users 150 -RampSeconds 40 -TailSeconds 120` (`-TailSeconds`는 복구 후에도 트래픽이 이어져 Grafana 그래프에 "정상 복귀"가 담기게 하는 chaos 모드 전용 꼬리 부하 — 스크린샷 실행에서만) |
| 2 | 부하 시작 ~20초 후: `powershell -File scripts/chaos-redis.ps1 -DurationSec 60` (Redis SIGTERM → 60초 → 자동 재시작). stop/restart UTC 시각이 콘솔 + `scripts/chaos-timeline.log`에 찍힌다 |
| 3 | Grafana 관찰: 정지 중 에러율 급등 → 재시작 후 rebuild → 정상 복귀까지 시간 측정. `chaos-timeline.log`의 시각으로 대시보드에 annotation을 찍고 4패널(응답시간 P50/P95/P99 · 상태코드별 · Kafka lag · HikariCP) 캡처 |
| 4 | Gatling 종료 후 정합성 검증 (아래 SQL) |

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
| 1 | `run-gatling.ps1 ... -Users 150 -RampSeconds 40` (결제 실패도 섞이도록 그룹 홀드 비중 유지) |
| 2 | ~20초 후: `powershell -File scripts/chaos-kafka.ps1 -DurationSec 90` |
| 3 | 필요 시 복구 후 `docker compose restart kafka-connect` (커넥터가 떨어지면) |
| 4 | Grafana "Kafka Consumer Lag" 패널로 복구 후 lag 0 도달 시간 측정 |

**검증 SQL**:
```sql
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;   -- 복구 후 미발행 0
```
```
# Consumer lag (복구 후 0 확인)
docker exec ticketrush-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group ticketrush-reservation
```

**합격 기준**: 장애 중 결제 확정 실패 0건(Gatling에서 `payment-request` 5xx 없음) + 복구 후 `outbox_events` 전부 발행 + Consumer lag 0 도달 < 60초 + 결제 실패건 좌석이 결국 `SEAT_RELEASED`.

---

## 3. 분산락 벤치마크 (부하 테스트)

decisions.md 2번의 채택 기준을 실제 숫자에 적용해 **Redisson RLock vs DB 비관적 락** 중 하나를 고른다.
이 프로젝트에서 가장 강한 소재라 여기에 시간을 쓴다.

### 3-1. 선행 작업 (코드, 벤치마크 전 필수)

`DbPessimisticLockGroupHoldLockStrategy`에 lock timeout을 `group-hold.lock-wait-millis`(현재 3,000ms)와
같게 걸고, 타임아웃 예외를 `GROUP_HOLD_LOCK_TIMEOUT`으로 매핑한다. 현재는 timeout 미지정이라 MySQL
기본 `innodb_lock_wait_timeout`(50초) 블로킹 후 일반 500으로 새서, Redisson과 **실패 방식이 달라 공정
비교가 안 된다** (멘토 피드백 — "바로 실패냐 몇 초 기다렸다 실패냐"가 경험을 가른다).

### 3-2. 실행

좌석 풀을 작게 잡아 경합을 만든다.

```powershell
powershell -File scripts/seed-load-test.ps1 -Rows 5 -SeatsPerRow 8 -BuyerCount 300   # 40석 + BUYER 300명 사전 로그인

# (A) Redisson
#   application.properties group-hold.lock-strategy=redis (기본) 로 백엔드 기동
powershell -File scripts/run-gatling.ps1 -EventId <idA> -SectionId <sid> -Users 300 -GroupHoldRatio 1.0 -InjectMode atonce

# (B) DB 비관적 락  — 새 이벤트로 (좌석 상태 초기화)
#   GROUP_HOLD_LOCK_STRATEGY=db 로 백엔드 재기동
powershell -File scripts/run-gatling.ps1 -EventId <idB> -SectionId <sid> -Users 300 -GroupHoldRatio 1.0 -InjectMode atonce
```

`-InjectMode atonce`로 300명 전원을 완전 동시(같은 시각) 투입한다 — 실제 티켓 오픈 순간의 "동시 클릭"을
가장 가깝게 재현해야 락 경합 신호가 제대로 드러난다(2026-09-01 확정, ramp로 서서히 투입하면 경합이
약해져 두 락 방식 차이가 흐려짐). `-BuyerCount`는 이번 `-Users`(300)보다 크거나 같아야 계정이
재사용되지 않는다.

> **조정 여지**: 로그인 BCrypt가 락 경합 신호를 가리면, `seed-load-test.ps1`에 BUYER 계정 풀을 미리
> 만들어 Gatling이 재사용하도록 바꾼다(가입/로그인을 매 VU마다 하지 않음).

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

**산출물**: 채택 결정 → `decisions.md` 2번 갱신, `test-results.md` 표, `aws-spec.md` B-2(RDS 스펙 확정), `portfolio.md` "분산락 벤치마크" 소재.

---

## 4. 한계 테스트 (동시 몇 명까지 버티나)

> **0-1번 리허설 스택으로 진행한다.** 평소 로컬(무제한)로 돌리면 "이 PC가 몇 명 버티나"만 재는
> 것이라 AWS 배포 판단에 못 쓴다 — `docker compose -f docker-compose.yml -f docker-compose.rehearsal.yml
> up -d --build` 로 띄우고 baseUrl은 `http://localhost:8081`(Nginx 경유).

2번·3번은 고정 부하다. 이 테스트는 **부러질 때까지 밀어서 한계 동시 사용자 수를 찾는다** — "우리 시스템은
동시 N명까지 버틴다"는 문장이 포트폴리오의 핵심 수치가 된다.

### 4-1. 방법

Gatling 계단식 주입으로 동시 사용자를 단계적으로 올린다: 예) 100 → 200 → 400 → 800 …, 각 단계 60초 유지.
`GoldenPathSimulation`에 `-Dmode=capacity` 주입 프로파일을 추가하거나 별도 `CapacitySimulation`을 만든다
(`incrementConcurrentUsers(...).times(...).eachLevelLasting(...)`).

### 4-2. 종료 조건 (셋 중 하나라도 걸리면 그 직전 단계가 한계치)

| 조건 | 임계 |
|---|---|
| P95 (홀드→결제) | > 5,000ms (목표 2초의 2.5배 — "느리지만 아직 응답은 옴"의 끝) |
| 에러율 (5xx + 락 타임아웃) | > 5% |
| 오버셀 | 1건이라도 발생 (정합성이 깨지는 순간 = 진짜 한계) |

### 4-3. 관찰 포인트

- 먼저 무엇이 포화되는가: HikariCP `pending`(DB 커넥션 대기)? Tomcat 스레드? 로그인 BCrypt 큐? Kafka lag?
  → Grafana 4패널로 병목 지점을 특정 (classq는 스레드 풀 큐잉이 P95의 주원인이었음)
- 한계치와 그때의 병목 → `test-results.md` + `portfolio.md`. AWS 배포 후 같은 테스트로 한계치가 얼마나
  올라가는지도 비교.

---

## 참조

- 정상 동작 기준 상세: `decisions.md` 1번(Redis), 8번(Kafka)
- 분산락 채택 기준: `decisions.md` 2번
- 도구 선택 이유: `decisions.md` 8번(Pumba), 2번(Gatling)
- AWS 스펙: `aws-spec.md`
