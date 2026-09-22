# Build context is the REPO ROOT (see docker-compose.yml `context: .`),
# not the service/ directory -- this lets the build pull in db/ (the
# single source of truth for migrations/views/test-data) alongside the
# service's own pom.xml/src.

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /repo
COPY db ./db
COPY service/pom.xml ./service/pom.xml
WORKDIR /repo/service
RUN mvn -B dependency:go-offline
COPY service/src ./src
RUN mvn -B clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=build /repo/service/target/business-network.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
