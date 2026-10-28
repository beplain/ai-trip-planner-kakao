package com.tripplanner.trip;

import com.tripplanner.place.GenerationPlaces;
import com.tripplanner.place.KakaoLocalClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenerationPlacesTests {
    @Test void simultaneousRepeatedQueriesAreReusedButDifferentCentersAndRequestsAreNot() throws Exception {
        AtomicInteger searches = new AtomicInteger(), areas = new AtomicInteger();
        var delegate = new KakaoLocalClient(null,null) {
            @Override public List<PlaceHit> search(String q, Area a, int size) { searches.incrementAndGet(); return List.of(); }
            @Override public Optional<Area> resolveArea(String d) { areas.incrementAndGet(); return Optional.empty(); }
        };
        var cached = new GenerationPlaces(delegate); var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<KakaoLocalClient.PlaceHit>>> tasks = new ArrayList<>();
            for (int i=0;i<10;i++) tasks.add(() -> cached.search("식당",null,15));
            for (var result : pool.invokeAll(tasks)) assertTrue(result.get().isEmpty());
            assertEquals(1,searches.get());
            cached.resolveArea("부산"); cached.resolveArea("부산"); assertEquals(1,areas.get());
            cached.search("식당",new KakaoLocalClient.Area(35,129,"부산"),15); assertEquals(2,searches.get());
            new GenerationPlaces(delegate).search("식당",null,15); assertEquals(3,searches.get());
        } finally { pool.shutdownNow(); }
    }
}
