# TicketRush — 진행 상황

## 현재 상태 (요약)

`db-schema.md` + `redis-design.md` 본격 작성 완료. `standing:remaining` 키를 좌석 상태 Hash에 통합, 스탠딩 예약에도 `idempotency_key` 기반 유니크 제약 적용, `rebuild:in_progress` 플래그는 이벤트 단위로 확정하고 반영함. `SEAT_HELD`(좌석 찜 상태)는 Redis에만 두고 DB `reservation.status` ENUM에는 넣지 않기로 확정(결제 요청 시점부터 DB 기록 시작) — 사용자 확인 완료.

문서 5개(decisions/architecture/db-schema/redis-design/progress) 전체 일관성 점검 완료. `idempotency_key` NULL 허용 버그, architecture.md의 옛 Redis 키 이름 잔존, `hold` 키를 `PERSIST`하면 PG 웹훅 타임아웃을 아무도 감지 못하는 설계 구멍 등을 찾아 수정함(수정 내역은 각 문서 및 아래 항목 참고).

`api-design.md` 작성 완료 — 인증/이벤트/대기열/좌석/결제·예약/관리자 6개 도메인. 작성 후 전체 문서 재점검해서 redis-design.md의 입장 토큰 미결 표시, api-design.md의 404 설명 모순을 정리함. ORGANIZER 가입은 ADMIN 승인 필요로 확정하고 관련 문서(db-schema/decisions/api-design) 전부 반영 완료.

**사재기 방지 정책 확정 및 스키마 재작업 완료**: 계정당 이벤트별 동시 진행 예약 1건 제한(Redis `active_reservation:{eventId}:{accountId}`) + 한 예약(그룹 홀드 포함)당 최대 2매 + 이벤트당 누적 확정 매수 최대 2매, 3중으로 사재기를 막기로 확정. 이 과정에서 그룹 홀드(분산락 벤치마크 대상)를 없앨지 논의했으나 **분산락 벤치마크(decisions.md 2번)를 유지하는 쪽으로 확정** — "좌석 개수 제한"이 아니라 "동시 진행 시도 개수 제한"으로 사재기 방지를 재해석해서 그룹 홀드 기능과 양립시킴. `reservation` 테이블은 "예약 1건 = 좌석 1개"에서 "예약 1건 = 결제 시도 1건(좌석 1~2개)"으로 재구성하고, 좌석 정보는 자식 테이블 `reservation_seat`로 분리(db-schema.md 5·6번). decisions.md/architecture.md/redis-design.md/api-design.md 전부 반영 완료.

**ADMIN 모니터링 기능 확정**: `GET /api/v1/admin/events/{eventId}/stats`로 판매 현황(확정/진행중 매수)과 좌석 점유율을 조회. 새 데이터를 만들지 않고 기존 Redis(`seat_status:{eventId}`)/DB(`idx_event_status`)를 그대로 읽기만 하며, 화면 자동 갱신(SSE/웹소켓)은 만들지 않고 폴링으로 처리(api-design.md 6번). **모든 설계 문서 1차 완료. 다음은 구현 착수.**

## 문서화 진행 상황 (`.claude/docs/`)

| 문서 | 상태 | 비고 |
|---|---|---|
| `decisions.md` | 거의 완료 | 13개 섹션(12번: 이벤트/좌석 도메인 모델 추가). 11번에 미확정 사항이 정리되어 있음 |
| `architecture.md` | 작성 완료 | classq 스타일(표/텍스트 위주, mermaid 미사용)로 통일됨 |
| `db-schema.md` | 작성 완료 | account/event/section/seat/reservation/reservation_seat/outbox_events 7개 테이블 |
| `redis-design.md` | 작성 완료 | classq 스타일(키별 블록 + 전체 요약 표)로 통일 |
| `api-design.md` | 작성 완료 | classq 스타일(도메인별 표+JSON 예시+에러코드 표)로 통일 |
| `progress.md` | 이 문서 | |
| `../portfolio.md` | 작성 시작 | 이력서/포트폴리오용 "문제와 해결" 소재 모음(`.claude/portfolio.md`). 2026-08-16 생성, 현재 3건 수록 + 앞으로 나올 소재 목록 |

