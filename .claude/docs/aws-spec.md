# TicketRush — AWS 인스턴스 스펙 분석

> AWS 배포는 3주차 마지막 항목이다(decisions.md 13번). 이 문서는 그 배포에서 쓸 인스턴스 계열과
> 크기를 정리한다. 계열 선택(A·B)은 지금 근거를 확정하고, 실제 크기·성능 예측(C·D)은 **로컬 Gatling
> 부하테스트 실측값이 나온 뒤** 채우고, SLO 달성 여부(E)는 **실제 배포 후 AWS에서 Gatling을 다시
> 돌린 실측값**으로 채운다.
>
> classq와의 차이 두 가지:
> 1. classq는 앱 서버가 전용 인스턴스(EKS)라 "앱만" 놓고 사이징했지만, TicketRush는 **EC2 한 대에
>    Spring Boot + Redis + Kafka + Kafka Connect + Nginx를 전부 얹는다**(decisions.md 10번). 그래서
>    사이징 관점이 "앱 CPU"가 아니라 "공존 스택 전체의 RAM+CPU"다.
> 2. classq는 이 문서를 예측까지만 쓰고 배포·검증은 하지 않았다. **TicketRush는 실제로 배포하고
>    AWS에서 재측정해 D·E를 실측으로 채운다**(decisions.md 10번, 2026-08-28 확정).

---

## A. EC2 인스턴스 계열 특성

| 계열 | 성격 | vCPU:RAM | 장점 | 약점 |
|---|---|---|---|---|
| t (버스터블) | 크레딧 기반 | 1:2~1:4 | 평소 절전, 순간 burst | **지속 부하 시 크레딧 고갈 → vCPU가 기준 성능(20~40%)으로 제한** |
| c (컴퓨트 최적화) | 상시 full CPU | 1:2 | CPU 성능 항상 일정, 네트워크 넓음 | 같은 크기 대비 RAM이 적음 |
| m (범용) | 상시 full CPU | 1:4 | CPU·RAM 균형 | 특정 축에 치우친 워크로드엔 비효율 |
| r (메모리 최적화) | 상시 full CPU | 1:8 | RAM 대용량 | CPU가 상대적으로 적고 비쌈 |

### 참고 인스턴스 스펙 (AWS 공식, 6세대 x86 기준)

| 인스턴스 | 계열 | vCPU | RAM (GiB) | 네트워크 |
|---|---|---|---|---|
| t3.large | 버스터블 | 2 | 8 | Up to 5 Gbps (burst) |
| c6i.large | 컴퓨트 | 2 | 4 | Up to 12.5 Gbps |
| c6i.xlarge | 컴퓨트 | 4 | 8 | Up to 12.5 Gbps |
| m6i.large | 범용 | 2 | 8 | Up to 12.5 Gbps |
| m6i.xlarge | 범용 | 4 | 16 | Up to 12.5 Gbps |
| m6i.2xlarge | 범용 | 8 | 32 | Up to 12.5 Gbps |
| r6i.large | 메모리 | 2 | 16 | Up to 12.5 Gbps |

> 세대는 6세대(m6i/c6i/r6i)를 기준으로 한다 — classq가 "c5의 현행 후속"이라는 이유로 c6i를 택한 것과
> 같은 판단. Graviton(m7g 등)은 비용 이점이 있으나 로컬(x86)과의 벤치마크 parity를 위해 x86으로 둔다.

---

## B. TicketRush 구성 분석 및 권장 (계열 근거)

### B-1. EC2 (Spring Boot + Redis + Kafka + Kafka Connect + Nginx 한 대)

**한 박스에 올라가는 것:**

| 프로세스 | 성격 | 대략적 메모리 요구 |
|---|---|---|
| Spring Boot (API 서버) | JVM. 로그인 BCrypt는 CPU 집약, 나머지는 Redis/DB I/O 위주 | heap 1~2 GiB |
| Kafka (KRaft, cp-kafka) | JVM + **로그 세그먼트를 OS 페이지 캐시에 의존** | heap ~1 GiB + 페이지 캐시 |
| Kafka Connect (debezium/connect) | JVM. binlog 폴링 + SMT 변환 | heap ~1 GiB |
| Redis | 단일 스레드. 좌석 상태 Hash + 대기열 Sorted Set + 각종 TTL 키. AOF/RDB 꺼둠(decisions.md 1번) | < 1 GiB (7만석 이벤트 기준에도 수십 MB) |
| Nginx | 대기열 진입 API rate limit만 | 무시 가능 |

**부하 특성:**
- 오픈 폭주 = 대기열 진입 폴링 + 좌석 조회/홀드가 동시에 몰림 → Redis 명령 폭주 + Spring 스레드 풀 포화
- 로그인 BCrypt로 CPU 스파이크 (classq와 동일)
- 결제 요청/웹훅 구간에서만 MySQL(RDS) INSERT/UPDATE + outbox → Debezium → Kafka 파이프라인 동작
- 부하테스트·카오스 테스트 = 지속 부하 → **t계열 크레딧 고갈로 제외**(classq와 동일한 이유)

**계열 판단:**

