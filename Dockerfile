FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml mvnw ./
COPY .mvn .mvn

RUN ./mvnw dependency:go-offline -B

COPY src ./src

RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S cloudforge && \
    adduser -S cloudforge -G cloudforge

COPY --from=builder /app/target/*.jar app.jar

USER cloudforge

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