## 구현 진행 상황

- **2026-08-13**: 로컬 개발/테스트 인프라 구축 완료. 프로젝트 루트에 `docker-compose.yml` 작성 — `mysql`(8.0, Debezium용 `--log-bin`/`--binlog-format=ROW`/`--binlog-row-image=FULL` 옵션 포함) / `redis`(7.2, AOF) / `kafka`(confluentinc/cp-kafka 7.7.0, **KRaft 모드**로 Zookeeper 없이 단일 컨테이너) / `kafka-connect`(`debezium/connect:2.6`, Debezium MySQL 커넥터 내장) 4개 서비스. classq(`all/classq/docker-compose.yml`)의 검증된 구성을 참고해 작성함. `application.properties`에 `spring.datasource.*`/`spring.data.redis.*`/`spring.kafka.bootstrap-servers`를 `${ENV_VAR:localhost 기본값}` 패턴(classq 스타일)으로 추가 — 로컬 `gradlew.bat bootRun` 기준 기본값으로 바로 붙고, 추후 앱 컨테이너화 시 환경변수 주입만으로 재사용 가능. `docker compose up -d` 실기동 + Spring Boot 앱 기동까지 MySQL 연결(HikariCP)·Redis PING·Kafka 토픽 조회·Kafka Connect의 Debezium 플러그인 로드 전부 검증 완료. Kafka Connect에 실제 Debezium 커넥터(outbox_events 대상)를 등록하는 작업과 Nginx 설정, 앱 `Dockerfile`, 배포용 Docker Compose는 범위에서 제외 — 각각 `outbox_events` 엔티티가 생기는 시점(3주차 Kafka exactly-once)과 3주차 일정(decisions.md 13번) 그대로 유지.
- **2026-08-13**: 저장소 구조를 classq와 동일하게 `ticketrush-backend/`(Gradle 프로젝트 전체) / `ticketrush-frontend/`(빈 폴더, 기술스택 미정) 로 분리 — 사용자 확인 완료(프론트엔드는 지금 스캐폴딩하지 않고 폴더만 이동). `build.gradle`/`settings.gradle`/`gradlew`/`gradlew.bat`/`gradle/`/`src/`를 `git mv`로 이동해 git 히스토리 보존, `.gradle`/`build/`/`HELP.md`(gitignore 대상)도 함께 이동. 이동 후 `ticketrush-backend/`에서 `gradlew.bat compileJava` 빌드 성공까지 검증 완료. `CLAUDE.md`의 명령어/경로를 새 구조에 맞게 갱신함(모든 gradle 명령은 `ticketrush-backend/`에서 실행).

- **2026-08-14**: **1주차 첫 항목(인증/인가 기반 구축) 착수 — 회원가입/로그인/JWT까지 완료**. 패키지 구조를 도메인별(`domain/{도메인}/{controller,dto,entity,repository,service}` + `global/{config,entity,exception,jwt}`)로 확정(사용자 확인 완료). 만든 것: `Account` 엔티티(+`Role`/`AccountStatus`)와 `AccountRepository`, `POST /api/v1/auth/signup`·`POST /api/v1/auth/login`, `JwtProvider`(Access Token 발급/검증)와 `JwtAuthenticationFilter`, `SecurityConfig`(BCrypt + 무상태 세션 + 인증/인가 실패도 공통 에러 형식으로 응답), 공통 예외 처리(`ErrorCode`/`BusinessException`/`ErrorResponse`/`GlobalExceptionHandler`), `BaseTimeEntity`+JPA Auditing, `AdminAccountInitializer`(ADMIN 계정 기동 시 자동 생성). 결정된 사항: **비밀값은 `.env`에 두고 `spring.config.import`로 읽는다**, **ADMIN 계정은 앱 기동 시 자동 생성한다**, **계정 도메인 패키지명은 `account`**(API 경로는 `/auth/*` 유지) — 모두 사용자 확인 완료. 실기동 후 curl로 15개 경로(가입/로그인/PENDING 차단/중복 이메일/잘못된 비밀번호/입력 검증/ADMIN 가입 차단/토큰 없음·잘못된 토큰·정상 토큰 등)를 검증했고, 검증 중 발견한 버그 2건(인증 필요 응답에 로그인 실패 메시지가 나가던 것, 없는 경로가 404 대신 500으로 처리되던 것)을 수정함. `gradlew.bat test` 통과. api-design.md에 회원가입 응답 형식과 `EMAIL_ALREADY_EXISTS` 에러 코드를 추가 반영함.
  - **인증 도메인 자동 테스트는 아직 없음** — 지금은 실행 후 curl 수동 검증만 했다. 회귀 방지가 필요해지는 시점에 추가할 것.
