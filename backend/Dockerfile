# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Run. Ubuntu 24.04 (noble) ships the PostgreSQL 16 client, so pg_dump matches the server for backups.
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-16 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app \
 && mkdir -p /data/uploads /data/backups && chown -R app /data
WORKDIR /app
COPY --from=build /src/target/credit-backend-*.jar app.jar
USER app
ENV STORAGE_LOCAL_ROOT=/data/uploads \
    BACKUP_DIR=/data/backups \
    TZ=Asia/Kolkata \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