| 후보 | 판단 |
|---|---|
| c6i.xlarge (4 vCPU / **8 GiB**) | CPU는 충분하나, JVM 3개(합 3~4 GiB) + Redis + Kafka 페이지 캐시 + OS를 8 GiB에 욱여넣으면 페이지 캐시가 밀려 Kafka I/O가 디스크로 떨어지고 GC 압박이 커진다 | △ RAM 빠듯 |
| **m6i.xlarge (4 vCPU / 16 GiB)** | vCPU 4개로 BCrypt + 스케줄러 2개 + Kafka/Connect 스레드 여유. 16 GiB로 JVM 3개 + Redis + 페이지 캐시 동시 수용 | ✅ **권장(잠정)** |
| m6i.2xlarge (8 vCPU / 32 GiB) | 3주차 부하테스트 규모에서는 과잉. 실측 후 병목이 CPU로 확인되면 scale-up 후보 | — 확장용 |
| r6i.large (2 vCPU / 16 GiB) | RAM은 맞지만 vCPU 2개로는 BCrypt 폭주 + 3개 JVM의 CPU 경쟁을 못 버틴다 | ❌ CPU 부족 |

**권장(잠정): `m6i.xlarge` (4 vCPU / 16 GiB)**
> "앱 전용"이 아니라 "스택 전체를 한 대에" 올리는 구성이라, classq의 c계열 논리(앱 CPU 최우선)를
> 그대로 쓸 수 없다. RAM이 CPU만큼 병목이므로 vCPU:RAM 1:4의 m계열이 맞고, 크기는 부하테스트에서
> 병목이 CPU인지 RAM인지 확인 후 확정한다.

### B-2. RDS (MySQL) — 분산락 벤치마크 결과에 종속

**부하 특성:**
- 정상 흐름에서 RDS에 닿는 건 결제 요청(`reservation` + `reservation_seat` 1~2행 INSERT) + 웹훅(`reservation` UPDATE + `outbox_events` INSERT) + Debezium binlog 읽기뿐 — 좌석 홀드 자체는 Redis 선에서 걸러진다(decisions.md 7번).
- **단, 분산락으로 DB 비관적 락을 채택하면** 그룹 홀드 요청마다 `SELECT ... FOR UPDATE`가 RDS로 몰려 부하 성격이 완전히 달라진다.

| 시나리오 | RDS 부하 | 권장(잠정) |
|---|---|---|
| Redisson RLock 채택 | INSERT/UPDATE + binlog 읽기만 | `db.m6i.large` (2 vCPU / 8 GiB) |
| DB 비관적 락 채택 | 위 + 그룹 홀드 `SELECT FOR UPDATE` 경합 | `db.r6i.large` (2 vCPU / 16 GiB, InnoDB 버퍼 풀 ~11 GiB) |

**확정: `db.m6i.large` (2 vCPU / 8 GiB).** 2026-09-03 분산락 벤치마크에서 **Redisson RLock 채택**
(decisions.md 2번, test-results.md 3번) — 그룹 홀드는 Redis 락으로 처리되고 RDS로는 `SELECT FOR UPDATE`가
가지 않는다. 따라서 RDS 부하는 "결제 요청/웹훅 INSERT·UPDATE + binlog 읽기"뿐이라 `db.m6i.large`로 충분.
DB 락을 채택했다면 그룹 홀드 `SELECT FOR UPDATE` 경합 + 커넥션 풀 압박(벤치마크에서 HikariCP pending 147)
때문에 `db.r6i.large`(16 GiB)로 올려야 했다. InnoDB 버퍼 풀 여유는 D(예측)·E(실측)에서 재확인.

---

## C. 컴포넌트별 권장 스펙 요약

| 컴포넌트 | 인스턴스 | vCPU | RAM | 상태 |
|---|---|---|---|---|
| EC2 (Boot+Redis+Kafka+Connect+Nginx) | `m6i.xlarge` | 4 | 16 GiB | **방향 확정** — 병목축 = CPU (한계 테스트 1차). 크기 미세조정은 AWS 재측정(E) |
| RDS (MySQL) | `db.m6i.large` | 2 | 8 GiB | ✅ 확정 — 분산락 벤치마크(2026-09-03) Redisson 채택 |

**병목축 = CPU (한계 테스트 1차, 2026-09-04, `test-results.md` 4번)**:
- 축소 리허설(app 2 vCPU / 2400m)에서 1,500명 버스트 시 **app CPU가 매번 2코어 cap(197%)에 붙었다.**
  RAM은 여유 있었음(app heap ~1 GiB / limit 2400m). HikariCP pending은 풀 크기(10·20) 무관하게 ~160~190
  — 그 뒤의 mysql 1.5 vCPU가 실질 상한. → **CPU가 1순위 병목, DB(CPU/커넥션)가 2순위.**
- 따라서 vCPU:RAM 1:4 m계열 + vCPU를 넉넉히(`m6i.xlarge` 4 vCPU) 방향이 맞다. RAM 병목이 아니므로
  `r` 계열은 불필요. 부하가 CPU에 더 몰리면 `m6i.2xlarge`(8 vCPU)가 scale-up 후보.