- **2026-08-14**: **Refresh Token 완료 — 1주차 "인증/인가 기반 구축" 항목 전체 종료**. 만든 것: `RefreshTokenRepository`(Redis `refresh_token:{accountId}`, redis-design.md 9번), `RefreshTokenCookieFactory`(httpOnly 쿠키 생성/만료), `JwtProvider.createRefreshToken`, `POST /api/v1/auth/refresh`·`POST /api/v1/auth/logout`. 코드에서 Redis를 처음 쓰기 시작한 지점이다(`spring-boot-starter-data-redis` 추가). 확정한 사항: **Refresh Token 값은 JWT**(쿠키 값만으로 `accountId`를 알아내 Redis 키를 조회해야 하므로 — 불투명 문자열이면 역인덱스가 추가로 필요), **재발급 시마다 회전**(새 토큰을 발급해 Redis 값을 덮어씀), 쿠키는 `Path=/api/v1/auth`+`SameSite=Lax`, `Secure`는 기본 켜짐이되 https가 아닌 로컬에서만 `REFRESH_COOKIE_SECURE=false`로 끈다. 검증 중 발견해 고친 문제 1건: JWT의 `iat`/`exp`가 초 단위라 같은 초에 재발급하면 토큰 문자열이 이전 것과 완전히 동일해져 회전이 실제로는 일어나지 않았다 — 발급마다 고유한 `jti` 클레임을 넣어 해결. curl로 13개 경로 검증 완료(쿠키 발급 속성/바디에 refreshToken 미포함/Redis 저장 및 TTL/재발급/회전 후 옛 토큰 거절/쿠키 없음·위조 토큰 거절/인증 없는 로그아웃 차단/로그아웃 후 Redis 키 삭제 및 재발급 차단, **기기A 로그인 → 기기B 로그인 → 기기A 무효화**(다중 기기 미지원, decisions.md 3번)). `gradlew.bat test` 통과. api-design.md(쿠키 속성·`INVALID_TOKEN` 적용 범위)와 redis-design.md 9번(JWT 채택 이유·`jti`)에 반영 완료.
- **2026-08-15**: **ADMIN 승인 API 완료 (1주차 2번째 항목)**. `GET /api/v1/admin/accounts/pending`(승인 대기 ORGANIZER 목록, 가입 순 정렬), `PATCH /api/v1/admin/accounts/{accountId}/approve`(PENDING → ACTIVE). 만든 것: `AdminAccountService`, `AdminAccountController`, `AccountResponse` DTO, `Account.approve()`/`isOrganizer()`, `AccountRepository.findAllByRoleAndStatusOrderByCreatedAtAsc`. SecurityConfig에 `/api/v1/admin/**` → `hasRole("ADMIN")` 규칙 추가. 패키지는 별도 `domain/admin`을 만들지 않고 `domain/account` 안에 뒀다 — CLAUDE.md의 구조 규칙(계정 관리 기능은 account 도메인)에 따른 것. 에러 코드 2개 추가: `ACCOUNT_NOT_FOUND`(404), `ACCOUNT_ALREADY_APPROVED`(409). curl로 12개 시나리오 검증 완료 — 특히 **가입 → 로그인 차단(ACCOUNT_PENDING) → ADMIN 승인 → 로그인 성공**의 전체 흐름과, BUYER 토큰/무토큰의 관리자 API 접근 차단(403/401), 없는 계정(404)·재승인(409)·BUYER 계정 승인 시도(400) 거절을 확인함. `gradlew.bat test` 통과. api-design.md 6번에 응답 형식과 에러 코드 반영 완료.
- **2026-08-15**: **이벤트/구역/좌석 등록 API 완료 (1주차 3번째 항목)**. `GET /events`(목록, 오픈시각순), `GET /events/{id}`(상세, 스탠딩 잔여수량은 Redis 실시간 값), `POST /events`(등록), `PUT /events/{id}`(전체 교체), `DELETE /events/{id}`. 엔티티 3개(`Event`/`Section`/`Seat`)+`SectionType`, 리포지토리 4개, `EventService`, `EventController`, DTO 5개 신규. Redis `seat_status:{eventId}` 초기화를 담당할 `domain/seat/repository/SeatStatusRepository`도 여기서 만들었다(2주차 좌석 홀드가 이어서 쓸 예정).
  - **확정 사항(사용자 확인 완료)**: 이벤트 수정/삭제는 **오픈 전에만** 허용하고, 수정은 부분 수정이 아니라 **전체 교체**(등록과 같은 본문 → 기존 구역/좌석 전부 삭제 후 재생성). 오픈 후 시도는 `EVENT_ALREADY_OPENED`(409). 남의 이벤트는 수정/삭제 불가(`FORBIDDEN`). 설계 문서에 수정/삭제 API가 아예 없던 빈틈을 사용자가 지적해 이번에 확정한 것.
  - **좌석 대량 생성은 JPA가 아니라 `JdbcTemplate` batch로 처리한다** — `seat.id`가 AUTO_INCREMENT라 JPA(IDENTITY 전략)는 `hibernate.jdbc.batch_size`를 켜도 배치가 적용되지 않는다(생성된 ID를 INSERT마다 즉시 받아와야 하기 때문). JDBC URL에 `rewriteBatchedStatements=true`도 추가. **실측 검증: 좌석 1,100행 생성이 INSERT 문 2개로 처리됨**(전체 6개 = 이벤트1+구역3+좌석2, MySQL `Com_insert` 카운터로 확인). 이 프로젝트에서 JPA 대신 SQL을 직접 쓰는 유일한 지점이다.
  - **구현 중 발견해 문서에 반영한 설계 구멍**: 지정석만 있는 이벤트는 `seat_status:{eventId}` Hash에 넣을 필드가 하나도 없어(지정석은 "필드 없음=AVAILABLE" 규약) Redis 키 자체가 생기지 않고, 그러면 decisions.md 1번의 "키 없음 = 데이터 유실" 판정에 정상 이벤트가 걸린다. `meta:initialized` 필드를 항상 넣는 것으로 해결하고 redis-design.md 3번에 반영.
  - curl로 22개 시나리오 검증 완료(등록/조회/전체교체/삭제, 교체 시 옛 구역·좌석·Redis 필드가 모두 정리되는지, BUYER·무토큰·타 ORGANIZER 차단, 지정석/스탠딩 필드 조합 검증 4종, 과거 오픈시각 거절, 오픈 전 수정 성공 → 오픈 후 수정·삭제 409). `gradlew.bat test` 통과. api-design.md 2번(PUT/DELETE 추가)·redis-design.md 3번에 반영 완료.
  - **참고(테스트 환경)**: Windows Git Bash에서 curl 명령줄에 한글을 직접 넣으면 UTF-8이 아닌 인코딩으로 전송돼 400이 난다. 앱 문제가 아니라 셸 문제이며, 요청 본문을 UTF-8 파일로 저장해 `--data-binary @파일`로 보내면 정상 동작한다.
