# Stage 1: Build the application
FROM gradle:jdk21-jammy AS builder
WORKDIR /app

# Copy dependency definition to cache dependencies
COPY build.gradle settings.gradle ./
# Copy source code
COPY src ./src

# Build the application (exclude tests to speed up build time for now, or include if desired. Plan said tests in CI/CD)
RUN gradle build -x test --no-daemon

# Stage 2: Run the application
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built artifact from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Create directory for file uploads
RUN mkdir -p /app/uploads

# Expose the port
EXPOSE 10080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
