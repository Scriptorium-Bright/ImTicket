FROM gradle:jdk21-jammy AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY src ./src

RUN gradle build -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

RUN mkdir -p /app/uploads


EXPOSE 10080


ENTRYPOINT ["java", "-jar", "app.jar"]
