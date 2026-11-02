# API image (build context: ./api). Builds the jar from a clean checkout; nothing from the host's target/ is used.
#
#   docker build -t stoploss-api api
#   docker run --rm -p 8080:8080 -e SPRING_DATASOURCE_URL=... stoploss-api               # web server
#   docker run --rm ... stoploss-api --app.mode=migrate|seed|load-sample                  # one-shot jobs
#
# Process contract: docs/SPEC.md "API process contract". No BuildKit-only syntax, so the legacy builder works too.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Dependency layer: re-downloaded only when the pom (or the wrapper) changes. The wrapper fetches Maven itself
# (with javac + tar when the image has no curl/wget). go-offline misses a few runtime transitive dependencies, so the
# package step below stays online and fetches whatever is left.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests need Docker (Testcontainers) and run in CI, not in the image build.
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/stoploss-api.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
# curl for the compose healthcheck (curl -fsS http://localhost:8080/healthz).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# Spring Boot layers, least to most often changing; together they are /app/stoploss-api.jar + /app/lib/.
# Files stay root-owned: the runtime user can read the app but not modify it.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
ENV PORT=8080 \
    DATA_DIR=/data \
    JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER app
EXPOSE 8080
# exec form: java is PID 1 and gets SIGTERM directly (graceful shutdown); extra args are passed through, so
# `docker compose run api --app.mode=load-sample` works.
ENTRYPOINT ["java","-jar","/app/stoploss-api.jar"]
