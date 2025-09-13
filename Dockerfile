FROM openjdk:17-jdk-slim AS builder

WORKDIR /app

# Install curl to fetch jars
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Prometheus Java client version
ENV PROM_VERSION=0.16.0

# Download required prometheus-client jars
RUN mkdir -p lib && \
    curl -L -o lib/gson-2.10.1.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar && \
    curl -L -o lib/simpleclient.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient/${PROM_VERSION}/simpleclient-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_common.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_common/${PROM_VERSION}/simpleclient_common-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_httpserver.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_httpserver/${PROM_VERSION}/simpleclient_httpserver-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_hotspot.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_hotspot/${PROM_VERSION}/simpleclient_hotspot-${PROM_VERSION}.jar

# Copy source code
COPY Benchmark.java ./

# Compile Java code with classpath including all jars
RUN javac -cp "lib/*" Benchmark.java

# Package app into a runnable jar
RUN jar cfe benchmark.jar Benchmark *.class

# Final runtime image
FROM openjdk:17-jdk-slim
WORKDIR /app

# Copy jars and compiled app from builder
COPY --from=builder /app/lib ./lib
COPY --from=builder /app/benchmark.jar ./

# Expose ports
EXPOSE 9091 9092

# Run app with all dependencies on classpath
CMD ["java", "-cp", "benchmark.jar:lib/*", "Benchmark"]