- **2026-08-15**: **이벤트 규모 상한 추가**(위 항목의 후속 — 사용자가 "상한이 없으면 100만 좌석 요청도 그대로 만들려 시도한다"는 점을 지적해 보완). **이벤트 전체 70,000석 / 구역 50개**로 확정(사용자 확인 완료). 70,000의 근거는 국내 최대 공연장인 잠실올림픽주경기장 약 69,000석. **공연 규모별로 다른 상한은 두지 않기로 정리** — "이 공연은 3,000석"은 상한이 아니라 주최자가 입력하는 값 그 자체이고, 공연별 상한을 두려면 그걸 정해줄 주체가 따로 필요해지기 때문. 여기서 막는 건 자릿수 입력 실수와 악의적 요청이다. 구역 개수를 따로 제한한 이유는 좌석 총합만 막으면 "1석짜리 구역 70,000개"가 통과하는데 구역은 JPA 저장이라 배치가 안 걸려 INSERT가 낱개로 나가기 때문. **구현 중 발견한 함정: 상한 검사를 `int`로 계산하면 `50,000 × 50,000 = 25억`이 오버플로돼 오히려 검사를 통과한다** — `long`으로 계산하도록 처리. curl로 7개 시나리오 검증(300,000석 거절 / 오버플로 시도가 25억으로 정확히 계산돼 거절 / 스탠딩 수량 초과 거절 / **정확히 70,000석은 통과** / 70,001석 거절 / 구역 51개 거절 / 구역 50개 통과). 70,000석 등록 실측: 좌석 50,000행 삽입에 **1,673ms, INSERT 문 53개**. decisions.md 12번에 근거 포함해 반영, api-design.md 2번에도 명시.
- **2026-08-15**: **상한 검증의 합계 오버플로 구멍 추가 수정**(위 항목의 후속 — 사용자가 "오버플로가 아직 문제 아니냐"고 되물어 재점검하다 발견). 구역별 좌석 수를 `long`으로 계산해도, **구역 하나의 최대치가 약 460경(`int` 최대값끼리의 곱)이라 그런 구역이 3개만 모이면 합계가 `long` 최대치(922경)마저 넘어** 같은 오버플로가 재현됐다. **합산 전에 구역 하나만으로 상한을 넘는지 먼저 검사**해 즉시 거절하도록 수정 — 이후 합계는 최대 (구역 50개 × 70,000석)이라 넘칠 수 없다. 교훈: "타입을 키우면 해결"이 아니라 **입력 범위를 먼저 좁혀야 안전해진다**. 검증: 극단값 구역 3개·10개 요청 모두 400 거절 + DB에 한 행도 생성 안 됨, 회귀 5종(일반 100석/정확히 70,000/70,001/구역 2개 합계 초과/구역 2개 합계 정확히 7만) 통과, `gradlew.bat test` 통과. decisions.md 12번·portfolio.md 소재 4에 반영.
  - 함께 정리한 오해: `int`를 `Integer`로 바꾸는 것은 해결책이 아니다(박싱된 같은 32비트 타입이라 범위 동일). 또 "검증 계산이 오래 걸린다"는 우려도 근거 없다 — 곱셈은 나노초 단위이고 느린 것은 좌석 INSERT인데, **검증이 생성보다 먼저 실행되므로** 거대한 값은 DB에 닿기 전에 거절된다.
