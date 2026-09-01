# AWS 배포(EC2 + Docker Compose, decisions.md 10번)에서 쓸 이미지.
# 로컬 개발은 계속 gradlew bootRun을 쓴다 — 이 Dockerfile은 (1) 리소스 제한을 건 로컬 리허설
# (docker-compose.rehearsal.yml, 부하 테스트 4번 전용)과 (2) 실제 AWS 배포 두 곳에서만 쓰인다.

# ── 빌드 스테이지 ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src

RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# ── 런타임 스테이지 ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

# -XX:MaxRAMPercentage: 고정 -Xmx 대신 컨테이너 메모리 제한(docker-compose의 mem_limit) 대비
# 비율로 힙을 잡는다 — 리허설/AWS에서 컨테이너 제한값만 바꾸면 힙도 따라간다(JDK 21은
# UseContainerSupport가 기본 켜져 있어 cgroup 메모리 제한을 스스로 인식한다).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "app.jar"]
