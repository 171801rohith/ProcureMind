# Stage 1: Build Stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Maven wrapper and root POM
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Copy shared library and service source
COPY procuremind-common/ ./procuremind-common/
COPY ai-service/ ./ai-service/

RUN chmod +x mvnw

# Build procuremind-common first, then package ai-service
RUN ./mvnw clean install -pl procuremind-common -am -DskipTests
RUN ./mvnw clean package -pl ai-service -DskipTests

# Stage 2: Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/ai-service/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]