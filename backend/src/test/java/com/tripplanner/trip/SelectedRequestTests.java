package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.ai.OpenAiItineraryClient;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectedRequestTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Map<String,Object> rule(String kind,String target,int value,boolean daily,int day,String category) {
        return Map.of("source",target+" · "+kind+" "+value,"kind",kind,"target",target,"value",value,
            "daily",daily,"day",day,"meal","","category",category,"aliases",List.of());
    }
    @SafeVarargs private static String request(Map<String,Object>... rules) throws Exception {
        return ExtraRequestRules.STRUCTURED+JSON.writeValueAsString(Map.of("rules",List.of(rules),"unknown",List.of()));
    }
    private static PlaceDraft place(String id,String category) {
        return new PlaceDraft(id,category+id,"부산",35.,129.,"12:00","","",category,true,null,null);
    }
    private static TripDraft draft(String request,List<PlaceDraft> first,List<PlaceDraft> second) {
        return new TripDraft("부산 여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,first),new DayDraft(2,null,second)),"AI",List.of(),request);
    }
    @Test void selectedRulesSkipLanguageInterpretationAndKeepTheirScope() throws Exception {
        var value=request(rule("EXACT","카페",1,true,0,"카페"),rule("EXCLUDE","다이소",0,false,0,""));
        // Null HTTP client makes an accidental interpretation network call fail this test.
        var props=new com.tripplanner.config.AppProperties(null,null,
            new com.tripplanner.config.AppProperties.OpenAi("test-key","test-model","https://example.invalid"),false,null,1,null);
        var ai=new OpenAiItineraryClient(null,props,JSON);
        assertEquals(value,ai.interpretRequest(value,2));
        assertTrue(ExtraRequestRules.violations(draft(value,List.of(place("1","카페")),List.of(place("2","카페")))).isEmpty());
        assertFalse(ExtraRequestRules.violations(draft(value,List.of(place("1","카페")),List.of())).isEmpty());
        assertFalse(ExtraRequestRules.allows(value,"다이소 부산역점","쇼핑"));
    }
    @Test void maximumCountsAreTargetSpecificAndRespectDailyWholeTripAndDayScope() throws Exception {
        var one=List.of(place("1","카페"),place("2","쇼핑"));
        var daily=request(rule("LIMIT","카페",1,true,0,"카페"));
        assertTrue(ExtraRequestRules.violations(draft(daily,one,List.of(place("3","카페")))).isEmpty());
        var whole=request(rule("LIMIT","카페",1,false,0,"카페"));
        assertFalse(ExtraRequestRules.violations(draft(whole,one,List.of(place("3","카페")))).isEmpty());
        assertFalse(ExtraRequestRules.allowsAdditional(whole,"카페3","카페",2,2,List.of(new ExtraRequestRules.Visit(1,"카페1","카페"))));
        assertTrue(ExtraRequestRules.allowsAdditional(daily,"카페3","카페",2,2,List.of(new ExtraRequestRules.Visit(1,"카페1","카페"))));
        var dayTwo=request(rule("LIMIT","카페",0,false,2,"카페"));
        assertTrue(ExtraRequestRules.violations(draft(dayTwo,one,List.of(place("3","쇼핑")))).isEmpty());
        assertFalse(ExtraRequestRules.allowsAdditional(dayTwo,"카페3","카페",2,2,List.of()));
        assertTrue(ExtraRequestRules.includePrompt(daily,2).contains("매일: 카페 최대 1곳"));
    }
    @Test void contradictoryCountsAndDatesFailBeforeGeneration() throws Exception {
        var incompatible=request(rule("INCLUDE","카페",2,true,0,"카페"),rule("LIMIT","카페",1,true,0,"카페"));
        assertTrue(assertThrows(RuntimeException.class,()->ExtraRequestRules.validateInput(incompatible,2)).getMessage().contains("서로 맞지"));
        var outside=request(rule("EXACT","횟집",1,false,3,"식당"));
        assertTrue(assertThrows(RuntimeException.class,()->ExtraRequestRules.validateInput(outside,2)).getMessage().contains("여행 기간"));
        var crossScope=request(rule("EXACT","카페",1,true,0,"카페"),rule("LIMIT","카페",1,false,0,"카페"));
        assertTrue(assertThrows(RuntimeException.class,()->ExtraRequestRules.validateInput(crossScope,2)).getMessage().contains("여행 전체"));
    }
    @Test void selectedRequestsCanBePersistedBeyondLegacyTwoHundredCharacterLimit() throws Exception {
        var value=request(rule("EXACT","카페",1,true,0,"카페"),rule("EXACT","횟집",2,false,0,"식당"),rule("EXCLUDE","다이소",0,false,0,""));
        assertTrue(value.length()>200);
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(new GenerateRequest("부산",null,2,null,List.of(),value)).isEmpty());
            assertTrue(factory.getValidator().validate(draft(value,List.of(place("1","카페")),List.of(place("2","카페")))) .isEmpty());
        }
    }
    @Test void mapSelectedBranchesUsePlaceIdsInsteadOfMatchingTheirSharedName() throws Exception {
        var include=new LinkedHashMap<>(rule("INCLUDE","같은 이름",1,false,0,"카페"));include.put("placeId","101");include.put("meal","PLACE");
        var exclude=new LinkedHashMap<>(rule("EXCLUDE","같은 이름",0,false,0,"카페"));exclude.put("placeId","102");exclude.put("meal","PLACE");
        var selected=request(include,exclude);
        ExtraRequestRules.validateInput(selected,2);
        assertTrue(ExtraRequestRules.violations(draft(selected,List.of(place("101","카페")),List.of(place("103","카페")))).isEmpty());
        assertFalse(ExtraRequestRules.violations(draft(selected,List.of(place("101","카페")),List.of(place("102","카페")))).isEmpty());
        assertTrue(ExtraRequestRules.allows(selected,"같은 이름","카페","103"));
        assertFalse(ExtraRequestRules.allows(selected,"같은 이름","카페","102"));
        assertEquals(10000,ExtraRequestRules.requiredPriority(selected,"같은 이름","카페","101"));
        assertEquals(0,ExtraRequestRules.requiredPriority(selected,"같은 이름","카페","103"));
    }
    @Test void otherCountTargetsMatchVerifiedMenuNamesAndBusinessCategories() throws Exception {
        for(String target:List.of("돼지국밥","냉면","횟집")) {
            var count=new LinkedHashMap<>(rule("EXACT",target,1,false,0,""));count.put("meal","COUNT");
            var selected=request(count);ExtraRequestRules.validateInput(selected,2);
            assertEquals("식당",ExtraRequestRules.includeSeeds(selected,"부산",1,2).get(0).category());
            var p=new PlaceDraft("201","현지 식당","부산",35.,129.,"12:00","","","식당",true,null,null,null,"음식점 > 한식 > "+target);
            assertTrue(ExtraRequestRules.violations(draft(selected,List.of(p),List.of())).isEmpty());
            var upper=new LinkedHashMap<>(rule("LIMIT",target,0,false,0,""));upper.put("meal","COUNT");
            assertFalse(ExtraRequestRules.violations(draft(request(upper),List.of(p),List.of())).isEmpty());
        }
    }
    @Test void fishLabelIsCanonicalAndDoesNotMatchUnrelatedWordsContainingHoe() throws Exception {
        var selected=request(rule("EXACT","횟집",1,false,0,"식당"));
        assertTrue(ExtraRequestRules.includePrompt(selected,2).contains("회 정확히 1곳"));
        assertFalse(ExtraRequestRules.includePrompt(selected,2).contains("횟집"));
        var meal=new ExtraRequestRules.Meal("회","회","저녁",1,1020,1320);
        assertFalse(meal.exact("시민회관 식당","음식점 > 한식"));
        assertTrue(meal.exact("항구수산","음식점 > 일식 > 회"));
    }
}
