# ── Stage 1: Build ───────────────────────────────────────────────────
# Use a full Maven + JDK image just for building the JAR.
# This stage is thrown away after — it never ends up in the final image.
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first and download dependencies separately.
# Docker caches this layer — if only source files change, Maven
# doesn't re-download the internet on every build.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Now copy source and build the JAR
COPY src ./src
RUN mvn clean package -q -DskipTests

# ── Stage 2: Run ─────────────────────────────────────────────────────
# Slim JRE-only image — no Maven, no JDK, no build tools.
# Result is ~200MB instead of ~600MB.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create directories the app needs at runtime
RUN mkdir -p /app/logs

# Copy only the built JAR from the builder stage
COPY --from=builder /app/target/availkv-0.0.1-SNAPSHOT.jar app.jar

# The port this container will listen on.
# Overridden per-node via NODE_PORT env var in docker-compose.
EXPOSE 8081

# All configuration comes in via environment variables.
# Defaults here are overridden by docker-compose.yml.
ENV NODE_ID=node1
ENV NODE_PORT=8081
ENV PEER_URLS=http://node2:8082,http://node3:8083
ENV PEER_IDS=node2,node3
ENV WAL_PATH=/app/logs/wal.txt
ENV OLLAMA_URL=http://ollama:11434
ENV OLLAMA_MODEL=gemma2:2b

ENTRYPOINT ["java", "-jar", "app.jar"]