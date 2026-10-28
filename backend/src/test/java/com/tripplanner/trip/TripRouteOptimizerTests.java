package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TripRouteOptimizerTests {
    @Test void actualRouteCostsCanPreferTheOppositeOfStraightLineOrder() {
        var start = p("station",129,"09:00","","arrival");
        var near = p("near",129.01,"10:00","",null);
        var far = p("far",129.02,"12:00","",null);
        var end = p("hotel",129.03,"17:00","","checkin");
        var result = TripRouteOptimizer.optimize(draft(List.of(start,near,far,end)), (a,b) -> {
            String edge = a.placeId()+">"+b.placeId();
            return Set.of("station>far","far>near","near>hotel").contains(edge) ? 10 : 100;
        });
        assertEquals(List.of("station","far","near","hotel"),result.days().get(0).places().stream().map(PlaceDraft::placeId).toList());
    }
    private PlaceDraft p(String id, double longitude, String time, String reason, String fixed) {
        return new PlaceDraft(id, id, "주소", 35.0, longitude, time, reason, "", "관광", true, "url", "", fixed);
    }
    private TripDraft draft(List<PlaceDraft> places) {
        return new TripDraft("여행", "부산", null, null, null, List.of(),
                List.of(new DayDraft(1, null, places)), "AI", List.of());
    }
    @Test void shortensRouteWhileKeepingSlotsAndFixedEvents() {
        var start = p("station", 129, "09:00", "", "arrival");
        var end = p("hotel", 129.04, "17:00", "", "checkin");
        var original = draft(List.of(start, p("far", 129.03, "10:00", "", null),
                p("near", 129.01, "12:00", "", null), p("middle", 129.02, "14:00", "", null), end));
        var result = TripRouteOptimizer.optimize(original).days().get(0).places();
        assertEquals(List.of("station", "near", "middle", "far", "hotel"), result.stream().map(PlaceDraft::placeId).toList());
        assertEquals(original.days().get(0).places().stream().map(PlaceDraft::visitTime).toList(), result.stream().map(PlaceDraft::visitTime).toList());
        assertEquals(start, result.get(0)); assertEquals(end, result.get(result.size() - 1));
        assertEquals("far", original.days().get(0).places().get(1).placeId());
    }
    @Test void preservesMealAndNightWindowsAndFixedBoundaries() {
        var places = List.of(p("station",129,"09:00","","arrival"), p("distant",129.03,"10:00","",null),
                p("lunch",129.01,"12:00","점심",null), p("hotel",129.02,"15:00","","checkin"),
                p("dinner",129.04,"17:00","저녁",null), p("night",129.03,"19:00","야경",null));
        var result = TripRouteOptimizer.optimize(draft(places)).days().get(0).places();
        assertEquals(places, result);
    }
    @Test void toleratesUnknownCoordinatesAndDoesNotDropPlaces() {
        var unknown = new PlaceDraft("old","old","",null,null,"10:00","","","관광",false,null,null);
        var input = draft(List.of(unknown,p("known",129,"12:00","",null)));
        assertEquals(input, TripRouteOptimizer.optimize(input));
    }
}