- **2026-08-15**: **구역 수 상한 50 → 200으로 상향(잠정값)**. 실제 공연장을 확인해보니 KSPO DOME(14,594석)만 해도 1층이 43구역이고 플로어(B·C·D)·2층까지 더하면 50을 넘어, **현재 값으로는 아레나급 콘서트 등록 자체가 막히는 상태**였다(3주차 부하 테스트 시나리오도 못 만듦). 확인 과정에서 정리된 개념: **등급(VIP/R석 = 가격 구분)과 구역(1구역/B블럭 = 물리적 위치)은 다르고**, 우리 `section`은 "가격 + 사각 격자" 단위라 물리적으로 떨어진 구역을 합칠 수 없어 실제 구역 수만큼 등록해야 한다. 참고 수치: 예술의전당 콘서트홀 2,505석(R·S·A·B·C 5등급), KSPO DOME 14,594석, 잠실올림픽주경기장 69,950석. **정확한 값은 3주차 부하 테스트에서 조정하기로 하고 지금은 넉넉히 열어두기만 함**(사용자 확인 완료 — 지금 세밀하게 정할 필요 없다고 판단). decisions.md 12번·api-design.md 2번 반영.
- **2026-08-16**: **대기열(순번 관리 + 입장 토큰 Scheduler) 완료 (1주차 마지막 구현 항목)**. `POST /events/{id}/queue/entries`(진입), `GET /events/{id}/queue/entries/me`(내 순번 폴링 조회). 만든 것: `QueueRepository`(Redis Sorted Set `queue:{eventId}`, score=진입 시각 밀리초 — 정렬 순서 자체가 "먼저 온 순서"), `EntryTokenRepository`(Redis String `entry_token:{eventId}:{accountId}`, UUID 값, TTL은 좌석 홀드 TTL과 동일), `QueueService`, `QueueController`, `EntryTokenScheduler`(`queue.admit-interval-millis`마다 오픈된 이벤트만 골라 상위 `queue.admit-count`명에게 토큰 발급). `QueueStatusResponse`는 `entryToken`이 null이면 대기 중, 값이 있으면 통과(이때 `rank`는 0)로 표현한다.
  - **확정/설계 포인트**: 대기열 진입 자체는 인증만 있으면 되고 입장 토큰은 요구하지 않는다(토큰을 받기 위한 절차이므로 순환 의존이 되기 때문). 오픈 전 이벤트는 Scheduler가 아예 건너뛴다 — 대기열에는 오픈 전부터 줄을 설 수 있지만 오픈 전에 토큰을 주면 예매 시작 시각 자체가 무의미해진다. 이미 토큰을 가진 사용자가 다시 진입을 호출해도 대기열에 재등록하지 않고 기존 토큰을 그대로 반환한다(폴링 중 실수로 재호출해 뒤로 밀리는 걸 방지). `admitFront`는 **토큰 발급 → 대기열 제거** 순서로 처리한다 — 반대로 하면 제거 후 토큰 발급 중 실패 시 사용자가 대기열에서도 빠지고 토큰도 없이 통째로 유실되기 때문(현재 순서면 최악의 경우도 "대기열에 남아있지만 토큰도 있는" 상태라 조회 시 토큰을 먼저 보므로 사용자 입장에선 문제없고 다음 주기에 자연히 정리됨).
  - curl로 시나리오 검증 완료: 오픈 25초 전 진입 시 `rank:1, entryToken:null`(Scheduler가 건너뜀 확인), 오픈 이후 재조회 시 `rank:0`+토큰 발급 확인(Redis `entry_token:{eventId}:{accountId}` TTL도 확인), 대기열 Sorted Set에서 정상 제거(`ZRANK` nil) 확인, 토큰 보유 상태에서 재진입 시 재등록 없이 같은 토큰 반환, 진입한 적 없는 `/me` 조회 시 `QUEUE_ENTRY_NOT_FOUND`(404), 존재하지 않는 이벤트 진입 시 `EVENT_NOT_FOUND`(404), 무토큰 진입 시 `UNAUTHORIZED`(401). `gradlew.bat test` 통과.
  - **자동 테스트는 아직 없음** — 인증 도메인과 마찬가지로 지금은 curl 수동 검증만 함.
  - **다음 작업**: 포트원 테스트 계정/웹훅 스모크테스트 — 이걸로 1주차 구현 항목 전체가 끝난다.
