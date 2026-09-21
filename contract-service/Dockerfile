# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy and install shared common library
COPY procuremind-common/pom.xml ./procuremind-common/pom.xml
COPY procuremind-common/src ./procuremind-common/src
RUN mvn clean install -f ./procuremind-common/pom.xml -DskipTests

# Copy and package contract-service
COPY contract-service/pom.xml ./contract-service/pom.xml
COPY contract-service/src ./contract-service/src
RUN mvn clean package -f ./contract-service/pom.xml -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl

COPY --from=builder /build/contract-service/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]