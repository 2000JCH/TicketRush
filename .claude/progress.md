# TicketRush — 진행 상황

설계 문서(`decisions.md`/`architecture.md`/`db-schema.md`/`redis-design.md`/`api-design.md`)는 1차 작성이 전부 끝난 상태이고, 이후 구현 단계에서 드러난 세부 사항은 그때그때 각 문서에 직접 반영한다 — 여기 별도로 요약해두지 않는다(중복·오래된 스냅샷이 되기 쉬워 2026-08-27에 정리함). 지금 진행 상황은 아래 "구현 진행 상황"의 가장 최근 날짜 항목과 "주차별 일정" 표를 보면 된다.

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

- **2026-08-19**: **좌석 상태 모델(단일 좌석 흐름) 완료 (2주차 첫 항목)**. `GET /events/{id}/seats?sectionId=`(좌석 상태 조회), `POST /events/{id}/seats/holds`(홀드), `DELETE /events/{id}/seats/holds`(해제). 만든 것: `SeatStatusRepository` 확장(`holdSeat`/`releaseSeat`/`holdStanding`/`releaseStanding`/`findSeatStatuses`), `HoldRepository`(redis-design.md 4번 `hold:*` 키), `ActiveReservationRepository`(8번 `active_reservation:*` 키), `SeatService`, `SeatController`, `QueueService.validateEntryToken`(좌석/결제 API 공통 `X-Entry-Token` 검증, api-design.md 공통 규칙), `Reservation`/`ReservationStatus` 엔티티 + `ReservationRepository`(누적 확정 매수 조회 전용으로 최소 컬럼만 미리 생성 — 실제 INSERT는 Saga 단계에서 구현).
  - **이번 단계 범위(사용자 확인 완료, 전부 다음 단계로 명시적으로 미룸)**: (1) 그룹 홀드(좌석 2개)는 구현하지 않고 `seatIds` 2개 요청은 `INVALID_INPUT`으로 거절 — 분산락 벤치마크(decisions.md 2번) 이후 구현. (2) 홀드 TTL 부여·만료 시 Keyspace Notification Consumer·`hold`/`active_reservation` 키 3단계 TTL 재설정은 전혀 구현하지 않음 — 지금은 두 키 모두 TTL 없이 생성되고 `DELETE .../seats/holds`로 명시적 해제해야만 지워진다("홀드 TTL/만료 처리" 단계에서 구현). (3) 사재기 방지 3중 규칙(`ACTIVE_RESERVATION_EXISTS`/`QUANTITY_LIMIT_EXCEEDED` 2종)은 이번에 포함 — 세 번째 규칙(이벤트당 누적 확정 2매)을 위해 `reservation` 테이블을 이번에 최소 컬럼으로 미리 만들었고, 결제 연동 전이라 지금은 항상 0건으로 통과한다(3주차부터 실제 값이 쌓임, 정상).
  - **구현 단계에서 단순화한 것(decisions.md 1번 반영)**: 지정석의 `AVAILABLE → HELD` 원자 전이를 원래 설계대로 Lua 스크립트가 아니라 `HSETNX`(필드 없을 때만 쓰는 단일 명령)로 구현했다 — Redis 싱글 스레드 특성상 이미 원자적이라 Lua가 굳이 필요 없었다. 동일한 보장을 더 단순하게 얻은 것.
  - **`active_reservation` 키 값 인코딩을 이번에 확정**: `"SEAT:{sectionId}:{seatId}"` / `"STANDING:{sectionId}:{quantity}"` — 홀드 해제(`DELETE`) 시 이 값만 보고 무엇을 되돌릴지 판단한다(redis-design.md 8번 반영).
  - Node.js 스크립트로 20여 개 시나리오 검증 완료(로컬에 Python이 Windows Store stub만 있어 curl 대신 fetch 기반 스크립트 사용) — 대기열 통과 후 좌석 상태 조회/홀드/해제 전체 흐름, `X-Entry-Token` 없음(`ENTRY_TOKEN_REQUIRED`)·위조(`ENTRY_TOKEN_EXPIRED`) 거절, 좌석 홀드 성공 후 다른 계정 재홀드 거절(`SEAT_ALREADY_HELD`), 같은 계정 동시 진행 거절(`ACTIVE_RESERVATION_EXISTS`), 그룹 홀드 요청 거절(`INVALID_INPUT`), 존재하지 않는 좌석(`SEAT_NOT_FOUND`), 해제 후 좌석이 다시 AVAILABLE로 조회되는지, 스탠딩 2매 홀드 후 이벤트 상세의 `remainingQuantity` 반영, 3매 요청 거절(`QUANTITY_LIMIT_EXCEEDED`), 스탠딩 매진(`STANDING_SOLD_OUT`)과 실패 시 잔여수량이 정확히 롤백되는지까지 확인. `gradlew.bat test` 통과(Reservation 엔티티 추가로 인한 스키마 변경도 `ddl-auto=update`로 문제없이 반영됨).
  - **참고(로컬 환경)**: 테스트 중 포트 3306을 다른 프로젝트의 컨테이너(`mysql-container`)가 선점해 `ticketrush-mysql`이 기동하지 못한 걸 발견 — 사용자 확인 후 그 컨테이너를 멈추고 진행함(TicketRush와 무관한 컨테이너라 삭제는 하지 않고 정지만 함).

- **2026-08-19**: **홀드 TTL/만료 처리 완료 (2주차 두 번째 항목)**. 만든 것: `HoldScheduleRepository`(redis-design.md 4-1번 신규 키 `hold_schedule`), `HoldExpiryScheduler`(`queue.admit-interval-millis`와 동일한 `@Scheduled` 폴링 패턴), `HoldRepository`/`ActiveReservationRepository`에 TTL 인자 추가, `SeatService`에 `HoldRecord`(홀드 1건을 나타내는 통합 인코딩)와 `releaseExpiredHolds()` 추가. `SeatHoldResponse.holdExpiresAt`이 이제 항상 실제 값을 반환한다(직전 단계에선 항상 `null`).
  - **설계를 원래 문서(redis-design.md 4번 Keyspace Notification)와 다르게 구현함(사용자와 논의 후 확정)**: Redis Keyspace Notification 대신 "만료 시각순 정렬 집합(`hold_schedule`, Sorted Set) + 주기적 스케줄러" 방식으로 재설계했다. 이유 두 가지를 사용자와 논의로 짚어냄 — (1) Keyspace Notification은 pub/sub라 앱이 그 순간 재시작 중이면 이벤트가 재전송 없이 영구 유실되어, 실제로는 아무도 안 잡고 있는데 영원히 HELD로 남는 "유령 좌석"이 생길 수 있음. (2) `expired` 이벤트는 만료된 키 "이름"만 주고 그 시점엔 값이 이미 사라진 뒤라, 스탠딩 홀드를 되돌리는 데 필요한 quantity를 읽을 방법이 없었음. `hold_schedule`의 member 문자열(`HoldRecord.encode()`) 자체에 롤백에 필요한 정보(eventId/accountId/sectionId/seatId 또는 quantity)를 전부 담아 두 문제를 모두 피했다. 이 member 인코딩은 `active_reservation` 키의 값과도 동일한 문자열을 공유해(단일 소스) 두 곳이 어긋날 위험을 없앴다.
  - **명시적 해제가 반드시 스케줄도 함께 지워야 하는 이유를 검증으로 확인**: 사용자 A가 좌석을 풀고 사용자 B가 같은 좌석을 새로 잡은 뒤, A의 원래 스케줄 항목이 남아있었다면 뒤늦게 발동해 B의 새 홀드를 잘못 해제했을 것이다 — `SeatService.release()`가 `holdScheduleRepository.unschedule()`도 함께 호출하도록 구현하고, 이 시나리오를 정확히 재현하는 시간차 테스트로 검증 완료(A의 원래 만료 시점은 지나되 B의 새 만료 시점 전인 구간에서 B의 홀드가 멀쩡히 유지되는지 확인).
  - **Redis AOF/RDB를 문서 원안대로 껐다(사용자 확인 완료)**: `docker-compose.yml`의 Redis가 `--appendonly yes`로 이미 켜져 있어 redis-design.md("AOF/RDB 영속성 옵션은 켜지 않는다")와 모순됐던 걸 발견 — "결제 중간에 날아가면 어떡하나"는 우려로 켜둔 것으로 보이나, 실제로 결제 진행 중 상태를 지키는 건 Redis 영속성이 아니라 "결제 요청 시점부터 MySQL에 동기 INSERT + Redis 재시작 시 MySQL 기준 rebuild"(decisions.md 1·5번, 이미 있던 장치)라 AOF/RDB가 그 역할을 하지 않는다고 설명해 정리함. `--save ""`까지 추가해 이미지 기본 RDB 스냅샷도 껐다(`CONFIG GET appendonly`/`save`로 둘 다 꺼졌음을 확인).
  - Node.js 스크립트로 홀드 TTL을 8초로 낮춰 기동한 앱에 대해 검증(운영 기본값 10분은 실제로 기다리기엔 너무 길어 테스트 환경변수로만 단축, `application.properties` 기본값은 그대로 둠): 홀드 성공 시 `holdExpiresAt`이 실제 미래 시각으로 채워짐, 방치된 좌석 홀드가 TTL+스케줄러 주기 뒤 자동으로 AVAILABLE 복귀, `active_reservation`도 함께 정리되어 재홀드 가능, 명시적 해제가 스케줄을 확실히 지워 늦게 도착한 옛 스케줄이 새 홀드를 잘못 풀지 않음, 스탠딩 홀드도 만료 시 잔여수량이 정확히 복구됨. `gradlew.bat test` 통과.
  - **다음 단계로 미룬 것**: 결제 요청 시 스케줄 재조정(`ZADD`로 결제 처리 타임아웃 시각으로 덮어쓰기)·결제 확정 시 스케줄 완전 제거(`ZREM`, 원래 설계의 `PERSIST`에 대응) 연동은 결제 API 자체가 아직 없어 Saga/결제 연동 단계에서 이어서 구현한다. 결제 처리 타임아웃 수치도 여전히 미정.

