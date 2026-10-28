package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.place.KakaoLocalClient.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class PlaceCategoryGenerationTests {
    static PlaceHit hit(String id, String name, String group, String code, String path) {
        return new PlaceHit(id,name,"부산",35,129,null,"url",group,0,code,path);
    }
    static class Maps extends TripGenerationRulesTests.FakePlaces {
        @Override public List<PlaceHit> search(String q, Area area, int size) {
            boolean food = q.contains("식당") || q.contains("맛집");
            if (q.contains("카페")) return List.of();
            var wrong = food ? hit("apartment", "행복아파트", "", "", "부동산 > 주거시설 > 아파트")
                    : hit("cinema","CGV","문화시설","CT1","문화,예술 > 영화,영상 > 영화관");
            var unknown = hit("unknown", "업종 미확인", "", "", "");
            var good = food ? hit("food-"+q,"식당A", "음식점","FD6","음식점 > 한식")
                    : hit("shop-"+q,"상점A","","","가정,생활 > 백화점");
            return List.of(wrong, unknown, wrong, unknown, good);
        }
    }
    @Test void normalGenerationSkipsCinemaApartmentsAndUnknownIndustries() {
        var ai = new TripGenerationRulesTests.FakeAi(new AiItinerary("부산",List.of(new AiDay(1,List.of(
                TripGenerationRulesTests.shop("상점A"),new AiPlace("식당A","식당A","12:00","식당","","",List.of("맛집"))),List.of()))));
        var service = new TripGenerationService(ai,new SampleItineraryGenerator(),new Maps());
        try {
            var draft = service.generate(new GenerateRequest("부산",null,1,null,List.of("쇼핑","맛집"),null));
            assertEquals(List.of("상점A","식당A"),draft.days().get(0).places().stream().map(PlaceDraft::name).toList());
            assertEquals(1,ai.calls);
        } finally { service.shutdown(); }
    }
    @Test void recoveryFindsVerifiedBusinessBeyondFirstThreeWrongHits() {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var draft = new ConfirmedItineraryFallback(new Maps(),pool).generate(
                    new GenerateRequest("부산",null,1,null,List.of("쇼핑","맛집"),null),null,false);
            var visits = draft.days().get(0).places();
            assertTrue(visits.stream().anyMatch(p -> p.category().equals("쇼핑")));
            assertTrue(visits.stream().anyMatch(p -> p.category().equals("식당")));
            assertTrue(visits.stream().allMatch(p -> Set.of("상점A","식당A").contains(p.name())));
        } finally { pool.shutdownNow(); }
    }
}
