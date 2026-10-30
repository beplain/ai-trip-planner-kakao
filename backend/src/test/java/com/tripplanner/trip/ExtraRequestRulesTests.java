package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtraRequestRulesTests {
    static PlaceDraft visit(String id,String name,String category,String time) {
        return new PlaceDraft(id,name,"부산",35.,129.,time,"다이소는 제외했음","",category,true,null,null);
    }
    static TripDraft draft(String request,PlaceDraft... places) {
        return new TripDraft("부산","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(places))),"AI",List.of(),request);
    }
    @Test void brandExclusionsMatchBranchesWhitespaceAndLatinAliasesButNotNotes() {
        for(String request:List.of("다이소 제외","다이소는 추천하지 말아 주세요","다이소를 추천하지 말아줘","다이소 매장은 빼주세요")) {
            ExtraRequestRules.validateInput(request);
            assertFalse(ExtraRequestRules.allows(request,"다이소 부산역점","쇼핑"));
            assertFalse(ExtraRequestRules.allows(request,"DAISO 부산점","쇼핑"));
            assertTrue(ExtraRequestRules.allows(request,"롯데백화점 부산본점","쇼핑"));
            assertEquals("UNMET",ExtraRequestRules.checks(draft(request,visit("1","다 이 소 부산점","쇼핑","10:00"))).get(0).status());
            assertEquals("APPLIED",ExtraRequestRules.checks(draft(request,visit("2","롯데백화점","쇼핑","10:00"))).get(0).status());
        }
    }
    @Test void categoryExclusionChecksRealCategoryRatherThanNameSubstrings() {
        assertFalse(ExtraRequestRules.allows("식당 제외","바다마루","식당"));
        assertTrue(ExtraRequestRules.allows("식당 제외","식당골목 박물관","문화"));
        assertFalse(ExtraRequestRules.allows("카페는 방문하지 말아주세요","이디야","카페"));
    }
    @Test void mandatoryVisitAndDailyCoverageCannotBeClaimedWithoutVerifiedPlaces() {
        var p=visit("1","해운대해수욕장","관광","10:00");
        assertEquals("APPLIED",ExtraRequestRules.checks(draft("해운대해수욕장 꼭 포함",p)).get(0).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft("해운대해수욕장 꼭 포함",visit("2","광안리해수욕장","관광","10:00"))).get(0).status());
        var d=new TripDraft("부산","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(p)),new DayDraft(2,null,List.of(visit("2","광안리","관광","10:00")))) ,"AI",List.of(),"매일 카페 포함");
        assertEquals("UNMET",ExtraRequestRules.checks(d).get(0).status());
    }
    @Test void timeAndDailyLimitChecksRecomputeAfterUserEdits() {
        String request="매일 10시 이후 시작; 18시 전에 마무리; 하루 최대 2곳 이하";
        ExtraRequestRules.validateInput(request);
        var d=draft(request,visit("1","쇼핑몰","쇼핑","10:00"),visit("2","박물관","문화","17:00"));
        assertTrue(ExtraRequestRules.checks(d).stream().allMatch(c->c.status().equals("APPLIED")));
        assertEquals("UNMET",ExtraRequestRules.checks(draft(request,visit("1","쇼핑몰","쇼핑","09:50"))).get(0).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft(request,visit("1","박물관","문화","17:10"))).get(1).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft(request,visit("1","A","관광","10:00"),visit("2","B","관광","12:00"),visit("3","C","관광","14:00"))).get(2).status());
    }
    @Test void ambiguousUnsupportedAndConflictingRequestsFailBeforeAi() {
        for(String request:List.of("많이 걷지 않게","알레르기 없는 식당 추천","다이소 같은 곳 제외","!!!","다이소 제외; 다이소 꼭 포함"))
            assertThrows(ApiException.class,()->ExtraRequestRules.validateInput(request),request);
        var ai=new TripGenerationRulesTests.FakeAi(null);
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new TripGenerationRulesTests.FakePlaces());
        try {
            assertThrows(ApiException.class,()->service.generate(new GenerateRequest("부산",null,1,null,List.of(),"많이 걷지 않게")));
            assertEquals(0,ai.calls);
        } finally { service.shutdown(); }
        assertEquals("UNVERIFIABLE",ExtraRequestRules.checks(draft("편하게",visit("1","A","관광","10:00"))).get(0).status());
    }
    static class Maps extends TripGenerationRulesTests.FakePlaces {
        @Override public List<PlaceHit> search(String q,Area area,int size) {
            boolean food=q.contains("식당") || q.contains("맛집");
            if(q.contains("카페")) return List.of();
            if(food) return List.of(PlaceCategoryGenerationTests.hit("food-"+q,"식당A","음식점","FD6","음식점 > 한식"));
            return List.of(PlaceCategoryGenerationTests.hit("daiso","다이소 부산역점","","","가정,생활 > 생활용품점"),
                PlaceCategoryGenerationTests.hit("shop-"+q,"부산백화점","","","가정,생활 > 백화점"));
        }
    }
    @Test void primaryMapResultsAndAllGeneratedOptionsExcludeProhibitedBrand() {
        var ai=new TripGenerationRulesTests.FakeAi(new AiItinerary("부산",List.of(new AiDay(1,List.of(TripGenerationRulesTests.shop("상점A"),TripGenerationRulesTests.food("식당A")),List.of(TripGenerationRulesTests.shop("상점B"))))));
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new Maps());
        try {
            var options=service.generateOptions(new GenerateRequest("부산",null,1,null,List.of("쇼핑"),"다이소 추천하지 말아주세요"));
            assertFalse(options.options().isEmpty());
            for(var option:options.options()) {
                assertTrue(option.draft().days().stream().flatMap(d->d.places().stream()).noneMatch(p->p.name().contains("다이소")));
                assertTrue(ExtraRequestRules.violations(option.draft()).isEmpty());
            }
        } finally { service.shutdown(); }
    }
    @Test void recoverySearchAndVariantPoolCannotReintroduceBrand() {
        var ai=new TripGenerationRulesTests.FakeAi(null) { @Override public AiItinerary generate(ItineraryRequest r,String f) { throw ApiException.badRequest("AI 실패"); } @Override public boolean enabled(){return false;} };
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var request=new GenerateRequest("부산",null,1,null,List.of("쇼핑"),"다이소 제외");
            var candidates=new HashMap<Integer,List<PlaceDraft>>();
            var d=new ConfirmedItineraryFallback(new Maps(),pool).generate(request,null,false,candidates);
            assertTrue(d.days().stream().flatMap(day->day.places().stream()).noneMatch(p->p.name().contains("다이소")));
            assertTrue(candidates.values().stream().flatMap(List::stream).noneMatch(p->p.name().contains("다이소")));
            candidates.put(1,List.of(visit("bad","다이소 부산점","쇼핑","10:00")));
            assertTrue(DraftOptionComposer.compose(d,candidates).stream().flatMap(x->x.days().stream()).flatMap(x->x.places().stream()).noneMatch(p->p.name().contains("다이소")));
        } finally { pool.shutdownNow(); }
    }
    @Test void repairSchedulingActuallyRespectsRequestedHoursAndVisitLimit() {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var request=new GenerateRequest("부산",null,1,null,List.of("쇼핑"),"매일 10시 이후 시작; 18시 전에 마무리; 하루 최대 2곳 이하");
            var d=new ConfirmedItineraryFallback(new Maps(),pool).generate(request,null,false);
            assertFalse(d.days().get(0).places().isEmpty());
            assertTrue(d.days().get(0).places().size()<=2);
            assertTrue(ExtraRequestRules.violations(d).isEmpty());
        } finally { pool.shutdownNow(); }
    }
    @Test void reportEndpointUsesDisplayedVisitsAndDoesNotTrustAnOldSuccessFlag() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ExtraRequestController()).build();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var prohibited=draft("다이소 제외",visit("1","다이소 부산점","쇼핑","10:00"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/trips/requests/check")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(prohibited)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].status").value("UNMET"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/trips/requests/check")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(draft("다이소 제외",visit("2","롯데백화점","쇼핑","10:00")))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].status").value("APPLIED"));
    }
    @Test void impossibleRequirementsAreRejectedInsteadOfReturningAnUnmetDraft() {
        assertThrows(ApiException.class,()->ExtraRequestRules.require(draft("해운대해수욕장 포함",visit("1","광안리","관광","10:00"))));
        assertThrows(ApiException.class,()->ExtraRequestRules.require(draft("다이소 제외",visit("1","다이소","쇼핑","10:00"))));
        assertDoesNotThrow(()->ExtraRequestRules.require(draft(null,visit("1","A","관광","10:00"))));
    }
}