- **2026-08-19**: **Saga 상태머신 완료 (2주차 세 번째 항목)**. 만든 것: `ReservationSeat`/엔티티 완성(`Reservation.request/confirm/fail/release`, `ReservationSeat.of/confirm/release`), `ReservationSeatRepository`(`existsBySeatIdAndStatusIn`으로 db-schema.md 6번 `uq_active_seat`를 애플리케이션 레벨로 대체), `IdempotencyRepository`(redis-design.md 5번), `ReservationService`(`requestPayment`/`confirmPayment`/`markPaymentFailed`/`releaseAfterFailure`), `POST /api/v1/reservations` API. `SeatService`에 `findActiveHold`/`reschedulePaymentTimeout`/`confirmHold`/`compensate` 공개 메서드를 추가해 `ReservationService`가 좌석 도메인과 연동한다.
  - **이번 단계 범위(사용자 확인 완료)**: `requestPayment`는 `PAYMENT_REQUESTED` 행만 만들고 **실제 PG(포트원) 호출은 하지 않는다** — 실제 웹훅 서명 검증·Kafka exactly-once는 3주차 "결제 연동"에 그대로 남겨둠. `confirmPayment`/`markPaymentFailed`/`releaseAfterFailure`를 실제로 트리거할 PG 웹훅이 없어, 이 메서드들을 직접 호출하는 **JUnit 자동 테스트(`ReservationServiceTest`, 8개 케이스)로 검증** — 이 프로젝트의 첫 자동 테스트 도입(그동안 인증/대기열/좌석 도메인은 수동 curl/Node 스크립트 검증만 해왔음). `GET /reservations/me`, `GET /reservations/{id}`, 취소 API도 3주차로 미룸.
  - **`markPaymentFailed`/`releaseAfterFailure`를 두 개 메서드로 분리한 이유**: decisions.md 5번의 Choreography(Kafka Consumer 기반)에서는 `PAYMENT_REQUESTED → PAYMENT_FAILED`와 "좌석 반납 + `→ SEAT_RELEASED`"가 서로 다른 트랜잭션(별도 Consumer 처리)이 될 예정이라, 지금부터 독립된 메서드로 나눠두면 3주차에 Kafka Consumer가 각각을 호출하도록 이어붙이기만 하면 된다.
  - **`reservation_seat`의 생성 컬럼(`active_seat_id`)·CHECK 제약 처리를 확정(CLAUDE.md에 예고돼 있던 결정, 사용자 확인 완료)**: Flyway를 도입하지 않고 `ddl-auto=update`를 계속 쓰기로 하고, 대신 `ReservationSeatRepository.existsBySeatIdAndStatusIn`로 애플리케이션 레벨에서 "같은 좌석에 진행 중인 예약이 이미 있는지"를 검사하는 2차 방어선을 뒀다(정상 흐름에서는 Redis 좌석 홀드가 이미 막아줘서 여기 걸릴 일이 없음).
  - JUnit 자동 테스트 8개(결제 요청 성공/중복 idempotencyKey 거절/홀드 없이 요청 거절/요청과 홀드 불일치 거절/확정 전이+active_reservation 정리/확정 멱등성/실패→보상→좌석 반납/스탠딩 수량·금액 계산) 전부 통과 + `POST /api/v1/reservations` 실제 HTTP 엔드포인트도 Node 스크립트로 별도 검증(성공/중복 거절/입장 토큰 없음 거절). `gradlew.bat test` 전체 통과.
  - **다음 단계로 미룬 것**: 실제 PG 호출, 웹훅 서명 검증, Kafka exactly-once 발행, 예약 조회/취소 API — 전부 3주차 "결제 연동". 결제 처리 타임아웃 수치(`payment.processing-timeout-millis`, 지금은 2분 placeholder)도 여전히 미정.

