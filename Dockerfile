# Multi-stage build: building and running needs only Docker, no local JDK.
# Targets: `app` (the service) and `simulator`.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Build scripts first, so the dependency download layer is cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY service/build.gradle.kts service/
COPY simulator/build.gradle.kts simulator/
RUN chmod +x gradlew && ./gradlew --no-daemon :service:dependencies :simulator:dependencies > /dev/null

COPY service/src service/src
COPY simulator/src simulator/src
# Tests need Docker (Testcontainers) and run outside the image build.
RUN ./gradlew --no-daemon :service:bootJar :simulator:installDist

FROM eclipse-temurin:25-jre AS simulator
WORKDIR /app
COPY --from=build /src/simulator/build/install/simulator /app
ENTRYPOINT ["/app/bin/simulator"]

FROM eclipse-temurin:25-jre AS app
WORKDIR /app
COPY --from=build /src/service/build/libs/service.jar /app/service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
