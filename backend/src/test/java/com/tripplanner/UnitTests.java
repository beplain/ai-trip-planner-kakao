package com.tripplanner;

import com.tripplanner.ai.AiItinerary;
import com.tripplanner.ai.AiItinerary.AiDay;
import com.tripplanner.ai.AiItinerary.AiPlace;
import com.tripplanner.ai.ItineraryRequest;
import com.tripplanner.ai.SampleItineraryGenerator;
import com.tripplanner.place.Geo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnitTests {

    @Test
    void haversineSeoulToBusanIsAbout325km() {
        double d = Geo.distanceMeters(37.5665, 126.9780, 35.1796, 129.0756);
        assertTrue(d > 310_000 && d < 340_000, "distance=" + d);
    }

    @Test
    void sampleGeneratorMakesRequestedDays() {
        AiItinerary it = new SampleItineraryGenerator()
                .generate(new ItineraryRequest("부산", null, 3, "20만원", List.of("맛집"), null));
        assertEquals(3, it.days().size());
        assertTrue(it.days().stream().allMatch(d -> d.places().size() >= 2));
    }

    @Test
    void aiItineraryShapeIsRecordFriendly() {
        AiPlace p = new AiPlace("해운대해수욕장", "해운대해수욕장 부산", "10:00", "자연", "바다", "팁");
        AiItinerary it = new AiItinerary("부산 1일", List.of(new AiDay(1, List.of(p, p), List.of())));
        assertEquals("해운대해수욕장", it.days().get(0).places().get(0).name());
    }
}
