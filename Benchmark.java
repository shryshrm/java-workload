import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.CollectorRegistry;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Benchmark {
    static final CollectorRegistry registry = new CollectorRegistry();

    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public static double getHeapUsedKb() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long usedBytes = heapUsage.getUsed();
        return usedBytes / 1024.0; // convert to KB
    }

    private static double[] generateExponentialBuckets(double start, double factor, int count) {
        double[] buckets = new double[count];
        for (int i = 0; i < count; i++) {
            buckets[i] = start * Math.pow(factor, i);
        }
        return buckets;
    }

    static final Counter workloadOps = Counter.build()
            .name("workload_ops_total")
            .help("Total number of operations executed")
            .labelNames("type")
            .register(registry);

    static final Histogram workloadLatency = Histogram.build()
            .name("workload_latency_seconds")
            .help("Latency of each operation in seconds")
            .labelNames("type")
            .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 2, 5)
            .register(registry);

    static final Histogram workloadHeap = Histogram.build()
            .name("workload_heap_kb")
            .help("Heap allocation in KB per operation")
            .buckets(generateExponentialBuckets(10, 1.5, 20)) // same as Go
            .labelNames("type")
            .register();

    static final Random random = new Random();
    static final ExecutorService pool = Executors.newFixedThreadPool(16);
    static final Gson gson = new Gson();

    // ---------------- Workloads ----------------
    static void cpuWork(int ops) {
        for (int i = 0; i < ops; i++) {
            Histogram.Timer timer = workloadLatency.labels("cpu_java").startTimer();
            double durationMs = 2.5 + (double)(random.nextDouble() * 2.5);
            long burnIterations = (long) (250_000 * durationMs);

            double x = 0;
            for (long j = 0; j < burnIterations; j++) {
                x += Math.sqrt(1000);
            }
            timer.observeDuration();
            workloadOps.labels("cpu_java").inc();
        }
        double heapUsedKb = getHeapUsedKb();

        workloadHeap.labels("cpu_java").observe(heapUsedKb);
    }

    static void constIterCpuWork(int ops) {
        for (int i = 0; i < ops; i++) {
            Histogram.Timer timer = workloadLatency.labels("cpu_java").startTimer();
            // Pick random duration between 5–10 ms
            double durationMs = 2.5 + (double)(random.nextDouble() * 2.5);
            long burnIterations = 1000000;
            double x = 0;

            // Example array of numbers
            double[] values = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
            Random rand = new Random();

            for (long j = 0; j < burnIterations; j++) {
                // Pick a random number from the array
                double r = values[rand.nextInt(values.length)];
                x += Math.sqrt(r);
            }
            timer.observeDuration();
            workloadOps.labels("cpu_java").inc();
        }
        double heapUsedKb = getHeapUsedKb();

        workloadHeap.labels("cpu_java").observe(heapUsedKb);
    }

    static void ioWork(int ops) {
        for (int i = 0; i < ops; i++) {
            Histogram.Timer timer = workloadLatency.labels("io_java").startTimer();
            try {
                double durationMs = 2.5;
                long millis = (long) durationMs;                  // whole milliseconds
                int nanos = (int) ((durationMs - millis) * 1_000_000); // remaining nanoseconds

                Thread.sleep(millis, nanos); // simulate db/redis latency
            } catch (InterruptedException ignored) {}
            timer.observeDuration();
            workloadOps.labels("io_java").inc();
        }
        double heapUsedKb = getHeapUsedKb();
        workloadHeap.labels("io_java").observe(heapUsedKb);
    }

    static void cpuIoWork(int ops, double ratio) {
        for (int i = 0; i < ops; i++) {
            Histogram.Timer timer = workloadLatency.labels("cpu_io_java").startTimer();
            if (random.nextDouble() < ratio) {
                // CPU
                long burnIterations = 1000000;
                double x = 0;

                // Example array of numbers
                double[] values = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
                Random rand = new Random();

                for (long j = 0; j < burnIterations; j++) {
                    // Pick a random number from the array
                    double r = values[rand.nextInt(values.length)];
                    x += Math.sqrt(r);
                }
            } else {
                // IO
                try { Thread.sleep(5 + random.nextInt(5)); } catch (InterruptedException ignored) {}
            }
            timer.observeDuration();
            workloadOps.labels("cpu_io_java").inc();
        }
        double heapUsedKb = getHeapUsedKb();
        workloadHeap.labels("cpu_io_java").observe(heapUsedKb);
    }

    // ---------------- JSON Handler ----------------
    static class WorkloadHandler implements HttpHandler {
        private final String type;

        public WorkloadHandler(String type) {
            this.type = type;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            JsonObject req = gson.fromJson(new java.io.InputStreamReader(is), JsonObject.class);

            int ops = req.has("ops") ? req.get("ops").getAsInt() : 1;
            int workers = req.has("workers") ? req.get("workers").getAsInt() : 1;
            double ratio = req.has("ratio") ? req.get("ratio").getAsDouble() : 0.5;

            int opsPerWorker = Math.max(1, ops / workers);
            CountDownLatch latch = new CountDownLatch(workers);

            for (int i = 0; i < workers; i++) {
//                pool.submit(() -> {
                    switch (type) {
                        case "cpu" -> constIterCpuWork(opsPerWorker);
                        case "io" -> ioWork(opsPerWorker);
                        case "cpu_io" -> cpuIoWork(opsPerWorker, ratio);
                    }
//                    latch.countDown();
//                });
            }

//            try { latch.await(); } catch (InterruptedException ignored) {}

            String msg = "Triggered " + type + " workload with ops=" + ops + ", workers=" + workers + (type.equals("cpu_io") ? ", ratio=" + ratio : "");
            exchange.sendResponseHeaders(200, msg.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes());
            }
        }
    }

    // ---------------- Main ----------------
    public static void main(String[] args) throws Exception {
        DefaultExports.register(registry);

        // Metrics server
        new HTTPServer(new InetSocketAddress(9092), registry, true);

        // API server
        HttpServer api = HttpServer.create(new InetSocketAddress(9091), 0);
        api.createContext("/cpu", new WorkloadHandler("cpu"));
        api.createContext("/io", new WorkloadHandler("io"));
        api.createContext("/cpui", new WorkloadHandler("cpu_io"));
        api.setExecutor(pool);
        api.start();

        System.out.println("🚀 API running on :9091");
        System.out.println("📊 Metrics exposed on :9092/metrics");
    }
}
