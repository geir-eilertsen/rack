FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/rack-*.jar app.jar
# The port inside the container. Spring Boot's relaxed binding maps SERVER_PORT
# onto server.port, so the image moves without touching application.yml — a
# checkout run with `./mvnw spring-boot:run` still comes up on 8080.
ENV SERVER_PORT=8123
EXPOSE 8123
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
