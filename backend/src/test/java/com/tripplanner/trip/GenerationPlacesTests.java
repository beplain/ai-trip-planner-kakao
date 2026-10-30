package com.tripplanner.trip;

import com.tripplanner.place.GenerationPlaces;
import com.tripplanner.place.KakaoLocalClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenerationPlacesTests {
    @Test void mealQueriesExpandWithinRequestAndReuseDeduplicatedActualBusinesses() {
        var terms=new ArrayList<String>();
        var delegate=new KakaoLocalClient(null,null) {
            @Override public List<PlaceHit> search(String q,Area a,int size) {
                terms.add(q);
                if(q.equals("부산 삼겹살")) return List.of();
                return List.of(new PlaceHit("1","연탄집","부산",35.,129.,null,null,"음식점",0,"FD6","음식점 > 돼지고기구이"));
            }
        };
        var expansions=ExtraRequestRules.mealSearches("마지막 날 저녁식사는 삼겹살로 추천해줘","부산",2);
        var cached=new GenerationPlaces(delegate,expansions);
        assertEquals(1,cached.search("부산 삼겹살",null,15).size());
        assertEquals(List.of("부산 삼겹살","부산 삼겹살 맛집","부산 돼지고기구이"),terms);
        assertEquals(1,cached.search("부산 삼겹살",null,15).size()); assertEquals(3,terms.size());
    }
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