- **로컬 한계 숫자(동시 N명)는 1차(localhost)에선 못 냈다** — Docker Desktop 포트 프록시(Windows↔
  WSL2 유저랜드 프록시)가 ~800~1,000 동시 연결에서 먼저 RST를 뱉는다. `server.tomcat.accept-count`를
  100→2000으로 올려도 무효(연결이 컨테이너에 닿기 전에 죽음). **AWS엔 이 계층이 없다** — 앱이 리눅스
  커널에 포트 직접 바인딩 + ALB/nginx 앞단.
- **Gatling-in-container 재측정(2026-09-04, `test-results.md` 4-4)으로 이 축소 스펙 기준 진짜 숫자를
  냈다** — Gatling도 컨테이너 네트워크 안에서 `nginx:80`을 직접 쳐 위 프록시를 우회. 매 실행 전
  DB/Redis를 리셋한 클린 재측정 결과 **100~450명은 완만(서버 P95 6ms→132ms)하다가 450→500 사이에서
  절벽**(132ms→8,990ms)이 나타났다 — 이 스펙(2 vCPU)의 실제 한계는 **~460~480명** 부근. 다만
  HikariCP·Tomcat·GC 중 무엇이 이 절벽의 실질 원인인지는 아직 미확정(다음 단계에서 진단). AWS(4 vCPU,
  2배)에서 이 한계가 어디까지 올라가는지가 D(예측)·E(AWS 실측)의 핵심 질문이다.

### C-1. 배포 시 반영할 튜닝 (한계 테스트에서 나온 것)

| 항목 | 값 | 근거 / 상태 |
|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | **10 (재검증 필요)** | 1차(host, 1,500명대)에선 10→20이 pending을 못 줄여 "레버가 아니다"로 결론냈으나, 4-4 재측정으로 절벽이 450~500 사이에 있는 걸 알게 된 지금은 이 결론이 **절벽을 이미 넘은 지점만 본 것**이라 재검증 필요 — 다음 단계(원인 진단)에서 절벽 근처(450~500)에서 HikariCP 상태를 직접 관찰해 확인 |
| `server.tomcat.accept-count` | 100 → **미정 (AWS에서 검증)** | 버스트 시 기본 100은 작아 보이나, 로컬에선 Docker 프록시에 가려 100→2000 효과를 검증 못 했다. AWS(프록시 없음)에서 실제로 필요한지 + 적정값 확인 |
| `TZ` / `-Duser.timezone` | **`Asia/Seoul`** ✅ | 앱이 `openAt` 등을 zone 없는 `LocalDateTime`으로 다룸. EC2 기본 UTC면 KST 기준 데이터와 9h 어긋남 — 이건 확정 반영 |

→ 리허설에선 `docker-compose.rehearsal.yml`의 env(`HIKARI_POOL`/`TOMCAT_ACCEPT`/`TZ`)로 실험 전환.
`TZ` 외에는 `application.properties` 기본값을 건드리지 않는다 — AWS 재측정으로 확정된 뒤에만 승격.

> **로컬 리허설은 `docker-compose.rehearsal.yml` + `docker-compose.capacity.yml`로 진행한다.** 원안은
> EC2 4vCPU/16GiB + RDS 2vCPU/8GiB를 그대로 흉내냈으나 이 개발 PC가 RAM 16 GiB뿐이라 합 24 GiB가
> 안 들어가 축소했다(합 ~7 GiB, `test-results.md` 4-0). "노트북 성능이 아니라 EC2 성능"이라는 원래
> 취지는 병목축 판별(CPU vs RAM)까지는 유효하고, 절대 수치는 AWS 재측정에서 확정한다.

---

## D. AWS 스펙별 성능 예측 → 실측

> **1단계(로컬 부하테스트 후)**: 로컬 Gatling 실측값(그룹 홀드 P99, 처리량, 에러율)을 기준으로,
> 로컬(한 PC에 전부) vs AWS(EC2 1대 + RDS 분리) 환경 차이를 반영해 예측표를 만든다.
> **2단계(AWS 배포 후)**: 같은 Gatling 시나리오를 배포 환경에서 다시 돌려 예측 대비 실측을 채운다.

*(1단계: 로컬 부하테스트 결과 대기 중 / 2단계: AWS 배포 후)*

---

## E. SLO (Service Level Objective)

> 로컬 목표 기준선은 `test-plan.md` 1번에 이미 확정돼 있다. 여기 E는 그 목표를 **AWS 환경에서
> 달성하는 것을 SLO로 삼고**, 로컬 실측(`test-results.md`) → D 예측표 → 배포 후 재측정으로 검증한다.
> **1단계(로컬 부하테스트 후)**: 로컬 실측을 D 예측표로 옮기고, SLO 수치를 AWS 기준으로 조정.
> **2단계(AWS 배포 후)**: 배포 환경 Gatling 재측정(`test-results.md` 5번)으로 SLO 달성 여부 확인.

*(1단계: 로컬 부하테스트 결과 대기 중 / 2단계: AWS 배포 후)*
