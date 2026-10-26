# ─── Stage 1: Build ───────────────────────────────────────────────
# Use the official Maven image — it has BOTH JDK and Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy ONLY pom.xml first. This is critical for layer caching.
# Docker caches this layer. If pom.xml doesn't change, dependencies
# are NOT re-downloaded on subsequent builds.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Now copy the source code. This layer is only invalidated when
# source files change, not when dependencies change.
COPY src ./src

# Build the JAR, skipping tests (tests run in CI, not in Docker build)
RUN mvn clean package -DskipTests

# ─── Stage 2: Runtime ─────────────────────────────────────────────
# Use the slim JRE image — no Maven, no JDK, just the runtime.
# This keeps the final image small (~200MB instead of ~800MB).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Security: create a non-root user. Never run production apps as root.
RUN addgroup -S spring && adduser -S spring -G spring
RUN chown -R spring:spring /app

USER spring:spring

# Copy ONLY the built JAR from the build stage.
# The multi-stage build discards Maven, source code, and build tools.
COPY --from=build /app/target/*.jar app.jar

# JVM tuning for a t2.micro (1GB RAM).
# -Xms256m: start with 256MB heap
# -Xmx512m: max 512MB heap (leave room for OS + Postgres)
# G1GC: low-pause garbage collector
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]