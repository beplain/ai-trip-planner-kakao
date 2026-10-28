package com.tripplanner.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.trip.TripDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Kakao-only route lookup. Credentials stay on the server and are sent in headers. */
@Component
public class RouteTimeClient {
    private final String carKey, transitKey;
    private final int maxCalls;
    private final RestClient http;
    private final ExecutorService routePool = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "route-lookup"); thread.setDaemon(true); return thread;
    });

    @jakarta.annotation.PreDestroy
    void shutdown() { routePool.shutdownNow(); }

    @org.springframework.beans.factory.annotation.Autowired
    public RouteTimeClient(@Value("${app.routing.kakao-key:}") String carKey,
                           @Value("${app.routing.kakao-map-key:}") String transitKey,
                           @Value("${app.routing.max-calls:12}") int maxCalls) {
        this(carKey, transitKey, maxCalls, client());
    }
    RouteTimeClient(String carKey, String transitKey, int maxCalls, RestClient http) {
        this.carKey = carKey; this.transitKey = transitKey;
        this.maxCalls = Math.max(0, Math.min(maxCalls, 80)); this.http = http;
    }
    private static RestClient client() {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2)); f.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(f).build();
    }
    Session session(MovementMode mode) { return new Session(mode == null ? MovementMode.PUBLIC_TRANSIT : mode); }

    final class Session {
        private final MovementMode mode;
        private final Map<String, OptionalInt> cache = new HashMap<>();
        private final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        private int calls;
        private boolean failed;
        Session(MovementMode mode) { this.mode = mode; }
        boolean enabled() { return !(mode == MovementMode.CAR ? carKey : transitKey).isBlank() && maxCalls > 0; }
        boolean incomplete() { return failed; }
        /** Reserve the same call budget before running independent existing legs concurrently. */
        void prefetch(List<List<PlaceDraft>> paths) {
            Map<String, Future<OptionalInt>> pending = new LinkedHashMap<>();
            for (List<PlaceDraft> path : paths) for (int i = 1; i < path.size(); i++) {
                PlaceDraft a = path.get(i - 1), b = path.get(i);
                if (!TripRouteOptimizer.known(a) || !TripRouteOptimizer.known(b)) { failed = true; continue; }
                if (a.latitude().equals(b.latitude()) && a.longitude().equals(b.longitude())) continue;
                String key = key(a,b);
                if (cache.containsKey(key) || pending.containsKey(key)) continue;
                if (!enabled() || calls >= maxCalls || System.nanoTime() >= deadline) { failed = true; continue; }
                calls++;
                pending.put(key, routePool.submit(() -> {
                    try { return fetch(a,b,mode); } catch (RuntimeException e) { return OptionalInt.empty(); }
                }));
            }
            for (var entry : pending.entrySet()) {
                OptionalInt value = OptionalInt.empty();
                try {
                    long remaining = deadline - System.nanoTime();
                    if (!entry.getValue().isDone() && remaining <= 0) throw new TimeoutException();
                    value = entry.getValue().get(Math.max(1,remaining),TimeUnit.NANOSECONDS);
                } catch (Exception e) {
                    entry.getValue().cancel(true);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                if (value.isEmpty()) failed = true;
                cache.put(entry.getKey(),value);
            }
        }
        OptionalInt minutes(PlaceDraft a, PlaceDraft b) {
            if (!TripRouteOptimizer.known(a) || !TripRouteOptimizer.known(b)) { failed = true; return OptionalInt.empty(); }
            if (a.latitude().equals(b.latitude()) && a.longitude().equals(b.longitude())) return OptionalInt.of(0);
            String key = key(a,b);
            if (cache.containsKey(key)) return cache.get(key);
            if (!enabled() || calls >= maxCalls || System.nanoTime() >= deadline) { failed = true; return OptionalInt.empty(); }
            calls++;
            OptionalInt value;
            try { value = fetch(a, b, mode); } catch (RuntimeException e) { value = OptionalInt.empty(); }
            if (value.isEmpty()) failed = true;
            cache.put(key, value); return value;
        }
    }
    private static String key(PlaceDraft a, PlaceDraft b) {
        return a.longitude()+","+a.latitude()+">"+b.longitude()+","+b.latitude();
    }
    private OptionalInt fetch(PlaceDraft a, PlaceDraft b, MovementMode mode) {
        if (mode == MovementMode.CAR) {
            var uri = UriComponentsBuilder.fromUriString("https://apis-navi.kakaomobility.com/v1/directions")
                    .queryParam("origin", a.longitude()+","+a.latitude())
                    .queryParam("destination", b.longitude()+","+b.latitude())
                    .queryParam("summary", true).queryParam("priority", "TIME").build().encode().toUri();
            JsonNode body = http.get().uri(uri).header("Authorization", "KakaoAK " + carKey).retrieve().body(JsonNode.class);
            if (body == null) return OptionalInt.empty();
            int best = Integer.MAX_VALUE;
            for (JsonNode route : body.path("routes")) if (route.path("result_code").asInt(-1) == 0) {
                int seconds = route.path("summary").path("duration").asInt(-1);
                if (seconds > 0) best = Math.min(best, (seconds + 59) / 60);
            }
            return best == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
        }
        var uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/routing/publictraffic")
                .queryParam("start_x", a.longitude()).queryParam("start_y", a.latitude())
                .queryParam("end_x", b.longitude()).queryParam("end_y", b.latitude())
                .queryParam("input_coord", "WGS84").build().encode().toUri();
        JsonNode body = http.get().uri(uri).header("Authorization", "KakaoAK " + transitKey).retrieve().body(JsonNode.class);
        if (body == null || !"OK".equals(body.path("status").asText())) return OptionalInt.empty();
        int best = Integer.MAX_VALUE;
        for (JsonNode route : body.path("routes")) {
            JsonNode properties = route.path("properties");
            String type = properties.path("type").asText();
            int seconds = properties.path("totalTime").asInt(-1);
            if (Set.of("BUS", "SUBWAY", "BUS_AND_SUBWAY").contains(type) && seconds > 0)
                best = Math.min(best, (seconds + 59) / 60);
        }
        return best == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }
}
