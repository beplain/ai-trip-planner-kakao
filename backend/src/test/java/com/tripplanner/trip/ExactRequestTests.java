package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.ai.*;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExactRequestTests {
    static final String TEXT="카페 한 곳만 메가커피로 추천해줘";
    static String rules(boolean daily,int count) {
        try {
            var mapper=new ObjectMapper();
            var plan=mapper.readTree(NaturalRequestTests.plan(TEXT,count,0).substring(ExtraRequestRules.STRUCTURED.length()));
            ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("rules").path(0)).put("kind","EXACT").put("daily",daily);
            return ExtraRequestRules.structured(plan);
        } catch(Exception e){throw new RuntimeException(e);}
    }
    static TripDraft twoDays(String rule,String first,String second) {
        return new TripDraft("부산","부산",null,null,null,List.of("카페"),List.of(
            new DayDraft(1,null,List.of(NaturalRequestTests.cafe("1",first,"카페"))),
            new DayDraft(2,null,List.of(NaturalRequestTests.cafe("2",second,"카페")))),"AI",List.of(),TEXT).withRequestMetadata(TEXT,rule);
    }
    @Test void exactlyOneIsAWholeTripUpperAndLowerBoundNotADailyMinimum() {
        assertFalse(ExtraRequestRules.violations(twoDays(rules(false,1),"메가MGC커피 부산역점","메가커피 해운대점")).isEmpty());
        assertTrue(ExtraRequestRules.includePrompt(rules(false,1),2).contains("여행 전체 2일 합계: 메가커피 정확히 1곳"));
        assertTrue(ExtraRequestRules.includePrompt(rules(false,1),2).contains("나머지 카페는 다른 장소"));
        var valid=twoDays(rules(false,1),"메가MGC커피 부산역점","개인 카페");
        assertTrue(ExtraRequestRules.violations(valid).isEmpty());
        assertTrue(ExtraRequestRules.checks(valid).get(0).detail().contains("정확히 1곳"));
        assertFalse(ExtraRequestRules.violations(twoDays(rules(false,1),"개인 카페 A","개인 카페 B")).isEmpty());
        assertTrue(ExtraRequestRules.violations(twoDays(rules(true,1),"메가MGC커피 부산역점","메가커피 해운대점")).isEmpty());
        // Explicit minimum counts remain a separate condition and may exceed one.
        assertTrue(ExtraRequestRules.violations(twoDays(NaturalRequestTests.plan("최소 1곳 포함",1,0),"메가MGC커피 부산역점","메가커피 해운대점")).isEmpty());
    }
    @Test void quotaPersistsAcrossDaysAndAliasesButDoesNotExcludeOtherCafes() {
        var prior=List.of(new ExtraRequestRules.Visit(1,"메가MGC커피 부산역점","카페"));
        assertFalse(ExtraRequestRules.allowsAdditional(rules(false,1),"메가엠지씨커피 해운대점","카페",2,2,prior));
        assertTrue(ExtraRequestRules.allowsAdditional(rules(false,1),"바다 개인카페","카페",2,2,prior));
        assertTrue(ExtraRequestRules.allowsAdditional(rules(true,1),"메가엠지씨커피 해운대점","카페",2,2,prior));
    }
    static class Maps extends KakaoLocalClient {
        Maps(){super(null,null);}
        @Override public boolean enabled(){return true;}
        @Override public Optional<Area> resolveArea(String destination){return Optional.of(new Area(35.,129.,"부산"));}
        @Override public List<PlaceHit> search(String query,Area area,int size){
            List<PlaceHit> result=new ArrayList<>();
            if(query.contains("메가") || query.contains("카페")) {
                for(int i=0;i<5;i++)result.add(new PlaceHit("mega"+i,"메가MGC커피 부산"+i+"점","부산 해운대구",35.,129.,null,null,"카페",0,"CE7","음식점 > 카페 > 커피전문점"));
                if(query.contains("카페"))for(int i=0;i<10;i++)result.add(new PlaceHit("local"+i,"개인카페 "+i,"부산 해운대구",35.,129.,null,null,"카페",0,"CE7","음식점 > 카페 > 커피전문점"));
            }
            return result;
        }
    }
    @Test void repairsAllMegaAiRecommendationsAndKeepsOtherCafesInEveryVariant() {
        var ai=new OpenAiItineraryClient(null,null,new ObjectMapper()){
            @Override public boolean enabled(){return true;}
            @Override public String interpretRequest(String text,int days){return rules(false,1);}
            @Override public AiItinerary generate(ItineraryRequest request,String feedback){
                return new AiItinerary("부산",List.of(new AiItinerary.AiDay(1,List.of(mega("A","10:00"),mega("B","12:00")),List.of()),
                    new AiItinerary.AiDay(2,List.of(mega("C","10:00"),mega("D","12:00")),List.of())));
            }
        };
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new Maps());
        try {
            var result=service.generateOptions(new GenerateRequest("부산",null,2,null,List.of("카페"),TEXT));
            assertFalse(result.options().isEmpty());
            for(var option:result.options()) {
                var cafes=option.draft().days().stream().flatMap(d->d.places().stream()).filter(p->"카페".equals(p.category())).toList();
                assertEquals(1,cafes.stream().filter(p->p.name().startsWith("메가")).count());
                assertTrue(cafes.stream().anyMatch(p->p.name().startsWith("개인카페")),"Do not satisfy the request by removing all other cafes");
                assertTrue(option.draft().days().stream().allMatch(d->d.places().stream().anyMatch(p->"카페".equals(p.category()))));
                assertTrue(ExtraRequestRules.violations(option.draft()).isEmpty());
            }
        } finally {service.shutdown();}
    }
    static AiItinerary.AiPlace mega(String suffix,String time){return new AiItinerary.AiPlace("메가커피 "+suffix,"부산 메가커피",time,"카페","","",List.of("카페"));}
}
