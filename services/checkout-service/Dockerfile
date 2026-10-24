# Build stage: compile and package the Spring Boot fat jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/target/checkout-service.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
