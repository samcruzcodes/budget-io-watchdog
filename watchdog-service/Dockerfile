# Build context: the repository root (see docker-compose.yml).

# Build stage: the official Maven image, same Maven (3.9.16) and Java (21) as ./mvnw.
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /workspace
# Only what this build needs. The simulator's pom.xml is required because the parent POM lists it as a module.
COPY pom.xml ./
COPY transaction-schema transaction-schema
COPY watchdog-service watchdog-service
COPY simulator/pom.xml simulator/pom.xml
# The cache mount keeps downloaded dependencies between builds. Tests run in CI, not here.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -pl watchdog-service -am package -Dmaven.test.skip=true

# Runtime stage: a Java runtime and Spring Boot's executable jar, run as a non-root user.
FROM eclipse-temurin:21-jre-noble
RUN useradd --system --uid 10001 watchdog
WORKDIR /app
COPY --from=build /workspace/watchdog-service/target/watchdog-service.jar app.jar
USER watchdog
ENTRYPOINT ["java", "-jar", "app.jar"]
