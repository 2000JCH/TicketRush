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

**권장(잠정): `db.m6i.large`, 벤치마크에서 DB 락 채택 시 `db.r6i.large`로 상향**
> InnoDB 버퍼 풀이 핵심(classq와 동일). 락 방식이 정해지기 전까지는 보수적으로 m6i.large로 두고,
> 2번 벤치마크 결과에 맞춰 확정한다.

---

## C. 컴포넌트별 권장 스펙 요약 (잠정 — 부하테스트 후 확정)

| 컴포넌트 | 잠정 인스턴스 | vCPU | RAM | 확정 조건 |
|---|---|---|---|---|
| EC2 (Boot+Redis+Kafka+Connect+Nginx) | `m6i.xlarge` | 4 | 16 GiB | 로컬 부하테스트에서 병목축(CPU/RAM) 확인 |
| RDS (MySQL) | `db.m6i.large` | 2 | 8 GiB | 분산락 벤치마크(decisions.md 2번) 결과. DB 락이면 `db.r6i.large` |

> **"로컬 부하테스트에서 병목축 확인"은 무제한 로컬이 아니라 `docker-compose.rehearsal.yml`
> (2026-09-01 신규)로 진행한다** — 위 표의 예산(EC2 4vCPU/16GiB, RDS 2vCPU/8GiB)을 그대로 컨테이너
> 리소스 제한으로 걸어서, 4번 한계 테스트를 이 조건에서 돌리고 Grafana로 CPU/RAM 중 무엇이 먼저
> 포화되는지 관찰해 계열·크기를 확정한다(decisions.md 10번, `test-plan.md` 0-1·4번).

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