- **2026-08-16**: **포트원 웹훅 수신 스모크테스트 완료 — 1주차 구현 항목 전체 종료**. 사용자가 포트원(V2) 콘솔에서 테스트 계정 가입 + 채널 2개(토스페이먼츠/카카오페이) 생성. 결제수단은 카드(토스페이먼츠)+간편결제(카카오페이) 2종을 함께 지원하기로 이 과정에서 확정(사용자 확인 완료, decisions.md 5번·11번 반영 — 그동안 미확정이던 PG사가 여기서 정리됨). 검증 방법: `WebhookSmokeTestController`(요청 헤더/바디를 로그로만 찍는 임시 컨트롤러, `SecurityConfig`에 해당 경로만 잠깐 `permitAll`)를 만들어 로컬 8080에 붙이고, `npx localtunnel`로 임시 공개 URL을 열어 포트원 콘솔의 웹훅 URL로 등록 → 콘솔의 "호출 테스트" 실행 → 서버 로그에 실제 포트원 서버(다른 IP·`AHC/2.1` User-Agent)가 보낸 V2 이벤트 페이로드(`{"type":"Transaction.Paid",...}`)가 수신된 것을 확인. **검증 후 임시 컨트롤러·`SecurityConfig`의 permitAll 추가·터널을 전부 원복/종료** — 실제 서명 검증과 결제 확정 로직은 3주차 결제 연동에서 `domain/reservation`에 정식 구현 예정. `gradlew.bat compileJava`/`test` 재확인 통과.
  - **확인된 것**: 콘솔의 "호출 테스트"는 서명 헤더(`webhook-signature` 등) 없이 온다 — 연결 확인용이라 그런 것으로 보이며, 실제 결제 이벤트 웹훅에 서명이 실리는지는 3주차에 실결제로 재확인 필요.
  - **아직 안 한 것**: 웹훅 시크릿(`PORTONE_WEBHOOK_SECRET`) 미발급 상태로 남겨둠 — 서명 검증이 실제로 필요해지는 3주차에 발급해도 늦지 않다고 판단(사용자 확인 완료).

