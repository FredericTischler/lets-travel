# =============================================================================
# payment-service — multi-stage Dockerfile
# Base images: eclipse-temurin (official, pinned — NEVER openjdk, deprecated).
# Build stage: JDK 21 (compile + package).
# Runtime stage: JRE 21 (minimal attack surface).
# =============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:21.0.7_6-jdk-noble AS build

WORKDIR /workspace

# `unzip` is REQUIRED by this project's mvnw: it is a hand-rolled shell wrapper
# (no maven-wrapper.jar shipped) that downloads the Maven distribution zip and
# extracts it with `unzip` — a tool absent from eclipse-temurin:*-jdk-noble.
# Without this the build fails at `./mvnw` with "unzip: not found". Pinned apt
# layer, lists cleaned to keep the build stage lean (discarded at runtime anyway).
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

# Copy Maven wrapper and POM first (layer cache: dependency download only
# re-runs when pom.xml changes, not on every source change).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (offline-friendly layer).
RUN ./mvnw dependency:go-offline -B --no-transfer-progress

# Copy source and build the fat JAR. Tests are skipped here; they run in CI
# with Testcontainers against a real DB, not inside the Docker build context.
COPY src/ src/
RUN ./mvnw package -DskipTests -B --no-transfer-progress

# ---- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:21.0.7_6-jre-noble AS runtime

# Non-root user: principle of least privilege inside the container.
RUN groupadd --system --gid 1001 appgroup \
 && useradd  --system --uid 1001 --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy only the fat JAR from the build stage.
COPY --from=build /workspace/target/payment-service-*.jar app.jar

# Ownership: appuser cannot write to /app (read-only runtime).
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# JVM flags:
#   -XX:+UseContainerSupport   — respect cgroup memory limits (default in 21).
#   -XX:MaxRAMPercentage=75.0  — use 75% of container RAM for heap.
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]