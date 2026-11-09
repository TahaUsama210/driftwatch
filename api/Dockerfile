# syntax=docker/dockerfile:1.27
# Build context: api/

# ---- Build: compile with the Maven Wrapper and split the Boot jar into layers ----
FROM eclipse-temurin:25.0.4_7-jdk-alpine AS build
WORKDIR /workspace

COPY --chmod=0755 mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY src/ src/

# The cache mount keeps ~/.m2 (dependencies and the Maven distribution) across builds without
# baking it into a layer. Tests are skipped here because CI runs them with `./mvnw -B verify`.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/driftwatch-api-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime: JRE only, unprivileged ----
FROM eclipse-temurin:25.0.4_7-jre-alpine

# A fixed, non-root UID/GID; the process needs no Linux capabilities (it binds 8080, above 1024),
# so it runs unchanged with cap_drop: [ALL] and no-new-privileges.
RUN addgroup -S -g 10001 driftwatch \
 && adduser -S -D -H -u 10001 -G driftwatch -s /sbin/nologin driftwatch

WORKDIR /app
# Least to most frequently changing, so a code change only rebuilds the last layer.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# busybox wget ships with the Alpine base; compose runs the same readiness probe.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