- **2026-08-20**: **분산락 벤치마크 구현 완료 (2주차 마지막 항목) — 2주차 코드 구현 전체 종료**. 그룹 좌석 홀드(좌석 2개 동시 선택)를 `domain/seat/lock/GroupHoldLockStrategy` 인터페이스로 추상화하고 두 구현을 모두 만들었다: `RedissonGroupHoldLockStrategy`(RLock, seatId 오름차순으로 순서대로 락을 걸어 데드락 방지)와 `DbPessimisticLockGroupHoldLockStrategy`(`SeatRepository.findAllByIdInForUpdate`의 `SELECT ... FOR UPDATE`, `seat` 행을 좌석 상태의 원천인 Redis와 무관하게 순수 뮤텍스로만 사용). `group-hold.lock-strategy` 프로퍼티(`redis`/`db`, 기본값 `redis`)로 `@ConditionalOnProperty` 전환한다.
  - **"2주차 완료"의 범위를 명확히 함**: 원래 주간 계획(아래 "주차별 일정" 표)의 2주차 마지막 항목은 "분산락 벤치마크(그룹 좌석 홀드 락 방식 **확정**)"이었다 — "확정"은 Redisson과 DB 락 중 실제로 하나를 고르는 것까지 포함하는 표현이었다. 오늘 끝난 건 그 결정에 필요한 **두 구현과 정합성 검증**이고, **어느 쪽을 채택할지는 아직 정하지 않았다** — 3주차 Gatling 부하테스트로 넘어간다(decisions.md 2번 채택 기준 적용은 그때). 그래서 "2주차 코드 구현은 끝났지만, '방식 확정'이라는 마지막 결정 하나는 3주차에 걸쳐 있다"는 것이 정확한 상태다(사용자와 함께 이 구분을 확인함).
  - **오늘 범위(사용자 확인 완료)**: 두 락 구현과 그룹 홀드 API까지만 오늘 완성하고, **실제 Gatling 비교·최종 채택은 3주차 부하 테스트로 미룬다** — decisions.md 2번의 채택 기준(오버셀 0건 전제 → 처리량 차 20%p 기준)은 아직 적용하지 않았다.
  - **Redisson 도입 방식**: `redisson-spring-boot-starter` 대신 core 아티팩트(`org.redisson:redisson:4.7.0`)만 추가하고 `RedissonConfig`에서 `RedissonClient`를 직접 구성했다 — Spring Boot 4가 기본으로 쓰는 Jackson 3와 Redisson의 Jackson 2 기반 Spring 자동 설정이 얽힐 위험을 사전에 차단하기 위함([redisson#6892](https://github.com/redisson/redisson/issues/6892)에서 아직 논의 중인 걸 확인). RLock은 값 직렬화가 필요 없어 애초에 이 코덱을 거칠 필요가 없었다.
  - **DB 락 구현에서 발견한 함정**: `SeatService.hold()`가 `@Transactional(readOnly = true)`인데, 그 안에서 그냥 호출하면 DB 락이 이후 처리(스케줄 등록 등)까지 필요 이상으로 오래 유지돼 Redisson 구현과 락 보유 시간이 달라져 벤치마크 조건이 어긋난다. `DbPessimisticLockGroupHoldLockStrategy.withLock`을 `Propagation.REQUIRES_NEW`로 별도 트랜잭션으로 열어, 락이 action 실행 동안으로만 좁혀지게 했다 — Redisson 구현이 락을 `groupHoldLockStrategy.withLock` 호출 구간에서만 쥐는 것과 정확히 대응시키기 위함.
  - **`SeatService`/`ReservationService`를 좌석 1개 전제에서 좌석 목록(1~2개) 전제로 확장**: `HoldRecord`/`ActiveHold`의 `seatId`(단일)를 `seatIds`(목록)로 바꾸고, `active_reservation`/`hold_schedule` 인코딩도 쉼표 구분 목록을 담도록 재설계(`HoldRecord.encode/parse`). `confirmHold`/`compensate`/`reschedulePaymentTimeout`가 좌석 목록을 순회하도록 고쳤고, `ReservationService`도 `reservation_seat` 자식 테이블에 좌석 1~2행을 각각 저장/확정/반납하도록 반복 처리로 바꿨다. 기존 단일 좌석 흐름은 동작을 그대로 유지한다(`ReservationServiceTest` 8개 전부 통과로 확인).
  - **그룹 홀드 원자성**: "두 좌석 다 성공 또는 둘 다 실패"를 보장하기 위해 `holdSeatsOrRollback`이 순차적으로 `HSETNX`를 시도하다 하나라도 실패하면 그때까지 잡은 좌석을 즉시 롤백한다. 락은 이 동시성(여러 요청이 겹치는 좌석 쌍을 동시에 시도하는 것) 자체를 막는 역할이고, 오버셀 방지 자체는 좌석 단위 `HSETNX`(decisions.md 1번)가 이미 원자적으로 보장한다.
  - **자동 테스트로 검증**: `SeatServiceGroupHoldTest`(기본 프로퍼티=Redisson, 5개 케이스: 그룹 홀드 성공/중복 좌석 거절/3개 이상 거절/한 좌석 선점 시 롤백/동시 요청 8개 중 1개만 성공)와 `SeatServiceGroupHoldDbLockTest`(`@SpringBootTest(properties = "group-hold.lock-strategy=db")`로 별도 스프링 컨텍스트, 같은 핵심 시나리오 3개 재검증) — 두 파일 다 오버셀 0건을 동시성 테스트(`ExecutorService` 8스레드가 같은 좌석 쌍을 동시에 시도)로 직접 확인했다. 전체 테스트(`ReservationServiceTest` 8 + 이 두 파일 8 + 기존 1)까지 `gradlew.bat test` 17개 전부 통과.
  - **다음 단계로 미룬 것**: Gatling 시나리오 작성, 실측 처리량/지연/에러율 비교, 최종 락 방식 채택(decisions.md 2번) — 전부 3주차 부하 테스트. `portfolio.md`의 "분산락 벤치마크" 소재(192번째 줄 표)는 그 실측이 끝난 뒤에 채운다.

- **2026-09-01**: **Phase 2 실행 전 테스트 설계 보완 3건 — 실제 실행은 아직 시작 전.**
  1. **부하 시나리오에서 회원가입/로그인을 뺐다.** `GoldenPathSimulation`이 매 VU마다 signup+login을
     하던 걸 제거하고, `seed-load-test.ps1`(`-BuyerCount` 신규 파라미터)이 BUYER 계정을 미리
     가입·로그인까지 끝내 `src/gatling/resources/data/buyers.csv`(email,password,accessToken, 순환
     feeder)로 공급하도록 바꿨다. 실제 티켓팅처럼 "오픈 훨씬 전에 로그인 완료" 상태를 가정해, 로그인
     BCrypt 비용이 분산락 벤치마크의 락 경합 신호·한계 테스트의 병목 판별을 가리는 걸 막기 위함(전체
     테스트에 적용, 사용자 확인 완료). buyers.csv는 gitignore 대상.
  2. **대기열 진입 투입 방식을 시나리오별로 분리했다(`inject.mode`).** 실제 티켓팅은 오픈 시각에
     다같이 클릭하는 순간 폭주지 몇십 초 걸친 점진 유입이 아니라는 지적(사용자)을 반영. `chaos`(기본,
     ①②용): 70%(`burst.ratio`)는 완전 동시 투입 + 나머지는 트리클 유입(장애 주입 시점 이후에도
     트래픽이 이어지게). `atonce`(③ 분산락 벤치마크용): 전원 완전 동시 — 좌석 경합을 가장 강하게
     재현. `run-gatling.ps1`에 `-InjectMode`/`-BurstRatio` 추가.
  3. **AWS 배포 전 로컬 리허설 스택을 만들었다(`docker-compose.rehearsal.yml` + `ticketrush-backend/Dockerfile` 신규).**
     "로컬에서 무제한으로 돌리면 노트북 성능만 재는 것이고, AWS에 올렸다가 못 버티면 요금 나가는 중에
     고쳐야 한다"는 사용자 지적으로 시작 — AWS EC2(`m6i.xlarge`, 4vCPU/16GiB)+RDS(`db.m6i.large`,
     2vCPU/8GiB) 스펙을 로컬에 그대로 흉내낸다. 평소 개발용 `docker-compose.yml`은 전혀 안 건드리는
     별도 오버레이 파일 — 리허설에서만 앱도 Dockerfile로 빌드해 컨테이너로 띄운다(평소엔 여전히
     `gradlew bootRun`). EC2 예산은 app(2vCPU/6g)+kafka(1/4g)+kafka-connect(0.5/2g)+redis(0.25/1g)+
     nginx(0.25/1g)로 분배, RDS 예산은 mysql이 별도(분산락 벤치마크가 DB 락을 채택하면 `db.r6i.large`
     16g로 재조정). Nginx는 `nginx.rehearsal.conf`(신규, upstream이 `host.docker.internal` 대신
     컨테이너 서비스명 `app`)를 쓴다 — Compose 파일 병합 시 volumes 리스트가 누적(append)되는 문제를
     `!override` YAML 태그로 해결. **빌드→기동→헬스체크까지 전부 실제 검증 완료**: 앱 컨테이너
     빌드 성공, mysql/redis/kafka/kafka-connect/app 순서로 기동해 앱이 컨테이너 네트워크 안에서
     `mysql`/`redis`/`kafka:29092` 서비스명으로 정상 연결, `docker inspect`로 리소스 제한(app 2
     CPU/6GiB, mysql 2 CPU/8GiB) 실제 적용 확인, Nginx(8081) 경유 응답도 정상. 검증 후 컨테이너는
     내려둠. Prometheus/Grafana는 AWS EC2 박스에 포함되는 구성요소가 아니라 제한 대상에서 제외.
     `test-plan.md` 0-1번(신규)·4번(한계 테스트는 이 리허설 스택으로 진행)·3번(분산락 명령 예시에
     `-InjectMode atonce`, `-BuyerCount` 반영)에 문서화 완료.
- **2026-09-01**: **Phase 2 카오스 테스트 A-1(Redis 다운) 준비 중 — 도구 버그 2건 + 설계·구현 간극 1건을 발견해 그 자리에서 고침.**
  1. **Pumba 스크립트가 이 PC에서 조용히 멈추는 버그 발견·수정**: `chaos-redis.ps1`/`chaos-kafka.ps1`이 `docker run ...`을 그냥 `docker`로 호출했는데, 이 PC는 **PowerShell PATH에 docker가 없고 Git Bash에만 있어서**(seed 스크립트 때도 겪음, 2026-08-28 항목) PowerShell이 "파일 열기" 팝업을 띄우며 조용히 멈췄다. `Get-Command docker`로 실제 docker.exe 경로를 찾고, 실패 시 Docker Desktop 기본 경로로 폴백하도록 두 스크립트 수정.
  2. **`test-plan.md`의 오버셀 검증 SQL 버그 발견·수정**: `rs.status='ACTIVE'`(존재하지 않는 enum 값 → 항상 0행=거짓 통과)와 `rs.section_id`(존재하지 않는 컬럼, `seat.section_id`를 거쳐야 함) 두 군데. SQL 버그 탓에 이 검증이 한 번도 제대로 돈 적이 없었다는 뜻이라, "통과했다"는 착시를 실제로 겪은 셈.
  3. **rebuild 로직 자체가 코드에 없다는 걸 발견 → 구현**: decisions.md 1번·portfolio.md 소재 6·redis-design.md 6번 참고. `SeatStatusRebuildService` 신규(좌석 조회/홀드/해제/결제확정/취소 진입점에서 이벤트별 rebuild 마커 확인 → 없으면 DB 기준으로 `seat_status` 재구성), 리포지토리 쿼리 2개 신규, `SeatStatusRepository`에 마커/락/재구성 프리미티브 추가, 에러 코드는 api-design.md에 이미 있던 `SERVICE_TEMPORARILY_UNAVAILABLE`(503) 재사용. 좌석 하나를 결제 요청 상태로 만들고 Redis 키를 강제로 지워 재현하는 시나리오로 검증(재구성 후 같은 좌석 재홀드가 정확히 409). 기존 자동 테스트 전부 통과. `decisions.md` 1번·`redis-design.md` 6번(재연결 이벤트 트리거 → 요청 경로 트리거, RENAME 스왑 → 락)에 반영.

- **2026-09-03**: **카오스 A-1 (Redis 다운) 실행 완료 — 전부 통과. (`test-results.md` 1번, 스크린샷 `.claude/screenshots/tests/a1-redis-down/`)**
  - **선행 수정**: (1) `application.properties`에 `spring.data.redis.timeout=2000`(env `REDIS_TIMEOUT`) — 미설정 시 Lettuce 기본 60초라 장애 중 시작된 요청이 복구 후에도 최대 60초 매달렸다 실패(A-1 준비 중 발견). (2) `chaos-*.ps1`이 stop/start UTC 시각을 `scripts/chaos-timeline.log`(gitignore)에 기록. (3) `GoldenPathSimulation`/`run-gatling.ps1`에 chaos 모드 전용 꼬리 부하 옵션(`-TailSeconds`, 기본 0=꺼짐) — 복구 후에도 트래픽이 이어져 "장애→복구→정상 복귀"가 Grafana 한 화면에. 커밋: `feat(seat)` rebuild / `fix(chaos)` 스크립트 / `chore` 타임아웃·문서 / `chore(grafana)` 대시보드 한글화·Kafka lag 패널.
  - **실행**: 클린 스택(`docker compose down -v`) + 새 이벤트(400석) + BUYER 400명. **총 390 VU** — 버스트 105(오픈 순간 동시) + 트리클 45(40초 분산) + 꼬리 240(초당 2명 × 120초, 복구 관찰용). 좌석 홀드 트래픽이 흐를 때 `docker stop ticketrush-redis` → 61초 → `docker start`. (Pumba `stop --restart`는 이 환경에서 "no containers to stop"으로 불안정 → docker 직접.)
  - **결과**: **오버셀 0** / Redis 복구 → seat_status 재구성 완료 **~4초** / rebuild **1회** + 락 경합 요청 `503` 3건 / **최대 응답시간 2,035ms**(타임아웃 2초 cap 확인 — 이전엔 60초 매달림). KO 1,187건 중 79%가 `404 QUEUE_ENTRY_NOT_FOUND`(Redis가 대기열 Sorted Set도 잃음 — decisions.md 1번 "알려진 한계"), 나머지는 장애 중 타임아웃 500·rebuild 가드 503. 진짜 서버 버그성 실패 0.
  - **다음 후보(안 고침)**: 장애 중 Redis 타임아웃을 일반 500이 아니라 503으로 매핑.

- **2026-09-03**: **카오스 A-2 (Kafka 브로커 다운) 실행 완료 — 전부 통과. (`test-results.md` 2번, 스크린샷 `.claude/screenshots/tests/a2-kafka-down/`)**
  - **선행: `scripts/fail-payments.ps1` 신규** — Gatling 골든패스는 결제 요청까지만 하므로 outbox→Kafka 경로가 안 돌아간다. 이 스크립트가 주기적으로 `PAYMENT_REQUESTED` 예약을 조회해 일부에 서명된(`PORTONE_WEBHOOK_SECRET`, Standard Webhooks HMAC-SHA256) `Transaction.Failed` 웹훅을 쏜다 → `markPaymentFailed`가 `outbox_events` INSERT. `test-plan.md` A-2 검증 SQL도 수정(원래 `SELECT status FROM outbox_events`인데 발행 상태 컬럼이 없음 — INSERT 전용).
  - **실행**: 클린 스택 + 새 이벤트(400석) + BUYER 400명. 총 390 VU 골든패스 + `fail-payments.ps1`(FailRatio 0.8) 병행. 좌석/웹훅 트래픽이 흐를 때 `docker stop ticketrush-kafka` → 91초 → `docker start` → `docker compose restart kafka-connect` + 커넥터 재등록.
  - **결과**: **장애 중 `payment-request` 5xx 0건 / 웹훅 5xx 0건**(계속 200 → outbox 계속 쌓임). 장애 중 PAYMENT_FAILED 16건 대기 → **복구 후 전부 SEAT_RELEASED**(outbox 239 = SEAT_RELEASED 239, 유실 0). **Consumer lag ~15초 만에 0**(커넥터 재시작 후). 장애 중 API P99 ~60ms(평상시와 동일). 오버셀 0. → **Kafka가 91초 죽어도 사용자 무영향 + 이벤트 유실 0.** Outbox 패턴이 Kafka를 critical path에서 뺀 것이 실측으로 확인됨.
  - **발견/개선 포인트**: (1) Kafka 다운 시 Debezium 커넥터가 `UNASSIGNED`로 떨어져 자동 복구 안 됨 → 수동 재시작 필요(운영이라면 Connect 헬스체크 + 자동 재시작). (2) 부하 프로파일의 `nothingFor(40s)` 공백이 장애 구간 중간에 겹침 — 다음 라운드엔 없애거나 줄일 것.

- **2026-09-03**: **분산락 벤치마크 완료 — Redisson RLock 채택 확정. (`test-results.md` 3번, `decisions.md` 2번)**
  - **선행 수정 커밋 `8570d91`**: `DbPessimisticLockGroupHoldLockStrategy`에 3초 lock timeout(`SeatRepository.findAllByIdInForUpdate`의 `lock.timeout` 힌트 → `FOR UPDATE WAIT 3`) + 락 획득 실패 예외를 `GROUP_HOLD_LOCK_TIMEOUT`(409)으로 매핑. 이전엔 MySQL 기본 50초 블로킹 후 일반 500이라 Redisson과 실패 방식이 달라 공정 비교 불가.
  - **측정**: 300명 완전 동시(`atonce`) → 좌석 4개, 전부 그룹 홀드, `QUEUE_ADMIT_COUNT=1000`. A안(redis)·B안(db) 각각 새 이벤트. **처음 40석 + 대기열 정상 투입으론 홀드 시점이 분산돼 경합이 안 생겨(P99 27ms) 무효 → 좌석 축소 + 대기열 개방 재실행.**
  - **결과**: 오버셀 0(둘 다) / 처리량 동일(238 req/s) / Global P99 거의 동일(1,032 vs 1,018ms) / 락 획득 타임아웃 0(둘 다). **유일한 차이 = HikariCP pending: Redisson 0 vs DB 락 147**(REQUIRES_NEW 트랜잭션이 커넥션 점유).
  - **채택 = Redisson**: 성능 동등이라 원래 tie-breaker는 "DB 락(추가 인프라 불필요)"이지만, 우리는 Redis가 이미 코어라 그 근거가 안 맞음. DB 락은 부하 커지면 커넥션 고갈로 먼저 무너질 자원. → `aws-spec.md` B-2도 RDS `db.m6i.large` 확정(DB 락이었으면 `db.r6i.large` 필요), `portfolio.md` 소재 7 추가.
  - **부수 확인**: 우리 홀드 액션이 HSETNX 한 번으로 짧아 락이 오래 안 잡혀서, 락 기술 선택이 성능에 큰 영향이 없다.
  - Grafana 스크린샷은 생략(두 실행 다 ~15초로 짧아 pending 스파이크가 순간값으로만 잡힘 — 표로 대체, classq 부하 비교표 방식).
  - **다음**: ④한계 테스트(리허설 스택 `docker-compose.rehearsal.yml`) → AWS 배포 → AWS 재측정.

- **2026-09-04**: **한계 테스트 1차 실행 — 정합성·병목축 확정, 동시 인원 숫자는 로컬 보류. (`test-results.md` 4번)**
  - **리허설 스택 준비에 시간 많이 씀**: 이 PC RAM 16 GiB에 원안 예산(EC2 16g + RDS 8g = 24g)이 안 들어가 축소(합 ~7 GiB, `docker-compose.rehearsal.yml` 재조정). `~/.wslconfig` 신설(`memory=8GB` + `autoMemoryReclaim=dropcache` — 없으면 Docker VM이 빌드 캐시 4 GiB를 안 돌려줘 호스트가 0.6 GiB로 굶었음). 앱 컨테이너 `TZ=Asia/Seoul`(UTC라 seed openAt와 9h 어긋나 이벤트 안 열림), `JWT_ACCESS_EXPIRATION=4h`(30분이라 대화 중 토큰 만료 → false 401), nginx `depends_on: [app]`(upstream 해석 실패로 죽음). 다른 프로젝트 컨테이너(classq `app` 크래시루프 → 8080 점유, `mysql-container` → 3306) 정지 + restart 해제.
  - **신규 파일**: `docker-compose.capacity.yml`(nginx를 rate-limit 없는 `nginx.capacity.conf`로 교체), `CapacitySimulation.java`(계단식), `scripts/{run-capacity.ps1, seed-buyers-parallel.mjs, login-buyers.mjs}`(PowerShell seed가 3000 계정에 30분 걸려 Node 병렬로 대체), `src/gatling/resources/logback.xml`(KO 요청 DEBUG 덤프 OFF).
  - **1차(계단식) 버림**: 계정 1개당 이벤트 1건 제한 → 800 풀이 ~50초에 소진, 뒷 단계 빈 409. 건진 것: 동시 100명에서 HikariCP 풀(10) 포화, pending 49~80.
  - **버스트(1,500 완전 동시) 3회**: 설정·경로 바꿔가며 — 전부 **오버셀 0 / 5xx 0**. 프록시 통과분은 정상 처리.
  - **핵심 발견: "Connection refused"는 Docker Desktop 유저랜드 포트 프록시**(Windows↔WSL2). `:8080`(앱)이든 `:8081`(nginx)이든 ~500~760/1500 동일 거부, `accept-count` 100→2000 무효(764→677). **AWS엔 없음**(앱이 진짜 리눅스에서 포트 직접 바인딩, ALB/nginx 앞단). 버스트B(nginx 경유)가 앱 직결보다 더 통과시킴이 방증.
  - **병목축 = app CPU (2 vCPU cap, 매 버스트 197% 고정) → HikariCP/mysql**(pending 풀 크기 무관 ~160~190, mysql 1.5 vCPU가 실질 상한). → `aws-spec.md` C 병목축 = CPU 우선.
  - **동시 인원 한계 숫자는 로컬 localhost로 못 냄** — 프록시가 ~800~1,000에서 먼저 무너짐. → AWS 재측정 또는 Gatling-in-container(`test-plan.md` 4-4)로 확정. 스크린샷 없음(버스트는 시각적 스토리 없음, 표로 충분 — 분산락과 동일).
  - **스택 상태**: `docker compose ... down`(볼륨 유지 — 이벤트·계정 데이터 남음). 재기동 체크리스트 `test-plan.md` 4-5.

- **2026-09-04**: **① 세션 변경분 커밋 완료(계획대로 3분할, `61b8ab5`/`bbbd988`/`e7d428b`) → ② Gatling-in-container 재측정 완료(`test-results.md` 4-4).** `docker-compose.capacity.yml`에 `gatling` 서비스 신규(컨테이너 네트워크 안에서 `nginx:80`을 직접 쳐 Docker Desktop 포트 프록시를 우회, `profiles: ["gatling"]`로 평소엔 안 뜸).
  - **1차 시도(방법론 결함, 폐기)**: 이벤트만 새로 만들고 DB/Redis는 리셋 없이 1,500→2,000→1,750→800명 순으로 4회 실행 — 프록시 우회 자체는 성공(오버셀 0, Connection refused 0)했지만 **N과 서버측 P95가 반비례하는 이상값**이 나왔다. `reservation` 누적 행수(3,728→8,724)를 의심했으나 실측 데이터 용량이 InnoDB 버퍼풀 기본값(128MB) 안에 여유 있게 들어가(합 ~14MB) 반증됨 — **사용자가 "테스트마다 DB 지우고 하면 되잖아"로 문제 제기**, 근본 원인은 데이터 누적이 아니라 테스트 간 상태를 리셋하지 않은 것 자체였다.
  - **2차 시도(클린 재측정, 채택)**: 매 실행 전 `reservation`/`reservation_seat`/`outbox_events` TRUNCATE + 기존 테스트 이벤트/구역/좌석 DELETE + Redis `FLUSHALL`로 동일한 초기 상태를 만들고, N을 100→250→350→450→500으로 늘려가며 재측정(계정 6,041개는 리셋 대상 제외 — stateless AccessToken이라 Redis flush와 무관). **결과: 100~450은 완만(서버 P95 6ms→132ms), 450→500 사이에서 절벽(132ms→8,990ms, 68배 폭증)** — 이 축소 스펙(app 2 vCPU)의 실제 한계는 ~460~480명 부근으로 추정. 오버셀은 전 구간 0(무너지는 방식이 안전함은 확인).
  - **사용자 문제 제기 2건 반영**: (1) 잘못된 1차 시도 수치는 문서에서 제거하고 원인·교훈만 남김. (2) classq `load-test.md`를 참고해 "한계 발견"에서 멈추지 말고 원인 진단(HikariCP/Tomcat/GC 중 무엇이 실질 상한인지) → 튜닝 → Before/After 재측정까지 하기로 함(classq는 HikariCP 대기·BCrypt cost·Redis/Kafka 구조 전환으로 P95를 최대 -99.95%까지 개선한 선례가 있음, `all/classq/.claude/docs/load-test.md` 참고). `test-results.md` 4-3의 "풀 크기가 레버가 아니다"(host 기반, 1,500명대 측정) 결론은 절벽 지점 자체를 못 본 상태에서 나온 것이라 재검증 필요로 명시.
  - `test-plan.md` 4-4(실행 완료로 갱신, DB 리셋 절차 필수 항목으로 반영)·`test-results.md` 4-4에 반영 완료.

- **2026-09-05**: **한계 테스트 원인 진단 + 캐싱 튜닝 Before/After 완료 (`test-results.md` 4-5).** 4-4의 절벽(450→500)을 그대로 재현하려 했으나 재현 절차가 기록에 안 남아있어 3번의 방법론 오류(① 이벤트를 N에 맞춰 작게 만들어 부하가 안 생김 ② 매 단계 15,000석 구역을 통째로 재생성해 그 자체가 부하가 됨 ③ 단계 간 Redis FLUSHALL이 `SeatStatusRebuildService`의 재구성 락 경합을 매번 유발해 진짜 부하가 아닌 미니 장애가 섞임)를 겪은 뒤, 리셋 후 재구성 마커를 직접 미리 심어 락 경합을 없앤 절차로 최종 측정값을 확보했다(사용자와 함께 각 단계마다 원인 확인 후 다음 시도로 진행, 3번째 실패 시점엔 AskUserQuestion으로 계속 시도할지 확인받음). **정확한 450→500 절벽 모양 재현에는 실패했지만, 오류 없는 새 기준값(N=100부터 이미 저하)을 확보해 이걸 공식 기준선으로 채택**하기로 사용자 확인 완료 — 4-4 원본 수치는 문서에 그대로 남기되 이후 진단/튜닝은 이 절 기준.
  - **원인 진단**: HikariCP 커넥션 풀(최대 10개)이 가장 먼저 병목이 된다 — N=100에서 이미 Tomcat 스레드는 60% 유휴(79/200)인데 DB 커넥션 대기는 67건. N=250부터 Tomcat도 포화(200/200)되지만 이건 원인이 아니라 결과(커넥션 대기 요청들이 스레드를 계속 붙잡음). GC 정지시간은 전체의 5% 미만이라 무관. `application.properties`에 `server.tomcat.mbeanregistry.enabled=true` 추가해야 `tomcat_threads_busy_threads` 등이 노출된다는 것도 이번에 발견(기본값 false라 이 지표가 아예 안 나가고 있었음).
  - **구체적 낭비 지점 특정**: `GET /seats`가 호출될 때마다 등록 후 절대 안 바뀌는 좌석 배치도(최대 15,000행)를 매번 DB에서 다시 SELECT하고 있었다.
  - **튜닝(사용자 지적 반영)**: 사용자가 "원인이 캐싱 문제면 근본적으로 코드를 고쳐야 하지 않냐"고 지적 — `SeatCatalogRepository`(신규, `domain/seat/repository`) 추가, 이벤트 등록 시 좌석 배치도를 DB에서 딱 한 번 읽어 Redis(`seat_catalog:{sectionId}`, List)에 캐싱하고 `GET /seats`는 이후 캐시만 읽는다(캐시 미스 시 DB 폴백 + 재채움, `SeatStatusRebuildService`와 같은 자가복구 패턴). 이벤트 전체교체/삭제 시 캐시도 함께 정리. 기존 자동 테스트 17개 전부 통과.
  - **Before/After 결과**: N=100~450 구간 seat-hold P95 32~46% 개선(2,098→1,123ms @N=100 등). **다만 N=500은 여전히 9초대(-13%뿐)**, HikariCP pending도 N=100에서 오히려 67→91로 늘었다 — 좌석 조회가 빨라지며 사용자들이 좌석 잡기/결제 요청(둘 다 DB 커넥션 사용) 단계에 더 촘촘히 몰렸기 때문(병목 하나를 없앴지만 DB 커넥션 풀 자체가 여전히 상한). "부분 개선, 근본 해결 아님"이 정직한 결론.
  - **다음 후보(미실행)**: HikariCP 풀 크기(10→20) 추가 튜닝 테스트. 스크린샷: `.claude/screenshots/tests/capacity-limit/`(Before 2026-09-05 19:42:00~19:49:45 KST, After 20:28:22~20:34:25 KST).
  - **참고(도구 버그, 이번에 발견)**: 이 PC의 Git Bash `date` 명령이 `TZ=Asia/Seoul`을 인식 못 해 UTC를 "GMT"로 잘못 표시(9시간 밀림) — 호스트 기본 로컬 시간대가 이미 KST라 `TZ=` 없이 `date -d @epoch`를 쓰는 게 정답. 스크린샷 시간 범위를 처음에 9시간 잘못 안내했다가 사용자가 지적해 정정함.

- **2026-09-05 (같은 세션 이어서)**: **트랜잭션 범위 축소 시도 — 코드는 채택, 로컬 측정은 새 병목(Redis)으로 중단(`test-results.md` 4-6).** 사용자가 "캐싱 말고 근본적으로 코드를 더 손볼 곳 없냐"고 질문 → `SeatService.hold()`/`findActiveHold()`와 `ReservationService.requestPayment()`가 메서드 전체를 `@Transactional`로 감싸 DB와 무관한 Redis 호출(입장 토큰 검증, 그룹 홀드 락 획득 — 경합 시 최대 3초 대기)까지 DB 커넥션을 붙잡고 있던 걸 발견. DB가 실제 필요한 구간만 남기고 나머지를 트랜잭션 밖으로 뺌(`requestPayment`는 신규 `TransactionTemplate` 빈으로 쓰기 구간만 프로그래밍 방식 트랜잭션으로 좁힘, 좌석 만료 스케줄 갱신은 원자성 때문에 그대로 안에 둠).
  - **사용자가 "위험 없다"는 제 말에 문제 제기** → 재확인 후 "위험 0을 장담할 수 없다, 테스트로 검증해야 확신 가능"으로 정정. 실제로 `gradlew test` 첫 실행에서 17개 중 12개가 `LazyInitializationException`으로 실패(트랜잭션이 짧아지며 `seat.getSection()` 지연 로딩 시점에 세션이 이미 닫혀있었음) — `SeatRepository`에 `JOIN FETCH` 쿼리 추가로 해결, 17개 전부 재통과. **미리 경고했던 위험이 실제로 발생하고 잡힌 사례.**
  - **Before/After 재측정 결과는 개선이 아니라 새 병목 노출**: N=350~500에서 실패(KO)가 새로 발생(N=500 878건) — 원인은 `Redis command timed out after 2 second(s)`. DB 병목을 없애자 요청이 훨씬 촘촘하게 Redis로 몰렸는데, 리허설 환경이 Redis를 0.5 vCPU로 작게 제한해둔 게 새 병목이 됨. Redis를 1.0 vCPU로 올려 재측정했지만 KO가 안 줄어(오히려 소폭 증가, 910건) — Redis가 싱글 스레드라 CPU를 늘려도 처리량이 비례해 안 늘어난다는 뜻. **사용자가 "시간 없다, 테스트하고 개선 다 해야 한다"고 확인** → 여기서 로컬 튜닝은 중단하기로 결정, Redis 설정 원복(0.5 vCPU).
  - **최종 결론**: 트랜잭션 범위 축소 코드는 구조적으로 옳은 개선이라 그대로 채택하되(테스트로 안전성 검증 완료), 그 효과는 로컬 리허설 규모(Redis 0.5~1 vCPU)에서는 측정 불가 — AWS 재측정(관리형 Redis 또는 더 넉넉한 vCPU)에서 다시 봐야 진짜 효과를 알 수 있음.
  - 스크린샷은 이번 라운드(트랜잭션 축소)는 실패(KO) 위주라 캡처하지 않음 — Before/After(캐싱) 세트만 `capacity-limit/`에 유지.

## 다음 작업

### ⏭️ 이어서 할 것 (2026-09-05 — **오늘 안에 ③~⑥ 전부 끝내는 게 목표**, 사용자 확정)

**현재 위치**: 커밋 + Gatling-in-container 재측정(클린) 완료 — 이 축소 스펙(2 vCPU)의 절벽 지점(450~500명)까지 확정. **오늘(09-05) 순서 = ③ 원인 진단 → ④ 튜닝 적용 + Before/After 재측정 → ⑤ AWS 배포 → ⑥ AWS 재측정.** 문서(캡쳐·표)는 각 단계 끝날 때마다 바로 정리(막판 몰아서 하지 않음). 포트폴리오 PDF(⑦)는 그다음.

**안전판(그대로 유지)**: ⑤ AWS 배포가 원래 추정 반나절~1~2일짜리 리스크 있는 단계라, 하루 안에 못 끝내면 "AWS 예측표(`aws-spec.md` D)까지만 하고 실배포는 다음으로 후퇴" — 이미 문서화돼 있음.

**스택 상태**: 컨테이너 기동 중(mysql/redis/app 등, 이벤트 1~12는 리셋됨 — 계정 6,041개만 남음). `docker ps`로 확인 후 필요시 `test-plan.md` 4-5 체크리스트로 재기동.

#### ③ 원인 진단 (다음 클린 버스트에서 절벽 지점 근처를 직접 관찰)
- 450~500 사이(예: 460/480/500)에서 재현하며 이번엔 **HikariCP active/pending, Tomcat busy thread, app GC 로그**를 Grafana/Actuator로 직접 관찰 — 어떤 자원이 먼저 포화되는지 특정
- 후보: HikariCP 풀(기본 10) 고갈 / Tomcat accept-count·max-threads / app GC stop-the-world / mysql 자체 CPU
- **재검증 필요**: 기존 "풀 크기가 레버가 아니다"(4-3, host 기반 1,500명대) 결론 — 그때는 절벽을 지난 지점만 봤으므로, 이번엔 절벽 근처에서 직접 관찰해야 함

#### ④ 튜닝 적용 + Before/After 재측정
- ③에서 특정된 병목에 맞는 조치 1~2개 적용(예: HikariCP 풀 크기 조정, Tomcat 스레드/큐 조정 등 — 확정된 원인에 따라 다름)
- 같은 클린 방법론(DB/Redis 리셋 + N 단계별 증가)으로 튜닝 전/후 절벽 지점을 재측정해 개선율 확인 (classq 스타일: "원인 → 조치 → Before/After 수치" 구조로 `test-results.md`/`portfolio.md`에 기록)

#### ⑤ AWS 배포 (test-plan.md 참고, ~1~2일, 리스크) — 순서상 ③④ 다음
AWS 설정은 **EC2 + RDS만이 아님**. 순서:
- **접근**: IAM 사용자(또는 루트 콘솔), 키페어(.pem) — SSH용
- **네트워크**(제일 자주 막힘): 기본 VPC + 보안그룹 2개
  - EC2용: 인바운드 22(내 IP), 8080/80(테스트), 3000·9090(Grafana·Prometheus, 내 IP)
  - RDS용: 인바운드 3306 = **EC2 보안그룹에서만**
- **EC2**: `m6i.xlarge`(AL2023 또는 Ubuntu), EBS **40~50GB**(Kafka+이미지가 작은 디스크 꽉 채움), Elastic IP(선택), 접속 후 **Docker + Compose + git 설치**
- **RDS**(Debezium 때문에 까다로움): `db.m6i.large`/MySQL 8.0/gp3 20~50GB/Multi-AZ off/퍼블릭 off
  - **파라미터 그룹**: `binlog_format=ROW`, `binlog_row_image=FULL`
  - 백업 활성화(binlog 켜짐) + `binlog retention hours` 설정
  - Debezium용 유저에 `REPLICATION SLAVE`, `REPLICATION CLIENT` 권한
  - 초기 DB `ticketrush`
- **앱 배포**(Claude가 파일 준비, 사용자가 EC2에서 실행):
  - `docker-compose.aws.yml` 신규 — MySQL 컨테이너 빼고 RDS 엔드포인트, 나머지(Redis/Kafka/Connect/Nginx/Prometheus/Grafana) 컨테이너 유지, 앱은 Dockerfile 빌드
  - **앱 env에 반드시**: `TZ=Asia/Seoul`(EC2 기본 UTC → openAt 9h 어긋남), `HIKARI_POOL`·`TOMCAT_ACCEPT`(한계 테스트에서 나온 튜닝, `aws-spec.md` C-1 — AWS 재측정에서 10 vs 30 비교). `JWT_ACCESS_EXPIRATION`은 운영 기본(30분)으로 되돌림(4h는 리허설 전용).
  - nginx는 `nginx.rehearsal.conf` 계열(upstream `app:8080`) 사용 — 단, AWS는 rate limit 유지(리허설의 `capacity.conf`는 로컬 테스트 전용)
  - `.env`(RDS 접속정보 + JWT/PortOne 시크릿)
  - Debezium 커넥터 재설정(MySQL host = RDS 엔드포인트)
- **자주 터지는 것**: RDS binlog/replication 권한(1순위), EC2↔RDS 보안그룹, EC2 디스크·메모리(16GB에 스택 전부)
- **역할**: Claude = `docker-compose.aws.yml` + `.env` 템플릿 + 배포 스크립트 + 단계별 체크리스트 문서 / 사용자 = AWS 콘솔 클릭 + EC2 SSH 명령 실행
- **안전판**: AWS가 하루 넘게 꼬이면 "aws-spec.md 예측표(D)까지만 + 실배포는 마감 후"로 후퇴

#### ⑥ AWS 재측정 (test-plan.md 5번, ~반나절)
- AWS에서 골든패스 부하(300 동시) + 한계 테스트 **다시** (카오스는 로컬만, 재측정 안 함)
- `aws-spec.md` D(예측) vs E(실측) 대조, `test-results.md` 5번

#### ⑦ 포트폴리오 문서화 (~1일)
- `test-results.md` + `portfolio.md`(소재 1~7) + `decisions.md` + `aws-spec.md` + 스크린샷(`a1-redis-down/`, `a2-kafka-down/`) → Notion → PDF (classq `정찬혁_ClassQ_포트폴리오.pdf` 참고)
- 파일별 역할은 사용자와 이미 정리됨 (portfolio.md가 본체, test-results.md가 수치 출처)

#### 5. 마무리 (여유 시)
- `architecture.md` "인프라 구성" 표 (배포 후 채움)
- 남은 문서 정리, 가벼운 리팩토링만 (새 기능 금지)

---

**진행 순서: 카오스 테스트 → 부하 테스트(분산락 최종 채택 포함) → AWS 배포**(decisions.md 13번). 카오스/부하 둘 다 로컬 Docker Compose 대상.

**2026-08-28 세션에서 정리된 것(사용자 확인 완료):**
- **카오스 중 부하 발생 = Gatling으로 통일**(별도 스크립트 안 만듦). 부하테스트에서 어차피 필요한 Gatling 시나리오를 먼저 작성해 카오스에도 재사용한다(decisions.md 8번 반영).
- **분산락 채택 기준에 P99·락 실패 응답 형태 추가**(멘토 피드백, decisions.md 2번 반영). 처리량만 보지 않고 그룹 홀드 P99를 동등 지표로, 락 실패가 "즉시냐 대기 후냐"도 관찰. → 벤치마크 전에 **DB 비관적 락에 lock timeout을 걸고 실패를 `GROUP_HOLD_LOCK_TIMEOUT`으로 매핑하는 선행 수정**이 필요(현재는 MySQL 기본 50초 블로킹 후 500).
- **AWS 인스턴스: m계열 방향**(classq는 c계열이지만 우리는 EC2 한 대에 스택 전부 공존 → RAM도 병목). 잠정 `m6i.xlarge` + RDS는 락 결과에 따라. 상세·확정은 `.claude/docs/aws-spec.md`(신규, A·B 작성 완료 / C·D는 로컬 부하테스트 후 / E는 AWS 배포 후).
- **실제 배포까지 한다(2026-08-28 확정, 사용자 확인 완료)**: classq는 `aws-spec.md`를 예측까지만 쓰고 배포는 안 했다 — TicketRush는 EC2+RDS에 실제로 올리고 AWS에서 Gatling을 다시 돌려 예측표(D·E)를 실측으로 검증한다. "같은 프로젝트 반복"으로 안 보이게 하려는 것(decisions.md 10번). 카오스 테스트는 로컬만, AWS 재측정은 부하 테스트만.

**카오스 테스트 준비(Phase 1) — 2026-08-28 완료, 스모크 검증까지:**
1. ✅ `application.properties` — `management.metrics.distribution.percentiles-histogram.http.server.requests=true` + `slo` 버킷(200ms~10s). Micrometer 기본은 count/sum/max만이라 이게 없으면 P99가 안 나온다.
2. ✅ Grafana 대시보드 provisioning — `grafana/dashboards/ticketrush.json`(uid `ticketrush-load`, 4패널: 응답시간 P50/P95/P99 · 상태코드별 요청/에러율 · Kafka Consumer lag · HikariCP) + `uri` 템플릿 변수. `grafana/provisioning/dashboards/dashboard.yml` 파일 프로바이더, `docker-compose.yml`에 `./grafana/dashboards` 마운트. 4개 패널 쿼리가 실제 데이터를 반환하는 것까지 확인(P99≈90ms 등).
3. ✅ Gatling — `io.gatling.gradle` 플러그인 `3.15.1.3`(3.13.5는 Gradle 9와 비호환 — `reportsDir` 에러). `src/gatling/java/simulation/GoldenPathSimulation.java`(가입→로그인→대기열 진입/폴링→좌석 조회→홀드→결제 요청). 좌석 ID는 시나리오가 `GET /seats` 응답에서 직접 뽑는다(seed가 DB를 안 건드려도 되도록). `-Dgroup.hold.ratio`로 그룹 홀드 비중 조절(벤치마크는 1.0).
   - `scripts/seed-load-test.ps1`(순수 REST — ORGANIZER 승인 + 이벤트/SEATED 구역 등록 + openAt를 미래로 두고 대기. **docker/DB 접근 없음** — 처음엔 `docker exec mysql`을 썼다가 Windows PowerShell에 docker CLI가 없어 "앱 선택" 팝업이 떠서 제거함).
   - `scripts/run-gatling.ps1`(래퍼 — PowerShell이 인라인 `-Dfoo.bar=baz`를 깨먹어서 `@args` splat 필요).
   - **스모크 검증**: 5·8 유저로 전체 골든 패스 실행 → KO 0, 모든 스텝(signup/login/queue/seat-list/seat-hold/payment-request) 통과 확인.
4. ✅ Pumba — `scripts/chaos-redis.ps1`·`scripts/chaos-kafka.ps1`(`gaiaadm/pumba:0.11.6` `docker run`, `stop --restart --duration`). redis 대상으로 실제 stop→15s→restart→PING 복구까지 확인. compose 상시 서비스로 넣지 않고 스크립트로 온디맨드 실행(재현성).

**테스트 계획 문서화(2026-08-28) — 실행 전 완료:**
- `.claude/docs/test-plan.md` 신규 — 목표 수치(기준선, 각 값에 근거)·카오스 2 시나리오·분산락 벤치마크·**한계 테스트**·절차·합격 기준을 실행 전에 못박음. 멘토 피드백 2건 반영: ① 목표 수치를 미리 적어두지 않으면 결과를 판단할 근거가 없음 → 1번에 정합성/성능/복구 기준선. ② 처리량뿐 아니라 P99·락 실패 형태 → 3번 판정 규칙. 추가로 "몇 명까지 버티나" 한계 테스트(4번)가 그동안 빠져 있던 걸 넣음(classq `StressTestSimulation` 대응).
- `.claude/docs/test-results.md` 신규 — 실측값 단일 출처(전부 "(대기)" 상태). `portfolio.md`·`aws-spec.md` D·E가 여기서 숫자를 끌어다 씀.
- 목표 수치(사용자 확인 완료): 오버셀 0(절대) / 동시 300명 / P95 좌석조회<1s·홀드~결제<2s / **P99 그룹홀드<3s** / 에러율<1%(경합 409 제외) / Redis 복구<30s / Kafka lag 0 도달<60s. 근거는 test-plan.md 1번.

**Phase 2 (진행 중) — 실행:** test-plan.md 2번 카오스 2개(**①Redis A-1 / ②Kafka A-2**) + **3번 분산락 벤치마크(→ Redisson 채택) 모두 완료 2026-09-03** → **4번 한계 테스트(리허설 스택 `docker-compose.rehearsal.yml`) — 다음** → AWS 배포 → AWS 재측정. 절차·합격 기준은 전부 test-plan.md에 있음.

**일정(2026-08-27 확정)**: 카오스/부하테스트/AWS 배포를 4주차로 넘기지 않고 **3주차 안(~08-30)에 완결 목표**. AWS 계정 가입은 완료(IAM 키/CLI 설정 여부는 미확인).

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
| 2주차 | 08-17 ~ 08-23 | 좌석 상태 모델(단일 좌석 흐름), 홀드 TTL/만료 처리, Saga 상태머신, 분산락 벤치마크(Redisson RLock/DB 비관적 락 **두 방식 구현** — 어느 쪽을 채택할지 **확정**은 3주차 부하테스트로 이월) |
| 3주차 | 08-24 ~ 08-30 | Kafka exactly-once, 결제 연동(**예약 취소 API 포함**), Nginx 설정 → **카오스 테스트 + 부하테스트(분산락 최종 채택 포함)** → **AWS 배포**(EC2 + Docker Compose + RDS, decisions.md 10번 — EKS/ElastiCache/MSK/CloudWatch 미도입 확정, 2026-08-27). **프론트엔드는 2026-08-23에 이미 완료.** |
| 4주차 | 08-31 ~ 09-09 | 카오스 테스트·부하테스트 마무리, 결과 기반 간단한 리팩토링만. 새 기능/인프라 변경 없음 |

**2026-08-16 (1주차 마지막 날) 점검에서 발견/확정된 사항**: decisions.md 13번 구현순서와 주차 일정을 대조한 결과, "이벤트/구역/좌석 등록 API"와 "ADMIN 승인 API"가 설계(api-design.md 2·6번)는 되어 있었지만 구현순서/주차 일정 어디에도 명시적으로 안 들어가 있던 걸 발견 — ADMIN 승인이 없으면 ORGANIZER가 로그인을 못해 이벤트 등록 자체가 막히고, 이벤트/좌석 데이터가 없으면 2주차 좌석 상태 모델 작업을 검증할 수 없어 순서상 1주차(인증/인가 다음)에 추가함(사용자 확인 완료). 예약 취소 API는 별도 항목 없이 3주차 결제 연동에 포함(Saga 보상 로직 재사용). 이 참에 미확정이었던 **Refresh Token 저장 방식도 확정**: httpOnly Cookie로 전달 + Redis(`refresh_token:{accountId}`)에 저장해 로그아웃/재로그인 시 무효화, 다중 기기 로그인은 미지원(계정당 1개 세션). decisions.md 3번, redis-design.md 9번, db-schema.md, api-design.md 전부 반영 완료.

**2026-08-23**: 중간 보고서(`REPORT_DRAFT.md`, 제출 기한 2026-08-25) 작성 중 카오스(장애 주입) 테스트 도구를 **Pumba로 확정**(decisions.md 8번 반영) — Docker 컨테이너를 직접 대상으로 해 지금 쓰는 docker-compose(MySQL/Redis/Kafka)에 코드 수정 없이 바로 적용 가능하고, 컨테이너 kill/stop뿐 아니라 네트워크 지연·패킷 유실까지 다룰 수 있어 decisions.md 8번의 "일정 남으면 네트워크 파티션 확장" 시나리오와도 같은 도구로 이어진다. Toxiproxy(더 정교하지만 앱 연결 설정을 프록시 경유로 바꿔야 함)·수동 `docker stop`(가장 단순하지만 재현성·중간 상태 표현력이 떨어짐) 두 대안을 검토 후 선택(사용자 확인 완료). 실제 도입은 3주차 카오스 테스트 착수 시점.

**2026-08-23**: **데모용 프론트엔드(React/Vite/TypeScript) 골든 패스 완료 — 원래 3주차 계획을 오늘로 앞당김(사용자 확인 완료)**. 보고서 작성 중 "curl/스크립트 캡처 vs 지금 프론트 만들기"를 논의하다, 총 작업량은 언제 하든 같고 오히려 지금 끝내면 3주차 중간에 짬 내야 하는 부담이 없어진다는 점에서 오늘 진행하기로 결정함.
- **백엔드**: `SecurityConfig`에 CORS 설정 추가(`corsConfigurationSource` 빈, `frontend.origin` 프로퍼티 — 기본값 `http://localhost:5173`, `.env`의 `FRONTEND_ORIGIN`으로 재정의 가능). 지금까지 프론트엔드가 없어 CORS 자체가 설정된 적이 없었다 — Refresh Token이 httpOnly Cookie라 `allowCredentials=true`가 필수이고, 그러면 허용 오리진에 `*`를 못 써서 프론트 오리진을 명시해야 했다.
- **프론트엔드**: `npm create vite`(react-ts 템플릿) + `react-router-dom`. 만든 것: `api/client.ts`(Access Token은 메모리에만 보관, `INVALID_TOKEN` 401 응답 시 `/auth/refresh`로 자동 재발급 후 원요청 1회 재시도), `api/{auth,events,queue,seats,reservations}.ts`(api-design.md 스키마와 1:1 대응하는 타입 포함), `AuthContext`(새로고침 시 세션 자동 복구), 화면 6개(`SignupPage`/`LoginPage`/`EventListPage`/`EventDetailPage`/`QueuePage`/`SeatHoldPage`). 입장 토큰은 이벤트별로 sessionStorage에 저장.
- **범위**: 회원가입/로그인 → 이벤트 목록/상세 → 대기열 진입/순번 폴링(자동 통과 시 좌석 화면으로 이동) → 좌석 선택·홀드(지정석 그리드/스탠딩 수량 모두 지원) → 결제 요청까지. **결제 확정(`PAYMENT_CONFIRMED`)은 포함하지 않는다** — 백엔드에 `POST /api/v1/reservations`(결제 요청)까지만 구현돼 있고 실제 PG 웹훅 연동은 3주차라, 결제 요청 완료 화면에서 "3주차에 이어붙일 예정"이라는 안내만 보여주고 끝난다. 관리자 화면 등은 원래 계획대로 범위 밖.
- **검증**: `npx tsc -b`/`oxlint` 통과. Docker Compose(MySQL/Redis/Kafka) + 백엔드(`gradlew.bat bootRun`) + 프론트(`npm run dev`)를 모두 띄운 뒤, curl로 프론트가 실제로 보내는 것과 동일한 요청(회원가입·로그인·이벤트 조회·대기열 진입/폴링·좌석 조회/홀드·결제 요청)을 백엔드에 직접 쏴서 계약이 맞는지 확인했고, `Origin: http://localhost:5173` 헤더를 붙여 CORS+쿠키 흐름(로그인 시 `Set-Cookie`, `/auth/refresh`가 그 쿠키를 정상적으로 읽는지)까지 확인했다. 이후 **사용자가 직접 브라우저(`localhost:5173`)에서 새 계정으로 회원가입부터 결제 요청까지 전체 흐름을 클릭해 최종 확인**(예약 번호 27, `PAYMENT_REQUESTED`) — Claude in Chrome 확장을 설치하지 않아 자동화된 브라우저 조작은 이번엔 진행하지 않았다.

- **2026-08-27**: **인프라 최종 확정 + 순서 정정.** 인프라는 EKS/ElastiCache/MSK/CloudWatch를 전부 도입하지 않기로 확정(decisions.md 10번) — "관리형 서비스를 써봤다"는 신입 채용에서 변별력이 낮고, 이 프로젝트의 진짜 강한 소재는 분산락 두 방식을 직접 실측 비교한 것(decisions.md 2번)이라 그쪽에 시간을 쓰기로 함(사용자 확인 완료). AWS는 EC2 + Docker Compose(로컬과 동일 구성) + RDS(MySQL)만 그대로 쓴다. 또한 3주차 일정 순서를 decisions.md 13번 원안대로 정정 — **카오스 테스트·부하테스트가 AWS 배포보다 먼저**다(로컬에서 정합성/성능을 검증한 뒤 배포해야지 그 반대는 순서가 거꾸로임, 이전에 progress.md 표가 잘못 뒤집혀 있었음). 카오스/부하테스트는 로컬 Docker Compose 대상으로 진행한다.

- **2026-08-27**: **Kafka exactly-once + 결제 연동 완료 (3주차 첫 번째·두 번째 항목).** 만든 것: `OutboxEvent` 엔티티/리포지토리(db-schema.md 7번), `ReservationService.markPaymentFailed`가 같은 트랜잭션에서 outbox INSERT, `PaymentFailedConsumer`(Kafka `@KafkaListener`, `ticketrush.reservation.events` 토픽 소비 → `releaseAfterFailure` 호출), `PaymentWebhookService`/`PaymentWebhookController`(`POST /api/v1/payments/webhook`, Standard Webhooks 서명 검증), `GET /reservations/me`·`GET /reservations/{id}`·`POST /reservations/{id}/cancel`, `scripts/register-outbox-connector.ps1`(Debezium Outbox Event Router SMT 커넥터 등록 자동화).
  - **Kafka Consumer 범위(사용자 확인 완료)**: outbox 이벤트는 `PAYMENT_FAILED` 전이에서만 기록하고, `PAYMENT_CONFIRMED`(정산/알림용) 쪽은 그 기능 자체가 여전히 보류 중이라 안 만든다 — decisions.md 6번에 반영. 결제 실패 시 좌석 반납을 Kafka Consumer가 트리거하게 만든 게, 애초에 `markPaymentFailed`/`releaseAfterFailure`를 2주차에 두 메서드로 나눠뒀던 이유 그 자체다.
  - **PortOne 웹훅 서명 검증은 구현 당시엔 실서명으로 확인한 적이 없었다** — Standard Webhooks 스펙(webhook-id/webhook-timestamp/webhook-signature + HMAC-SHA256)이라고 가정하고 구현했고(1주차 스모크테스트 로그의 헤더 이름 근거), `.env`에 로컬 테스트용 임시 시크릿을 넣어 자체 서명 생성/검증 왕복으로만 확인했었다. **→ 2026-08-27 같은 날 사용자가 포트원 콘솔에서 실제 웹훅 시크릿을 찾아 `.env`에 반영함(`PORTONE_WEBHOOK_SECRET=whsec_...`).** 다만 이것도 "설정값을 넣었다"이지 "실제 웹훅으로 검증했다"는 아직 아니다 — 포트원 콘솔의 "호출 테스트"는 서명 헤더 없이 오므로(1주차에 이미 확인) 그걸로는 검증이 안 되고, 실제 결제 이벤트가 와야 Standard Webhooks 가정이 맞는지 최종 확인된다. 이 재검증은 프론트 PG SDK 연동(아래 참고) 이후로 자연히 넘어간다.
  - **`pg_payment_id`의 실제 의미를 바로잡음**: db-schema.md에 "포트원이 발급"이라고 잘못 적혀 있던 걸, 실제로는 merchant(우리 서버)가 결제 요청 시점에 만들어 부여하는 값(`"TICKETRUSH-{reservationId}"`)이라는 걸로 정정(decisions.md 5번, db-schema.md 5번 반영). 프론트가 포트원 SDK 호출 시 이 값을 그대로 넘겨야 하는데, **프론트의 실제 PG SDK 연동(결제창 호출)은 이번 범위에 포함하지 않았다** — 골든 패스 데모는 여전히 결제 요청 화면에서 끝나고, 결제 확정까지 브라우저로 이어지는 건 다음 단계.
  - **디버깅 중 실제로 겪은 함정 2건(portfolio.md 소재 5로 정리)**: (1) Debezium이 스키마 변경 이벤트를 `topic.prefix`와 같은 이름의 토픽에 발행하려다 브로커의 `auto.create.topics.enable=false` 때문에 무한 재시도에 빠져 커넥터가 `RUNNING` 상태를 유지한 채로 아무것도 발행 못 하고 있었음 — `include.schema.changes=false` + 브로커 옵션 `true`로 해결. (2) Spring Boot 4부터 `spring-kafka` 라이브러리만 추가하면 `KafkaAutoConfiguration`이 전혀 안 붙는다(패키지가 `org.springframework.boot.kafka.autoconfigure`로 분리되어 `spring-boot-starter-kafka`가 별도로 필요, Boot 3까지의 관행과 다름) — 앱이 에러 없이 멀쩡히 기동됐는데도 Kafka 컨슈머 그룹 자체가 생성되지 않는 조용한 실패였다. `build.gradle` 의존성을 스타터로 교체해 해결.
  - **검증**: Node.js e2e 스크립트로 전체 흐름 확인 — 회원가입/승인/이벤트 등록 → 대기열 통과 → 좌석 홀드 → 결제 요청(`pgPaymentId` 발급 확인) → 웹훅 서명 위조 거절(401) → `Transaction.Paid` 웹훅 → `PAYMENT_CONFIRMED` → 별도 계정으로 두 번째 좌석 홀드/결제 요청 → `Transaction.Failed` 웹훅 → outbox INSERT → Debezium → Kafka → `PaymentFailedConsumer` → `SEAT_RELEASED`까지 자동 전이(좌석도 `AVAILABLE`로 복귀) → 확정 예약 취소(`PAYMENT_CONFIRMED → SEAT_RELEASED`, 좌석 복귀) → 재취소 거절(409) → `GET /reservations/me` 확인, 전 과정 통과. `docker exec ... kafka-consumer-groups --describe`로 컨슈머 그룹 LAG 0까지 직접 확인. `gradlew.bat test` 전체 통과(Kafka 컨슈머 빈이 있어도 테스트 컨텍스트 기동에 지장 없음 확인).
  - **다음으로 미룬 것**: 프론트 PG SDK 연동(결제창 호출), 카오스/부하테스트(로컬), 그 다음 AWS 배포.

- **2026-08-27**: **프론트엔드 "내 예약" 화면 추가 (같은 세션 이어서 진행).**
  - **프론트엔드**: `ReservationsPage`(`GET /reservations/me` 목록 + `PAYMENT_CONFIRMED`인 예약에만 취소 버튼) 신규 추가, 헤더 네비게이션에 "내 예약" 링크 추가. `SeatHoldPage`의 결제 요청 완료 화면을 정적 문구 대신 **`GET /reservations/{id}`를 2초 간격으로 폴링**해서 `PAYMENT_CONFIRMED`/`PAYMENT_FAILED`/`SEAT_RELEASED` 결과가 실제로 반영되도록 바꿈(웹훅이 오면 화면이 자동으로 갱신됨). `pgPaymentId` 필드도 타입에 반영. `npx tsc -b`/`oxlint`/`vite build` 전부 통과, 프론트가 보내는 것과 동일한 헤더(Origin 포함)로 `GET /reservations/me`·`POST .../cancel`을 직접 호출해 계약도 재확인했다. **다만 이 세션엔 브라우저 조작 도구가 없어 실제 클릭 테스트는 못 했다** — 사용자가 직접 `localhost:5173`에서 확인 필요(2026-08-23 프론트 완료 때와 동일한 제약).
  - **웹훅 시크릿 실제 값 반영**: 사용자가 포트원 콘솔에서 실제 웹훅 시크릿을 찾아 `.env`에 넣음. 백엔드를 재시작해 반영하고, 예전 로컬 테스트용 시크릿으로 서명한 웹훅이 이제 401로 거절되는 것까지 확인해 **실제 시크릿이 적용된 상태**임을 검증했다(실제 웹훅 이벤트로 서명 형식 자체가 맞는지는 여전히 미확인 — 위 항목 참고).
  - **다음으로 미룬 것**: 프론트 PG SDK 연동(결제창 호출), Nginx(코드는 작성됐으나 사용자가 직접 테스트 후 별도로 커밋할 예정 — 아래 항목 참고), 카오스/부하테스트, AWS 배포.

- **2026-08-27**: **Nginx 추가(로컬, 대기열 진입 API 앞단 Rate Limiting) — 코드 작성 및 자체 검증 완료, 커밋은 사용자가 직접 테스트 후 별도로 진행 예정.** `nginx/nginx.conf` + `docker-compose.yml`에 `nginx` 서비스 추가(포트 8081 → 컨테이너 80). decisions.md 4번 범위 그대로 — **대기열 진입 API(`POST /events/{id}/queue/entries`)만** `limit_req_zone`(5r/s, burst 10)으로 제한하고, 순번 폴링(GET .../me)이나 다른 API는 그대로 통과시킨다. 백엔드가 아직 컨테이너화되지 않아(Dockerfile 미착수) `host.docker.internal`로 호스트의 `gradlew bootRun` 프로세스를 그대로 가리킨다. rate/burst 수치는 다른 TTL류와 마찬가지로 placeholder — 3주차 Gatling 부하테스트에서 조정한다.
  - **검증(구현 중 직접 확인한 것)**: 대기열 진입 API에 연속 20회 요청 시 뒤쪽이 429로 거절되는 것 확인, 반면 이벤트 목록 조회(`GET /events`)와 순번 폴링(`GET .../queue/entries/me`)은 동일하게 연속 20회를 쏴도 전부 정상 응답(429 없음)인 것으로 스코프가 의도대로 좁게 걸렸는지 확인했다.
  - **다음으로 미룬 것**: 프론트 정적 파일을 Nginx가 서빙하는 것(AWS 배포 단계에서 진행, 지금은 Vite dev server 그대로), 사용자의 자체 테스트 및 커밋/푸시.

- **2026-08-27**: **Prometheus + Grafana 추가(로컬, 카오스/부하테스트 관찰용) — 코드 작성 및 자체 검증 완료.** 사용자가 "그라파나·프로메테우스도 해야 하지 않냐"고 지적해 그제야 착수 — decisions.md 10번(CloudWatch 배제)과는 다른 결정이다: CloudWatch는 AWS 관리형 서비스라 뺀 것이고, Prometheus/Grafana는 우리가 직접 Docker로 띄우는 것이라 그 배제 이유에 안 걸린다. **classq(참고 프로젝트)가 부하테스트 때 쓴 방식을 참고**하되(구조만 참고, 코드는 우리 스택에 맞게 새로 작성 — CLAUDE.md 협업 규칙) 사용자 확인(AskUserQuestion)으로 범위를 정함: (1) 카오스/부하테스트 직전에 **로컬로만** 추가(AWS 배포에는 미포함), (2) classq와 동일하게 API 응답시간/에러율·Kafka Consumer lag·DB 커넥션 풀(HikariCP)을 지켜본다.
  - **만든 것**: 백엔드에 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 추가, `management.endpoints.web.exposure.include=health,prometheus`로 `/actuator/prometheus` 개방. `prometheus/prometheus.yml`(1초 간격 스크랩, classq와 동일), `grafana/provisioning/datasources/prometheus.yml`(Prometheus 데이터소스 자동 등록). `docker-compose.yml`에 `prometheus`(9090)·`grafana`(3000) 서비스 추가, Nginx와 동일하게 `host.docker.internal`로 호스트의 `gradlew bootRun`을 가리킨다. Grafana는 `grafana_data` 볼륨을 붙여 카오스 테스트 중 타임라인에 남기는 annotation·패널 편집이 `docker compose down` 후에도 유지되게 했다(classq 참고).
  - **exporter 없이 백엔드 하나만 스크랩해도 3개 항목이 전부 잡히는 것을 직접 확인**: `/actuator/prometheus` 응답에서 `hikaricp_connections_*`(커넥션 풀), `kafka_consumer_fetch_manager_records_lag_max`(Consumer lag), `http_server_requests_seconds_*`(API 응답시간/상태코드) 메트릭이 별도 exporter 설정 없이 자동으로 노출되는 것을 확인했다 — Spring Boot가 HikariCP와 Kafka 클라이언트를 Micrometer에 자동으로 바인딩해주기 때문(classq의 `docker/prometheus.yml`도 실제로 스크랩 대상이 앱 하나뿐이라 같은 구조임을 확인하고 그대로 따름).
  - **버그 하나 발견해 수정**: `/actuator/prometheus`가 401을 반환했다 — `SecurityConfig`가 이 경로도 인증 대상으로 잡고 있었음. `.requestMatchers("/actuator/**").permitAll()` 추가로 해결(로컬 전용이라 인증 없이 열어둠 — 실배포 시엔 네트워크 레벨 차단이 더 표준적인 접근이라고 판단, decisions.md에 반영).
  - **검증**: `curl localhost:9090/api/v1/targets` → `"health":"up"`으로 스크랩 성공 확인. `curl -u admin:admin localhost:3000/api/datasources` → Prometheus 데이터소스가 자동으로 등록된 것 확인(Grafana 기본 계정 admin/admin).
  - **다음으로 미룬 것**: 실제 대시보드 패널 구성(카오스/부하테스트 시작 시점에 만드는 게 더 자연스러움 — provisioning 파일로 관리 예정).
  - **2026-08-28 재검증 후 커밋**: 인프라를 전부 띄우고 `gradlew clean build`(테스트 17개 포함) 통과 확인, 앱 기동 후 `/actuator/prometheus`의 3개 지표(`hikaricp_connections`·`http_server_requests_seconds`·`kafka_consumer_*`)·Prometheus 타깃 `up`·Grafana→Prometheus 데이터소스 헬스체크 OK·Nginx 진입 API 20연타 시 뒤쪽 429까지 재확인. 이 과정에서 `grafana_data` 볼륨과 `GF_SECURITY_ADMIN_PASSWORD`를 추가(classq 정렬). Nginx는 2026-08-27 자체 검증 상태 그대로 함께 커밋.

- **2026-08-27**: **일정 재확인 — 3주차 안에 카오스/부하테스트/AWS 배포까지 다 끝내기로 확정(사용자 확인 완료).** 원래 "4주차는 3주차 테스트 마무리 버퍼"로 설계돼 있었지만, 사용자가 4주차로 넘기지 않고 3주차(~08-30) 안에 완결하고 싶다고 확정함. 오늘 포함 4일(08-27~08-30) 안에 "1~3주차 흐름 복습 + 카오스 테스트 + 부하테스트(분산락 결정 포함) + AWS 배포"를 다 넣어야 해서 상당히 빠듯하다는 점을 사용자와 공유·확인했다(제안한 완화책: 복습은 별도 시간 잡지 말고 필요할 때 문서를 짧게 참고하는 식으로, 카오스 시나리오는 decisions.md 8번 최소 범위인 Redis/Kafka 2개만). **AWS 사전 준비(계정 가입)는 이미 완료된 상태** — 계정 준비 단계에서 막힐 위험은 없어짐, IAM 키/CLI 설정 여부는 아직 미확인.

- **2026-08-28**: **카오스 테스트 준비(Phase 1) 완료 — 문서 정리 + Gatling·Grafana 대시보드·Pumba 셋업, 스모크 검증까지.** 위 "다음 작업 > 카오스 테스트 준비(Phase 1)" 4개 항목 참고.
  - **문서(Phase 0)**: `decisions.md` 2번(분산락 채택 기준에 P99·락 실패 응답 형태 추가, 멘토 피드백)·8번(카오스 부하도 Gatling)·10번(EC2 m계열/RDS는 락 결과 의존), 신규 `.claude/docs/aws-spec.md`(A·B 작성, C·D·E는 부하테스트 후), `progress.md`·`CLAUDE.md`.
  - **코드/설정**: `application.properties`에 P99 히스토그램+SLO 버킷, `build.gradle`에 Gatling 플러그인, `src/gatling/java/simulation/GoldenPathSimulation.java`, `grafana/dashboards/ticketrush.json`+`grafana/provisioning/dashboards/dashboard.yml`+`docker-compose.yml` 대시보드 마운트, 스크립트 4종(`seed-load-test.ps1`·`run-gatling.ps1`·`chaos-redis.ps1`·`chaos-kafka.ps1`).
  - **검증**: Gatling 5·8 유저 스모크 → KO 0 전 스텝 통과. Grafana 대시보드 4패널 쿼리 실데이터 반환 확인. Pumba redis stop→restart→PING 복구 확인. `gatlingClasses` 컴파일 통과.
  - **다음(Phase 2)**: 두 카오스 시나리오 실제 실행. 그 전 또는 부하테스트 착수 시 **DB 비관적 락 timeout 선행 수정**(위 "시점이 정해진 결정 > 분산락 기술" 참고).

## 추후 결정 필요 (지금 작업에는 안 막힘)

### 구현 단계에서 확정 (db-schema.md / redis-design.md 작성 중 새로 식별된 항목)

- **결제 처리 타임아웃 수치**: PG 웹훅이 안 올 때 "타임아웃"으로 간주하는 대기 시간(홀드 TTL과는 별개 값). `hold` 키 TTL을 이 값으로 재설정해 감지한다(redis-design.md 4번) — 정합성 일관성 체크 중 새로 발견한 항목: 기존 설계(`PERSIST`)로는 PG가 웹훅을 끝내 안 보내는 타임아웃을 아무도 감지하지 못하는 구멍이 있어서 TTL 재설정 방식으로 수정함
- 홀드 TTL과 결제 처리 시간의 경합 처리: 결제 요청이 TTL 만료 시각 직전에 들어오는 경우의 원자성 보장 방식 (redis-design.md 4번 "미정 사항" 참고, Lua 스크립트로 "TTL 확인 + 재설정"을 원자적으로 묶는 방식 검토 예정)
- 스탠딩 예약의 `quantity`가 여러 장일 때 개별 티켓 단위 이력이 필요한지 (지금은 한 예약 행 = N장으로 묶음, db-schema.md 참고)
- **`PORTONE_WEBHOOK_SECRET` 발급 완료(2026-08-27)** — `.env`에 반영됨. 다만 **실제 결제 이벤트로 서명이 맞는지는 아직 확인 전**이다(콘솔 "호출 테스트"는 서명 헤더가 안 옴) — 프론트 PG SDK 연동 이후 실결제/실웹훅으로 재확인할 것.
- **프론트 PG SDK 연동(포트원 결제창 호출) 미착수**: 백엔드는 `pgPaymentId`를 발급하고 웹훅을 받을 준비가 됐지만, 프론트가 실제로 포트원 SDK(`requestPayment` 등)를 호출해 카드/카카오페이 결제창을 띄우는 부분은 아직 없다 — 지금 데모 프론트는 여전히 "결제 요청 완료" 화면에서 끝난다. 착수 시점 미정(3주차 후반 여유 있을 때 또는 남겨두고 보고서에 한계로 명시하는 방안 모두 가능, 사용자와 논의 필요).

### 시점이 정해진 결정 (해당 주차 되면 확정)

- **~~분산락 기술~~ → Redisson RLock 채택 확정 (2026-09-03)**. 벤치마크 결과: 성능 동등(처리량·Global P99), 유일 차이는 DB 락의 HikariCP pending 147(Redisson 0). 우리는 Redis가 이미 코어라 "20% 이내 → DB 락" tie-breaker의 근거가 안 맞음 + DB 락은 커넥션 고갈 리스크. 상세 `test-results.md` 3번·`decisions.md` 2번. 선행 수정(DB 락 3초 timeout + `GROUP_HOLD_LOCK_TIMEOUT` 매핑)은 커밋 `8570d91`.
- **~~대기열 이탈률 섞은 부하테스트 시나리오~~ → 실측 완료 (2026-09-05, `test-results.md` 5-1)**. `GoldenPathSimulation`에 `dropout.ratio` 파라미터 추가(진입만 하고 폴링 안 하는 이탈자 흉내). 실사용자 60명 고정, 이탈률 0/30/50%로 전체 진입 인원만 늘려 재보니(60/86/120명) 실사용자 평균 폴링 횟수가 1.00→1.81→1.83회로 최대 83% 증가 — 가설(이탈률이 실사용자 체감 대기를 늘린다) 확인됨. 오버셀 0으로 정합성 무관, 순수 UX 지표.
- **architecture.md "인프라 구성" 표 추가**: classq(`all/classq/.claude/docs/architecture.md`)처럼 인프라 구성 표를 별도로 추가하기로 확인됨. 인프라 도입 여부는 2026-08-27에 확정(decisions.md 10번, EKS/ElastiCache/MSK/CloudWatch 미도입)됐으니 실제 배포 단계에서 표를 채운다
- **AWS 인스턴스 스펙 확정**(`.claude/docs/aws-spec.md`, 2026-08-28 신규): 계열은 m계열로 방향 확정(A·B 섹션 작성 완료 — classq는 앱 전용 c계열이지만 우리는 EC2 한 대에 Boot+Redis+Kafka+Connect+Nginx 공존이라 RAM도 병목). 잠정 `m6i.xlarge` / RDS `db.m6i.large`(DB 락 채택 시 `db.r6i.large`). 실제 크기·성능 예측·SLO(C·D·E)는 **로컬 Gatling 부하테스트 실측 후** classq와 같은 방식으로 채운다 — 아래 "성능/처리량 목표치"도 그때 함께 닫힌다

### 여유 있을 때 아무 때나 결정 가능 (일정과 무관, decisions.md 11번에서 정리된 항목)

- 성능/처리량 목표치: Gatling 부하테스트 성공 기준(동시접속 N명, P99 응답시간 Xms 등) 미정
- Outbox 테이블 정리 정책: TTL/배치삭제 정책 — 운영 단계 진입 전 결정 필요
- **이벤트별 구매 한도(1인 1매/2매) 조직자 설정 기능 — 보류하기로 확정(사용자 확인 완료, 2026-08-19)**: 기술적으로는 단순한 추가(이벤트에 `maxTicketsPerAccount` 컬럼 하나, 좌석 홀드 검증 시 하드코딩된 2매 대신 이 값을 읽도록 변경 — 락/Redis 설계와는 무관)이지만, 이 프로젝트의 핵심(동시성 제어·오버셀 방지·부하/장애 테스트)과 무관한 일반 CRUD성 기능이라 `portfolio.md` 수록 기준에도 안 맞고, 지금은 2주차 분산락 벤치마크가 더 급함. 굳이 넣는다면 3주차 후반(결제 연동 끝나고 카오스/부하테스트 직전)이 그나마 안전한 시점 — 4주차(새 기능 금지 원칙)엔 절대 넣지 않는다.
- **알려진 프론트엔드 버그: 좌석 홀드 실패(`SEAT_ALREADY_HELD`) 후 좌석 배치도가 자동 갱신 안 됨(2026-08-27, 중간 보고서 리뷰 중 발견)**: `SeatHoldPage.tsx`의 `handleHold` catch 블록은 에러 메시지만 `setError`로 띄우고 `seats` 상태를 다시 안 불러온다. `seats`는 `selectSection` 호출 시점에만 갱신되므로, 방금 다른 사용자가 잡아간 좌석이 화면엔 계속 "선택 가능"으로 보여 같은 좌석을 또 클릭해 같은 에러가 반복될 수 있다. 진짜 최신 상태를 보려면 사용자가 구역을 다시 선택해야 한다(수동 새로고침 우회 경로는 있음 — 완전히 막힌 건 아님). 골든 패스(성공 흐름)만 만들기로 한 프론트 범위 결정(2026-08-23) 때문에 실패 흐름은 처음부터 논의된 적이 없었던 것— 의도적으로 미룬 게 아니라 빠뜨린 것. **고치는 법(예정)**: `handleHold`의 catch에서 `SEAT_ALREADY_HELD`일 때 `section`이 SEATED면 `selectSection(section)`을 재호출해 좌석 목록을 갱신. 지금은 문서화만 하고 나중에 여유 있을 때 수정하기로 함(사용자 확인 완료).
