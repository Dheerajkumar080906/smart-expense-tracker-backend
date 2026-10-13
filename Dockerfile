# Multi-stage Dockerfile for Spring Boot backend
# Stage 1: Build application
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application artifact
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Lightweight runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

# Ensure data directory exists for H2 file persistence
RUN mkdir -p /app/data

COPY --from=builder /app/target/smart-expense-tracker-1.0.0.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
