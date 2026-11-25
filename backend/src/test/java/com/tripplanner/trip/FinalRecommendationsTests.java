package com.tripplanner.trip;

import com.fasterxml.jackson.databind.*;
import com.tripplanner.ai.OpenAiItineraryClient;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalRecommendationsTests {
    final ObjectMapper json=new ObjectMapper();
    PlaceDraft place(String id,String name,String reason,String category) {return new PlaceDraft(id,name,"주소는 추천 문구에 넣지 않음",37.5,127.0,"12:00",reason,"사용자 메모",category,true,null,null,null);}
    TripDraft draft(PlaceDraft... places) {return new TripDraft("여행","부산",null,null,null,List.of("카페"),List.of(new DayDraft(1,null,List.of(places))),"AI",List.of(),null,null);}
    @Test void missingFinalMapPlacesAreFilledOnceAndSavedAcrossAllOptions() {
        int[] calls={0};
        var ai=new OpenAiItineraryClient(null,null,json) {
            @Override public JsonNode describeFinalPlaces(List<Map<String,String>> facts) {
                calls[0]++;assertEquals(2,facts.size());
                for(var fact:facts) {assertFalse(fact.containsKey("address"));assertFalse(fact.containsKey("visitTime"));assertFalse(fact.containsKey("note"));}
                return json.valueToTree(List.of(Map.of("id","1","reason","커피를 마시며 여행 중 잠깐 쉬어 갈 수 있어요."),Map.of("id","2","reason","식사 시간을 갖고 다음 활동을 준비할 수 있어요.")));
            }
        };
        var base=draft(place("1","카페",null,"카페"),place("2","식당","식사 일정 · 점심 · 냉면","식당"));
        var options=FinalRecommendations.fill(List.of(base,base,base),ai);
        assertEquals(1,calls[0]);
        for(var option:options) {
            var visits=option.days().get(0).places();
            assertFalse(FinalRecommendations.text(visits.get(0)).isBlank());
            assertTrue(visits.get(1).reason().startsWith("식사 일정 · 점심 · 냉면 · "));
            assertFalse(FinalRecommendations.text(visits.get(1)).isBlank());
            assertEquals("사용자 메모",visits.get(0).note());
        }
    }
    @Test void realSavedNarrativesAreKeptWithoutNewPaidCall() {
        var ai=new OpenAiItineraryClient(null,null,json) {@Override public JsonNode describeFinalPlaces(List<Map<String,String>> facts) {fail("Existing narratives must remain");return null;}};
        var base=draft(place("1","박물관","지역의 역사를 전시로 살펴볼 수 있어요.","문화"));
        assertEquals(base,FinalRecommendations.fill(List.of(base),ai).get(0));
        assertEquals("지역의 역사를 전시로 살펴볼 수 있어요.",FinalRecommendations.text(place("1","박물관","식사 일정 · 점심 · 냉면 · 지역의 역사를 전시로 살펴볼 수 있어요.","문화")));
    }
    @Test void failedDescriptionsDoNotEraseVisitsOrFabricateFoodClaims() {
        var ai=new OpenAiItineraryClient(null,null,json) {@Override public JsonNode describeFinalPlaces(List<Map<String,String>> facts) {throw new IllegalStateException("unavailable");}};
        var p=place("1","맥도날드",null,"식당");var result=FinalRecommendations.fill(List.of(draft(p)),ai).get(0);
        assertEquals(p,result.days().get(0).places().get(0));assertFalse(result.warnings().isEmpty());
        assertEquals("",FinalRecommendations.text(place("1","맥도날드","밥과 국이 기본으로 놓입니다.","식당")));
    }
}
