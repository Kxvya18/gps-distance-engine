package com.moveinsync.distance.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

// HTTP load test against a running stack: real JSON, real login, real Postgres writes.
// Usage: java -cp target/classes com.moveinsync.distance.benchmark.ApiLoadRunner http://localhost:8080 2000 8
public class ApiLoadRunner {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int requests = args.length > 1 ? Integer.parseInt(args[1]) : 2_000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        String token = login(base);
        String trip = Files.readString(Path.of("scripts/sample-trip.json"));
        String run = "load-" + System.currentTimeMillis();

        List<String> lines = new ArrayList<>();
        lines.add(measure("POST trip (new)", requests, concurrency, i ->
                post(base + "/api/trips", token, trip.replace("\"tripId\":\"demo\"", "\"tripId\":\"" + run + "-" + i + "\""))));
        lines.add(measure("POST trip (replay)", requests, concurrency, i ->
                post(base + "/api/trips", token, trip.replace("\"tripId\":\"demo\"", "\"tripId\":\"" + run + "-" + i + "\""))));
        lines.add(measure("GET trip", requests, concurrency, i ->
                HttpRequest.newBuilder(URI.create(base + "/api/trips/" + run + "-" + i))
                        .header("Authorization", "Bearer " + token).GET().build()));

        Files.createDirectories(Path.of("docs/results"));
        List<String> all = new ArrayList<>();
        all.add("operation,requests,concurrency,seconds,requests_per_second,p50_ms,p90_ms,p95_ms,p99_ms,max_ms,non_200");
        all.addAll(lines);
        Files.write(Path.of("docs/results/api-load.csv"), all);
        all.forEach(System.out::println);
    }

    private interface RequestMaker {
        HttpRequest make(int index);
    }

    private static String measure(String name, int requests, int concurrency, RequestMaker maker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Long> nanos = Collections.synchronizedList(new ArrayList<>());
        Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                long before = System.nanoTime();
                HttpResponse<Void> response = CLIENT.send(maker.make(index), HttpResponse.BodyHandlers.discarding());
                nanos.add(System.nanoTime() - before);
                statuses.computeIfAbsent(response.statusCode(), s -> new LongAdder()).increment();
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - start) / 1e9;
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        long ok = statuses.getOrDefault(200, new LongAdder()).sum();
        return String.format("%s,%d,%d,%.2f,%.0f,%.1f,%.1f,%.1f,%.1f,%.1f,%d", name, requests, concurrency, seconds, requests / seconds,
                ms(sorted, 50), ms(sorted, 90), ms(sorted, 95), ms(sorted, 99), ms(sorted, 100), requests - ok);
    }

    private static double ms(List<Long> sorted, int percentile) {
        int position = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, position)) / 1e6;
    }

    private static HttpRequest post(String url, String token, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String login(String base) throws Exception {
        String body = "{\"userId\":\"admin-a\",\"password\":\"" + System.getenv().getOrDefault("DEMO_PASSWORD", "demo-password") + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        String response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
        int start = response.indexOf(":\"") + 2;
        return response.substring(start, response.indexOf('"', start));
    }
}
