# ---------------------------------------------------------------------------
# SportDay backend — Spring Boot 4.1 on Java 25 (target/sportday-backend-1.0.0.jar)
#
# Two stages:
#   build — Maven + JDK 25 compiles the jar *inside* the image. Nothing from the
#           host's target/ is ever copied in, so the image cannot be poisoned by
#           a stale or half-written local build.
#   run   — a JRE 25 image, no compiler and no Maven, running the fat jar.
#
# Tags are pinned rather than floating (see the report in docs/INSTALL.md):
#   maven:3.9.15-eclipse-temurin-25  — Maven 3.9.15 on Eclipse Temurin JDK 25
#   eclipse-temurin:25-jre           — Eclipse Temurin JRE 25
# ---------------------------------------------------------------------------

# ------------------------------- build stage -------------------------------
FROM maven:3.9.15-eclipse-temurin-25 AS build

WORKDIR /build

# The POM on its own first, so the dependency download is its own layer and is
# reused by every later build whose dependencies have not changed.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# Then the sources. src/test is copied too because the POM declares surefire;
# the jar is still packaged with -DskipTests (below).
COPY src ./src

# -DskipTests: the Docker build packages the artefact, it does not run the suite
# (that is what `mvn test` on the host / CI is for). Tests are *compiled* so a
# test that no longer compiles still fails the build.
RUN mvn -B -ntp clean package -DskipTests

# -------------------------------- run stage --------------------------------
FROM eclipse-temurin:25-jre AS runtime

# curl is used only for the container healthcheck below. The JRE image does not
# ship it, so it is installed explicitly and the apt lists are cleaned in the
# same layer.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# A non-root account. /app is owned by it so the app can write its season
# backups (app.backup.dir=backups/ is resolved relative to the working
# directory) without running as root.
RUN groupadd --system --gid 1001 sportday \
 && useradd --system --uid 1001 --gid sportday --create-home --home-dir /home/sportday sportday

WORKDIR /app
RUN mkdir -p /app/backups && chown -R sportday:sportday /app

# Only the fat jar crosses from the build stage.
COPY --from=build --chown=sportday:sportday /build/target/sportday-backend-1.0.0.jar /app/app.jar

USER sportday

EXPOSE 8080

# Every Spring property can be overridden at runtime by an environment variable
# spelled in the relaxed form — SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
# SPRING_DATASOURCE_PASSWORD, APP_JWT_SECRET, ... — which is how docker-compose.yml
# points this container at the `mysql` service instead of localhost:3307.
# Sensible non-secret defaults, so the image also runs on its own:
ENV SPRING_PROFILES_ACTIVE="" \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"

# The API docs endpoint is public; the actuator is not on the classpath, so this
# is the cheapest thing that proves the HTTP listener is actually serving.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
  CMD curl -fsS http://127.0.0.1:8080/v3/api-docs > /dev/null || exit 1

# JAVA_OPTS is deliberately unquoted so a value with several flags is split.
# exec keeps the JVM as PID 1, so SIGTERM reaches it and shutdown is graceful.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
