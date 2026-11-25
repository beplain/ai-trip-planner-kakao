package com.tripplanner.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.tripplanner.trip.TripDtos.*;

class StyleNamesTests {
    @Test void oldClientsAndAiResponsesUseCanonicalExperience() {
        var request = new ItineraryRequest("부산",null,1,null,List.of("액티비티","체험"),null);
        assertEquals(List.of("체험"), request.styles());
        assertEquals(List.of("체험"), new GenerateRequest("부산",null,1,null,List.of("액티비티"),null).styles());
        var place = new AiItinerary.AiPlace("체험관","체험관","14:00","액티비티","","",List.of("액티비티"));
        assertEquals("체험",place.category());
        assertEquals(java.util.Set.of("체험"),ItineraryRules.stylesFor(place));
        var itinerary = new AiItinerary("여행",List.of(new AiItinerary.AiDay(1,List.of(
            new AiItinerary.AiPlace("공원","공원","10:00","자연","","",List.of("자연")),
            new AiItinerary.AiPlace("박물관","박물관","12:00","문화","","",List.of("문화"))),List.of())));
        var errors=ItineraryRules.validate(itinerary,request);
        assertTrue(errors.stream().anyMatch(e->e.contains("빠진 스타일: 체험")));
        assertTrue(errors.stream().noneMatch(e->e.contains("액티비티")));
    }
}
