# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy and install shared common library
COPY procuremind-common/pom.xml ./procuremind-common/pom.xml
COPY procuremind-common/src ./procuremind-common/src
RUN mvn clean install -f ./procuremind-common/pom.xml -DskipTests

# Copy and package ai-service
COPY ai-service/pom.xml ./ai-service/pom.xml
COPY ai-service/src ./ai-service/src
RUN mvn clean package -f ./ai-service/pom.xml -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/ai-service/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]