# 테스트 스크린샷

카오스/부하 테스트의 Grafana 캡처를 시나리오별로 모은다. `test-results.md`의 각 결과가
여기 이미지를 근거로 참조한다(`test-plan.md` 매핑).

| 폴더 | 시나리오 | test-plan.md |
|---|---|---|
| `a1-redis-down/` | 카오스 A-1 — Redis 다운 | 2번 |
| `a2-kafka-down/` | 카오스 A-2 — Kafka 브로커 다운 | 2번 |
| `distributed-lock-benchmark/` | 분산락 벤치마크 (Redisson vs DB 락) | 3번 |
| `capacity-limit/` | 한계 테스트 (동시 몇 명까지) | 4번 |
| `aws-remeasure/` | AWS 배포 후 재측정 | 5번 |

## 파일명

각 폴더 안에서 내용을 알아볼 수 있는 이름이면 된다(한글 가능). 대략:
- `4패널_전체사진.png` — 4패널 전체 (대표 이미지)
- `API응답시간.png` — 응답시간 P50/P95/P99 패널 확대
- `요청_에러율.png` — 상태코드별/에러율 패널 확대
- `HikariCP_커넥션풀.png` — HikariCP 패널 확대

같은 시나리오를 다시 돌려 새로 찍으면 예전 것은 지우고 교체한다(단일 출처).

## 캡처할 것 (공통)

1. 4패널 전체 1장 (장애 구간이 화면 중앙에 오도록 시간범위 조정)
2. 그 시나리오의 핵심 패널 확대 1~2장
   - A-1/A-2: 응답시간 P99 + 에러율
   - A-2: Kafka Consumer Lag(밀린 메시지)도
   - 분산락: 응답시간 P99 + HikariCP(대기)
   - 한계 테스트: 단계별 응답시간 + HikariCP + 종료 조건에 걸린 순간
3. 장애 주입 시각은 `scripts/chaos-timeline.log`(UTC)로 Grafana annotation
