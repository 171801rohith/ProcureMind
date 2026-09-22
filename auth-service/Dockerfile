# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy auth-service pom.xml and source code
COPY auth-service/pom.xml ./auth-service/pom.xml
COPY auth-service/src ./auth-service/src

# Package the standalone Spring Boot application
RUN mvn clean package -f ./auth-service/pom.xml -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl

COPY --from=builder /build/auth-service/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
