# Build:  docker build -t lob-engine .
# Run:    docker run -v lob-data:/data -e SPRING_PROFILES_ACTIVE=dev -p 8080:8080 lob-engine
#         (the dev profile switches authentication off; for real use configure users, see docs/operations/runbook.md)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -ntp -DskipTests -Djacoco.skip=true -pl api -am package

FROM eclipse-temurin:21-jre
# A fixed, unprivileged user, so that a volume's ownership is predictable.
RUN useradd --system --uid 10001 --no-create-home lob && mkdir /data && chown 10001 /data
COPY --from=build /src/api/target/api-*-SNAPSHOT.jar /app/lob-engine.jar
USER 10001
WORKDIR /app
VOLUME /data
EXPOSE 8080 9878
ENV LOB_DATA_DIR=/data \
    LOB_BIND_ADDRESS=0.0.0.0
# Exit on OutOfMemoryError so that the orchestrator restarts a venue that recovers from its journal.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/lob-engine.jar"]
