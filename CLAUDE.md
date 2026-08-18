# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(claude.ai/code)에게 제공하는 가이드입니다.

## 프로젝트 현황

TicketRush는 이제 막 뼈대만 생성된 Spring Boot 애플리케이션입니다 (Spring Boot 4.1.0, Java 21, Gradle). 저장소는 classq(`all/classq`)와 동일하게 `ticketrush-backend/`(Gradle 프로젝트 전체)와 `ticketrush-frontend/`(현재 빈 폴더, 프론트엔드 기술스택 미정) 폴더로 나뉘어 있고, 루트에는 `docker-compose.yml`/`CLAUDE.md`/`.claude/`만 있습니다. **1주차 구현 항목이 전부 완료된 상태**입니다 — `account`/`event`/`section`/`seat` 엔티티, 회원가입·로그인·재발급·로그아웃, JWT 발급·검증 필터, Refresh Token(httpOnly Cookie + Redis `refresh_token:{accountId}`), 공통 예외 처리, ADMIN 계정 자동 생성, ORGANIZER 승인 API, 이벤트 등록/전체교체/삭제/조회와 Redis `seat_status:{eventId}` 초기화, 대기열 진입/순번 폴링 조회(Redis Sorted Set `queue:{eventId}`)와 입장 토큰 Scheduler(Redis String `entry_token:{eventId}:{accountId}`, 오픈 전 이벤트는 건너뜀)가 동작합니다. 포트원(V2) 웹훅 수신도 임시 스모크테스트로 연결 확인까지 마쳤습니다(검증용 코드는 확인 후 제거함) — PG는 토스페이먼츠(카드)+카카오페이(간편결제) 2채널로 확정(decisions.md 5번). 다음은 2주차(좌석 상태 모델, 홀드 TTL/만료, Saga 상태머신, 분산락 벤치마크)이며, 이후 순서는 `decisions.md` 13번을 따릅니다. `.claude/docs/` 아래 설계 문서(`decisions.md`, `architecture.md`, `db-schema.md`, `redis-design.md`, `api-design.md`)는 1차 작성이 완료된 상태이고, 다음 단계는 구현 착수입니다(`decisions.md` 13번 구현 순서 참고). 로컬 개발/테스트용 `docker-compose.yml`(MySQL, Redis, Kafka(KRaft), Kafka Connect+Debezium)과 `application.properties`의 DB/Redis/Kafka 연결 설정은 작성 및 실제 기동 확인까지 완료했습니다(`docker compose up -d` → `gradlew.bat bootRun` 정상 연결 검증됨). `.claude/progress.md`는 문서화/구현 진행 상황과 추후 결정이 필요한 사항, 4주 일정을 추적합니다. 프로젝트가 채워짐에 따라 각 문서의 최신 내용을 확인하세요.

## 명령어

모든 명령어는 `ticketrush-backend/` 디렉토리에서 실행합니다. Gradle 래퍼(Windows에서는 `gradlew.bat`, POSIX 셸에서는 `./gradlew`)를 사용하세요 — 시스템에 설치된 Gradle에 의존하지 마세요.

- 빌드: `gradlew.bat build`
- 앱 실행: `gradlew.bat bootRun`
- 전체 테스트 실행: `gradlew.bat test`
- 특정 테스트 클래스 실행: `gradlew.bat test --tests "com.ticketrush.ticketrush.TicketRushApplicationTests"`
- 특정 테스트 메서드 실행: `gradlew.bat test --tests "com.ticketrush.ticketrush.TicketRushApplicationTests.methodName"`

## 아키텍처

