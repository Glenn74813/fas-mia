# syntax=docker/dockerfile:1
# Build stage: compile and package with the Maven wrapper (tests run in CI, not in the image build).
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/cas-backend-*.jar /app.jar

# Runtime stage: JRE only, non-root, no build tools or sources.
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system cas && useradd --system --gid cas --home /app --shell /usr/sbin/nologin cas
WORKDIR /app
COPY --from=build /app.jar /app/cas-backend.jar
USER cas
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom -Djdk.tls.client.protocols=TLSv1.3"
# Liveness/readiness: orchestrator probes on /actuator/health/liveness and /actuator/health/readiness
# (management port 8081 in the prod profile).
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/cas-backend.jar"]
