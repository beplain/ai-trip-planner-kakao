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

    @Test void largeDescriptionRequestsRunInParallelWithoutLosingPlacesOrValidation() {
        var entered=new java.util.concurrent.CountDownLatch(8);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var requested=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var ai=new OpenAiItineraryClient(null,null,json) {
            @Override public JsonNode describeFinalPlaces(List<Map<String,String>> facts) {
                int now=active.incrementAndGet();peak.accumulateAndGet(now,Math::max);entered.countDown();
                try {
                    assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS),"All eight batches must start before waiting for one response");
                    var lines=json.createArrayNode();
                    for(var fact:facts) {
                        assertTrue(requested.add(fact.get("id")),"A place must be described once across all types");
                        assertFalse(fact.containsKey("address"));
                        lines.addObject().put("id",fact.get("id")).put("reason","전시 공간 "+fact.get("id")+"에서 작품을 둘러보며 여행의 관심사를 넓혀 보세요.");
                    }
                    return lines;
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                finally{active.decrementAndGet();}
            }
        };
        var visits=new ArrayList<PlaceDraft>();for(int i=0;i<48;i++)visits.add(place("id"+i,"공간"+i,null,"문화"));
        var base=draft(visits.toArray(PlaceDraft[]::new));
        var output=FinalRecommendations.fill(List.of(base,base,base),ai);
        assertEquals(8,peak.get());assertEquals(48,requested.size());
        for(var option:output)for(var p:option.days().get(0).places()) {
            assertFalse(FinalRecommendations.text(p).isBlank());assertEquals("사용자 메모",p.note());
        }
    }


    @Test void preparedDescriptionsFollowTheSameVenueWithoutOverwritingNotesOrMealLabels() {
        var prepared=draft(place("1","식당","지역을 둘러보는 중 잠깐 식사 시간을 갖기 좋은 곳이에요.","식당"));
        var finalDraft=draft(place("1","식당","식사 일정 · 점심 · 냉면","식당"));
        var reused=FinalRecommendations.reuseDescriptions(List.of(finalDraft),List.of(prepared)).get(0).days().get(0).places().get(0);
        assertTrue(reused.reason().startsWith("식사 일정 · 점심 · 냉면 · "));assertEquals("사용자 메모",reused.note());
        var renamed=draft(place("1","다른 지점",null,"식당"));
        assertTrue(FinalRecommendations.text(FinalRecommendations.reuseDescriptions(List.of(renamed),List.of(prepared)).get(0).days().get(0).places().get(0)).isBlank());
    }
}