- `ticketrush-backend/`: 기본 패키지 `com.ticketrush.ticketrush`, 루트 클래스는 `TicketRushApplication` (`ticketrush-backend/src/main/java/com/ticketrush/ticketrush/TicketRushApplication.java`).
- `ticketrush-frontend/`: 아직 빈 폴더 — 프론트엔드 기술 스택은 미정.
- **패키지 구조는 도메인별로 나눈다**(사용자 확인 완료). `domain/{account,event,queue,seat,reservation}` 각각을 `controller/dto/entity/repository/service` 레이어로 구성하고, 도메인과 무관한 공통 코드는 `global/{config,entity,exception,jwt}`에 둔다. Kafka producer/consumer나 scheduler처럼 특정 도메인에만 필요한 것은 해당 도메인 하위에 폴더를 추가한다. 계정 관련 코드는 API 경로가 `/api/v1/auth/*`여도 패키지는 DB 테이블명과 맞춘 `domain/account`에 둔다(ADMIN의 계정 승인처럼 "인증"이 아닌 계정 관리 기능도 함께 들어가기 때문).
- 의존성 스택 (`ticketrush-backend/build.gradle` 기준): Spring Web MVC, Spring Data JPA, Spring Security, Bean Validation, Lombok, MySQL 커넥터(런타임), Spring Boot DevTools(개발용).
- 영속성 대상은 `com.mysql:mysql-connector-j`를 통한 MySQL. `ticketrush-backend/src/main/resources/application.properties`에 `spring.datasource.*`/`spring.data.redis.*`/`spring.kafka.bootstrap-servers`가 `${ENV_VAR:localhost 기반 기본값}` 패턴으로 설정되어 있어, 로컬에서는 별도 환경변수 없이 `docker-compose.yml`만 띄우면 바로 연결된다(나중에 앱을 컨테이너화해도 환경변수 주입만으로 재사용 가능, classq 패턴 참고).
- **비밀값은 `ticketrush-backend/.env`에 두고** `spring.config.import=optional:file:.env[.properties]`로 읽는다(사용자 확인 완료). `.env`는 gitignore 대상이라 저장소에 없으므로, 새 환경에서는 직접 만들어야 한다 — 필요한 키는 `JWT_SECRET`(HS256 이상이라 32바이트 이상), `JWT_ACCESS_EXPIRATION`(ms), `JWT_REFRESH_EXPIRATION`(ms), `REFRESH_COOKIE_SECURE`(로컬은 `false` — https가 아니면 Secure 쿠키가 오가지 않는다), `ADMIN_EMAIL`, `ADMIN_PASSWORD`.
- **테이블은 `spring.jpa.hibernate.ddl-auto=update`로 엔티티에서 자동 생성**한다. 단 db-schema.md의 생성 컬럼(`reservation_seat.active_seat_id`)과 CHECK 제약은 이 방식으로 표현되지 않으므로, `reservation_seat`를 구현하는 시점에 명시적 스키마 관리(Flyway 등) 도입 여부를 다시 정해야 한다.
- **좌석 대량 생성만 JPA가 아니라 `JdbcTemplate` batch를 쓴다**(`SeatBulkInsertRepository`). `seat.id`가 AUTO_INCREMENT라 JPA(IDENTITY 전략)에서는 `hibernate.jdbc.batch_size`를 켜도 배치가 적용되지 않고, 구역 하나가 수천 좌석이 될 수 있기 때문이다. JDBC URL의 `rewriteBatchedStatements=true`와 짝이므로 둘 중 하나만 빠져도 배치가 무력화된다 — 좌석 생성 성능을 건드릴 때는 MySQL `Com_insert` 상태값으로 실제 INSERT 문 개수를 확인할 것.
- **Spring Boot 4는 Jackson 3을 쓴다** — `ObjectMapper`의 패키지가 `com.fasterxml.jackson.databind`가 아니라 `tools.jackson.databind`다. 같은 이유로 JJWT의 JSON 처리기도 Jackson 2를 요구하는 `jjwt-jackson` 대신 `jjwt-gson`을 쓴다.
- 로컬 개발 인프라는 프로젝트 루트 `docker-compose.yml`(MySQL/Redis/Kafka(KRaft, Zookeeper 없음)/Kafka Connect+Debezium 4개 서비스)로 제공된다. `docker compose up -d`로 기동하며, Kafka Connect에 실제 Debezium 커넥터(outbox_events 대상)를 등록하는 건 아직 안 했다 — `outbox_events` 테이블/엔티티가 코드에 생기는 3주차(Kafka exactly-once) 시점에 진행 예정. Nginx 설정과 앱 `Dockerfile`, 배포용 Docker Compose는 `decisions.md` 13번/3주차 일정 그대로 아직 착수 전이다.
- 테스트 스택은 메인 스택과 동일하게 `-test` 스타터 변형(`spring-boot-starter-data-jpa-test`, `-security-test`, `-validation-test`, `-webmvc-test`)으로 구성되며, `ticketrush-backend/build.gradle`에서 `useJUnitPlatform()`으로 설정되어 있습니다.

## 협업 방식

- 사용자가 명시적으로 말하지 않은 내용을 넘겨짚어 채우지 않는다. 예: 사용자가 "A 파일은 마지막에 업데이트할 것"이라고만 말했을 때, 언급하지 않은 B 파일도 같은 방식으로 처리할 것이라고 임의로 추측하지 않는다.
- 지시가 애매하거나, 여러 해석이 가능하거나, 잘못 판단하면 이후 작업에 영향을 줄 수 있는 상황에서는 임의로 결정하지 말고 먼저 사용자에게 질문해서 확인한 뒤 진행한다.
- 작업 중 참고할 문서가 필요하면 먼저 `TicketRush/.claude/` 하위 문서를 확인하고, 부족하면 `all/classq/.claude/docs/` 또는 `all/classq/.claude/`를 참고한다. 그래도 참고할 내용이 없으면 임의로 판단하지 말고 사용자에게 질문한다.
- 문서 작업을 진행하면서 `.claude/progress.md`(진행 상황·추후 결정 사항)와 이 `CLAUDE.md`(프로젝트 현황)가 최신 상태를 벗어나면, 별도 요청 없이도 계속 업데이트한다.
- `.claude/portfolio.md`는 프로젝트 종료 후 이력서/포트폴리오에 쓸 "문제와 해결" 소재를 모으는 문서다. 작업 중 **실제로 부딪혀서 측정하거나 고친 것**이 나오면 별도 요청 없이 여기에 추가하되, 그 문서 상단의 "수록 기준"을 반드시 지킨다 — 일반적인 CRUD 구현이나 "~를 사용했습니다" 수준의 내용은 넣지 않는다. **단, 성능 수치는 개발 중 값을 그때그때 적지 않는다**(사용자 확인 완료) — 3주차 후반 부하/장애 테스트와 모니터링을 거쳐 정식으로 측정·개선한 뒤에 채운다. 그 전까지는 "문제와 접근"까지만 기록한다.