## 다음 작업 순서

1. **1주차 전체 완료. 2주차 착수**: 좌석 상태 모델(단일 좌석 홀드), 홀드 TTL/만료 처리, Saga 상태머신, 분산락 벤치마크(그룹 좌석 홀드 락 방식 확정). 진행 상황은 위 "구현 진행 상황"에 계속 추가

## api-design.md 작성 중 나왔던 항목 정리 (모두 확정됨)

- **ORGANIZER 가입 승인**: `ADMIN` 승인 필요로 확정. 가입 시 `account.status = PENDING`, 승인 전 로그인 시도는 `ACCOUNT_PENDING` 에러(db-schema.md `account`, api-design.md 1·6번, decisions.md 12번에 반영 완료). 팝업/안내 문구 표시는 프론트엔드 담당, 백엔드는 에러 코드만 전달
- **ADMIN 역할의 기능**: 위 ORGANIZER 승인 처리가 첫 구체적 기능으로 확정 (api-design.md 6번 관리자 섹션 추가)
- **정산/알림**: 지금은 구현 보류, Kafka Consumer 확장 여지만 열어둠 (api-design.md 남은 항목에 명시)
- **좌석 홀드 해제 API**: 유지하기로 확정 (다른 좌석으로 바꾸는 UX용)

## 주차별 일정 (1주차~4주차, 2026-08-10~09-09)

decisions.md 13번 구현 순서를 4주에 배분한 것. **4주차는 새 기능·인프라 작업 없이 테스트 마무리 + 가벼운 리팩토링만** 하는 것이 원칙 — 이를 위해 인프라 확정과 카오스/부하테스트 착수를 3주차로 앞당겨 4주차에 부담을 넘기지 않는다.

| 주차 | 기간(대략) | 작업 |
|---|---|---|
| 1주차 | 08-10 ~ 08-16 | 인증/인가 기반 구축(회원가입/로그인/JWT, Refresh Token은 httpOnly Cookie + Redis 저장) → **ADMIN 승인 API(ORGANIZER 가입 승인)** → **이벤트/구역/좌석 등록 API** → 대기열(순번 관리) 구현 → **포트원 테스트 계정/웹훅 수신 스모크테스트**(사업자등록 불필요, 3주차 결제 연동 시점에 막히지 않도록 선행 확인). **전체 완료.** PG는 토스페이먼츠(카드)+카카오페이(간편결제) 2채널로 확정. |
| 2주차 | 08-17 ~ 08-23 | 좌석 상태 모델(단일 좌석 흐름), 홀드 TTL/만료 처리, Saga 상태머신, 분산락 벤치마크(그룹 좌석 홀드 락 방식 확정) |
| 3주차 | 08-24 ~ 08-30 | Kafka exactly-once, 결제 연동(**예약 취소 API 포함**), Nginx 설정 + **인프라(EKS/ElastiCache/MSK/CloudWatch) 검토·확정 및 AWS 배포**(decisions.md 10번, 4주차에서 앞당김) → **후반부에 카오스 테스트 + 부하테스트 착수** |
| 4주차 | 08-31 ~ 09-09 | 카오스 테스트·부하테스트 마무리, 결과 기반 간단한 리팩토링만. 새 기능/인프라 변경 없음 |

