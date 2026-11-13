# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# 1) 빌드 단계
#    --platform=$BUILDPLATFORM 이므로 "빌드하는 머신"의 네이티브 아키텍처로 실행된다.
#    (GitHub x86_64 러너에서는 amd64, EC2 Graviton에서 직접 빌드하면 arm64)
#    JAR는 아키텍처와 무관하므로 QEMU 에뮬레이션 없이 빠르게 빌드된다.
# ---------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 의존성 레이어: pom.xml이 바뀌지 않으면 캐시를 재사용한다.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# 테스트는 CI(GitHub Actions의 mvn verify)에서 수행하므로 이미지 빌드에서는 생략한다.
RUN mvn -B -ntp -DskipTests package \
    && cp target/cogom-api-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# 2) 실행 단계
#    타깃 아키텍처(linux/arm64 등)의 JRE 이미지를 사용한다.
#    이 단계에는 RUN이 없어서 x86_64 러너에서도 에뮬레이션 없이 arm64 이미지를 만들 수 있다.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 변경 빈도가 낮은 레이어부터 복사해 배포 시 내려받는 양을 줄인다.
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./

# application.yml의 server.address가 127.0.0.1이라 컨테이너에서는 0.0.0.0으로 덮어쓴다.
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# root가 아닌 고정 UID로 실행한다.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
