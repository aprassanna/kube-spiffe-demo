# Global build argument, declared before the first FROM so the runtime stage can use it.
# cloudbuild.yaml sets it to the java-runtime base image. Outside Cloud Build, it falls back to
# the upstream distroless image.
ARG BASE_IMAGE=gcr.io/distroless/java21-debian12:nonroot

# Build stage: compile and package the Spring Boot fat jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage on the shared base image (images/java-runtime).
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --from=build /workspace/target/cart-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