**2026-08-16 (1주차 마지막 날) 점검에서 발견/확정된 사항**: decisions.md 13번 구현순서와 주차 일정을 대조한 결과, "이벤트/구역/좌석 등록 API"와 "ADMIN 승인 API"가 설계(api-design.md 2·6번)는 되어 있었지만 구현순서/주차 일정 어디에도 명시적으로 안 들어가 있던 걸 발견 — ADMIN 승인이 없으면 ORGANIZER가 로그인을 못해 이벤트 등록 자체가 막히고, 이벤트/좌석 데이터가 없으면 2주차 좌석 상태 모델 작업을 검증할 수 없어 순서상 1주차(인증/인가 다음)에 추가함(사용자 확인 완료). 예약 취소 API는 별도 항목 없이 3주차 결제 연동에 포함(Saga 보상 로직 재사용). 이 참에 미확정이었던 **Refresh Token 저장 방식도 확정**: httpOnly Cookie로 전달 + Redis(`refresh_token:{accountId}`)에 저장해 로그아웃/재로그인 시 무효화, 다중 기기 로그인은 미지원(계정당 1개 세션). decisions.md 3번, redis-design.md 9번, db-schema.md, api-design.md 전부 반영 완료.

**1주차 마무리**: 인증/인가·ADMIN 승인·이벤트/구역/좌석 등록·대기열·포트원 웹훅 스모크테스트까지 모두 완료. 2주차(좌석 상태 모델/홀드 TTL/Saga/분산락 벤치마크)로 진행.

## 추후 결정 필요 (지금 작업에는 안 막힘)

### 구현 단계에서 확정 (db-schema.md / redis-design.md 작성 중 새로 식별된 항목)

- **결제 처리 타임아웃 수치**: PG 웹훅이 안 올 때 "타임아웃"으로 간주하는 대기 시간(홀드 TTL과는 별개 값). `hold` 키 TTL을 이 값으로 재설정해 감지한다(redis-design.md 4번) — 정합성 일관성 체크 중 새로 발견한 항목: 기존 설계(`PERSIST`)로는 PG가 웹훅을 끝내 안 보내는 타임아웃을 아무도 감지하지 못하는 구멍이 있어서 TTL 재설정 방식으로 수정함
- 홀드 TTL과 결제 처리 시간의 경합 처리: 결제 요청이 TTL 만료 시각 직전에 들어오는 경우의 원자성 보장 방식 (redis-design.md 4번 "미정 사항" 참고, Lua 스크립트로 "TTL 확인 + 재설정"을 원자적으로 묶는 방식 검토 예정)
- 스탠딩 예약의 `quantity`가 여러 장일 때 개별 티켓 단위 이력이 필요한지 (지금은 한 예약 행 = N장으로 묶음, db-schema.md 참고)

### 시점이 정해진 결정 (해당 주차 되면 확정)

- **분산락 기술**(decisions.md 2번): Redisson RLock vs DB 비관적 락(`SELECT ... FOR UPDATE`) — 2주차 분산락 벤치마크 후, 이미 정해진 채택 기준(정합성 우선 → 처리량 차이 20%p 이상이면 우세한 쪽, 미만이면 DB 락)에 따라 확정
- **인프라 도입 여부**(decisions.md 10번): EKS, ElastiCache, MSK, CloudWatch — 3주차 배포 시점에 확정
- **architecture.md "인프라 구성" 표 추가**: classq(`all/classq/.claude/docs/architecture.md`)처럼 인프라 구성 표를 별도로 추가하기로 확인됨. 위 인프라 도입 여부가 3주차에 확정된 뒤 추가

### 여유 있을 때 아무 때나 결정 가능 (일정과 무관, decisions.md 11번에서 정리된 항목)

- 성능/처리량 목표치: Gatling 부하테스트 성공 기준(동시접속 N명, P99 응답시간 Xms 등) 미정
- Outbox 테이블 정리 정책: TTL/배치삭제 정책 — 운영 단계 진입 전 결정 필요
