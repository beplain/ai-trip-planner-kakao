package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.ai.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalRequestTests {
    static final String ORIGINAL="카페 추천 중에 하나는 메가커피로 추천해줘";
    static String plan(String source,int minimum,int day) {
        try {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("source",source);fields.put("kind","INCLUDE");fields.put("target","메가커피");fields.put("value",minimum);
            fields.put("daily",false);fields.put("day",day);fields.put("meal","");fields.put("category","카페");fields.put("aliases",List.of("메가MGC커피","메가엠지씨커피"));
            return ExtraRequestRules.structured(new ObjectMapper().valueToTree(Map.of("rules",List.of(fields),"unknown",List.of())));
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    static PlaceDraft cafe(String id,String name,String category) {
        return new PlaceDraft(id,name,"부산 해운대구",35.,129.,"10:00","","",category,true,null,null);
    }
    static TripDraft draft(String rules,PlaceDraft... places) {
        return new TripDraft("부산","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(places))),"AI",List.of(),ORIGINAL)
            .withRequestMetadata(ORIGINAL,rules);
    }
    static String menuPlan(String source,String kind,int count,boolean daily) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("source",source);fields.put("kind",kind);fields.put("target","돼지국밥");fields.put("value",count);
        fields.put("daily",daily);fields.put("day",0);fields.put("meal",kind.equals("MEAL")?"저녁":"");fields.put("category","식당");fields.put("aliases",List.of());
        return ExtraRequestRules.structured(new ObjectMapper().valueToTree(Map.of("rules",List.of(fields),"unknown",List.of())));
    }
    @Test void unspecifiedMenuIsExactlyOneAcrossTheWholeTripEvenIfAiSaysDaily() {
        for(String kind:List.of("INCLUDE","MEAL")) {
            var rules=menuPlan("돼지국밥 맛집을 추천해줘",kind,kind.equals("MEAL")?0:1,true);
            var one=cafe("1","부산 돼지국밥","식당");var two=cafe("2","해운대 돼지국밥","식당");var other=cafe("3","생선구이 식당","식당");
            var valid=new TripDraft("여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(one)),new DayDraft(2,null,List.of(other))),"AI",List.of()).withRequestMetadata("돼지국밥 맛집을 추천해줘",rules);
            assertTrue(ExtraRequestRules.violations(valid).isEmpty());
            var repeated=DraftVariants.withDays(valid,List.of(new DayDraft(1,null,List.of(one)),new DayDraft(2,null,List.of(two))),List.of(),null,null);
            assertFalse(ExtraRequestRules.violations(repeated).isEmpty());
            assertFalse(ExtraRequestRules.allowsAdditional(rules,two.name(),two.category(),2,2,List.of(new ExtraRequestRules.Visit(1,one.name(),one.category()))));
            assertTrue(ExtraRequestRules.allowsAdditional(rules,other.name(),other.category(),2,2,List.of(new ExtraRequestRules.Visit(1,one.name(),one.category()))));
        }
    }
    @Test void explicitMenuCountAndDailyRequestsKeepTheirMeaning() {
        var two=menuPlan("돼지국밥 식당 두 곳 추천해줘","EXACT",2,false);
        assertTrue(ExtraRequestRules.includePrompt(two,2).contains("정확히 2곳"));
        var daily=menuPlan("매일 돼지국밥 한 곳 추천해줘","EXACT",1,true);
        assertTrue(ExtraRequestRules.includePrompt(daily,2).contains("매일"));
    }
    @Test void semanticConditionsMatchRealBrandAliasesAndMinimumCountsRatherThanLiteralSentences() {
        var rules=plan(ORIGINAL,1,0);
        assertDoesNotThrow(()->ExtraRequestRules.validateInput(rules,2));
        assertTrue(ExtraRequestRules.violations(draft(rules,cafe("1","메가MGC커피 부산역점","카페"))).isEmpty());
        assertFalse(ExtraRequestRules.violations(draft(rules,cafe("2","다른 카페","카페"))).isEmpty());
        assertFalse(ExtraRequestRules.violations(draft(rules,cafe("3","메가MGC커피 부산역점","쇼핑"))).isEmpty());
        assertFalse(ExtraRequestRules.violations(draft(plan("메가커피 최소 2곳 포함",2,0),cafe("1","메가MGC커피 부산역점","카페"))).isEmpty());
        assertTrue(ExtraRequestRules.violations(draft(plan("메가커피 최소 2곳 포함",2,0),cafe("1","메가MGC커피 부산역점","카페"),cafe("2","메가엠지씨커피 해운대점","카페"))).isEmpty());
        assertTrue(ExtraRequestRules.checks(draft(rules,cafe("1","메가MGC커피 부산역점","카페"))).get(0).detail().contains("최소 1곳"));
    }
    @Test void interpretationsSurviveJsonStorageRenamesAndVariantSwitching() throws Exception {
        var original=draft(plan(ORIGINAL,1,0),cafe("1","메가MGC커피 부산역점","카페"));
        var mapper=new ObjectMapper();
        var decoded=mapper.readValue(mapper.writeValueAsString(original),TripDraft.class);
        var copied=DraftVariants.withDays(decoded,decoded.days(),decoded.warnings(),null,null);
        assertEquals(ORIGINAL,copied.request());
        assertEquals(original.interpretedRequest(),copied.interpretedRequest());
        assertTrue(ExtraRequestRules.violations(copied).isEmpty());
        assertFalse(mapper.writeValueAsString(copied).contains("effectiveRequest"));
    }
    @Test void mandatoryCafeIsSearchedEvenWhenAiNeverRecommendedTheBrand() {
        var queries=new CopyOnWriteArrayList<String>();
        var maps=new KakaoLocalClient(null,null) {
            @Override public boolean enabled(){return true;}
            @Override public Optional<Area> resolveArea(String destination){return Optional.of(new Area(35.,129.,"부산"));}
            @Override public List<PlaceHit> search(String query,Area area,int size) {
                queries.add(query);
                if(!query.contains("메가")) return List.of(new PlaceHit("456","동네카페","부산 동구",35.,129.,null,null,"카페",0,"CE7","음식점 > 카페 > 커피전문점"));
                return List.of(new PlaceHit("123","메가MGC커피 부산역점","부산 동구",35.,129.,null,null,"카페",0,"CE7","음식점 > 카페 > 커피전문점"));
            }
        };
        var ai=new OpenAiItineraryClient(null,null,new ObjectMapper()) {
            @Override public boolean enabled(){return true;}
            @Override public String interpretRequest(String text,int days){return plan(text,1,0);}
            @Override public AiItinerary generate(ItineraryRequest req,String feedback){throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"AI_FAILED","응답 없음");}
        };
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),maps);
        try {
            for(String text:List.of(ORIGINAL,"메가커피 최소 1곳 포함")) {
                var options=service.generateOptions(new GenerateRequest("부산",null,1,null,List.of(),text));
                assertFalse(options.options().isEmpty());
                for(var option:options.options()) {
                    assertTrue(option.draft().days().stream().flatMap(d->d.places().stream()).anyMatch(p->p.name().equals("메가MGC커피 부산역점")));
                    assertEquals(text,option.draft().request());
                    assertTrue(ExtraRequestRules.violations(option.draft()).isEmpty());
                }
            }
            assertTrue(queries.contains("부산 메가커피"));
            assertTrue(queries.contains("부산 메가MGC커피"));
            assertTrue(queries.contains("부산 메가엠지씨커피"));
        } finally { service.shutdown(); }
    }
    @Test void explicitDayIsVerifiedAndMalformedConditionsCannotSilentlyPass() {
        var rules=plan(ORIGINAL,1,2);
        assertFalse(ExtraRequestRules.violations(draft(rules,cafe("1","메가MGC커피 부산역점","카페"))).isEmpty());
        assertThrows(ApiException.class,()->ExtraRequestRules.validateInput(rules,1));
        assertThrows(RuntimeException.class,()->plan(ORIGINAL,0,0));
    }
}
