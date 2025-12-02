FROM eclipse-temurin:17-jdk

WORKDIR /app

# Install curl to fetch jars
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

ENV PROM_VERSION=0.16.0

RUN mkdir -p lib && \
    curl -L -o lib/gson-2.10.1.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar && \
    curl -L -o lib/simpleclient.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient/${PROM_VERSION}/simpleclient-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_common.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_common/${PROM_VERSION}/simpleclient_common-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_httpserver.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_httpserver/${PROM_VERSION}/simpleclient_httpserver-${PROM_VERSION}.jar && \
    curl -L -o lib/simpleclient_hotspot.jar https://repo1.maven.org/maven2/io/prometheus/simpleclient_hotspot/${PROM_VERSION}/simpleclient_hotspot-${PROM_VERSION}.jar

COPY Benchmark.java ./

RUN javac -cp "lib/*" Benchmark.java

RUN jar cfe benchmark.jar Benchmark *.class

EXPOSE 9091 9092

CMD ["java", "-cp", "benchmark.jar:lib/*", "Benchmark"]
