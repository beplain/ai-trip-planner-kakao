package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.MovementMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.*;

/** Route geometry is requested only when the map opens; never during generation. */
@RestController
@RequestMapping("/api/trips/map-routes")
public class MapRouteController {
    public record Point(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
    public record Pair(@NotNull @Valid Point from, @NotNull @Valid Point to) {}
    public record Request(@NotNull MovementMode mode, @NotNull @Size(max=32) List<@NotNull @Valid Pair> legs) {}
    public record Segment(String mode, List<Point> points) {}
    public record Leg(String status, List<Segment> segments) {
        static Leg unavailable() { return new Leg("UNAVAILABLE", List.of()); }
    }
    public record Response(List<Leg> legs) {}
    private final RouteTimeClient client;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> { var t=new Thread(task,"map-route"); t.setDaemon(true); return t; });
    public MapRouteController(RouteTimeClient client) { this.client=client; }
    @jakarta.annotation.PreDestroy void shutdown() { pool.shutdownNow(); }

    @PostMapping
    public Response routes(@Valid @RequestBody Request request) {
        List<Future<Leg>> jobs=new ArrayList<>();
        for (Pair pair:request.legs()) {
            try { jobs.add(pool.submit(() -> {
                try { return client.geometry(pair,request.mode()); }
                catch (RuntimeException e) { return Leg.unavailable(); }
            })); } catch (RejectedExecutionException e) { jobs.add(CompletableFuture.completedFuture(Leg.unavailable())); }
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        List<Leg> results=new ArrayList<>();
        for (Future<Leg> job:jobs) {
            try { results.add(job.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)); }
            catch (Exception e) {
                job.cancel(true); results.add(Leg.unavailable());
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        return new Response(results);
    }
}
