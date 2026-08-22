# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy api-gateway pom.xml and source code
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY api-gateway/src ./api-gateway/src

# Package the standalone Spring Boot application
RUN mvn clean package -f ./api-gateway/pom.xml -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/api-gateway/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]