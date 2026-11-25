package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationRetryBudgetTests {
    private TripDraft draft(String time) {
        var place=new PlaceDraft("123","상점","부산",35.,129.,time,"","","쇼핑",true,null,null);
        return new TripDraft("여행","부산",null,null,null,List.of("쇼핑"),List.of(new DayDraft(1,null,List.of(place))),"AI",List.of());
    }
    @Test void identicalScheduleSkipsExternalVerificationButChangedTimeIsChecked() {
        var calls=new AtomicInteger();
        UnaryOperator<TripDraft> verify=d->{calls.incrementAndGet();return d;};
        assertNull(TripGenerationService.verifyChangedOption(draft("10:00"),draft("10:00"),verify));
        assertEquals(0,calls.get());
        assertNotNull(TripGenerationService.verifyChangedOption(draft("10:00"),draft("11:00"),verify));
        assertEquals(1,calls.get(),"A different visit time must be checked even if the place IDs are unchanged");
    }
    @Test void mealAndCountFailuresRepairTheFailedScheduleInsteadOfBuildingManyAlternatives() {
        var original=draft("10:00");
        assertSame(original,TripGenerationService.recoveryCandidate(original,Map.of(),0,"1일차에 필요한 식사 시간대를 채우지 못했습니다.",false));
        assertSame(original,TripGenerationService.recoveryCandidate(original,Map.of(),1,"식당 제외 방문 수 3곳이 유형 기준을 충족하지 못했습니다.",false));
    }
    @Test void unchangedFailuresCheckEachProfileOnceAndReturnEditableOptions() {
        verifyBudget(false,3);
    }
    @Test void changedRepairsFailingAgainStopAfterOneAdditionalCheckPerProfile() {
        verifyBudget(true,6);
    }
    private void verifyBudget(boolean changeDuringRouting,int expectedChecks) {
        var ai=new TripGenerationRulesTests.FakeAi(TripGenerationRulesTests.input(List.of(TripGenerationRulesTests.shop("상점B"))));
        var checks=new AtomicInteger();
        var hours=new BusinessHoursService(null,0) {
            @Override public TripDraft check(TripDraft draft) {checks.incrementAndGet();return null;}
        };
        RoutedItineraryService routes=changeDuringRouting?new RoutedItineraryService(null) {
            @Override GenerateRequest resolveTransport(GenerateRequest req){return req;}
            @Override RouteTimeClient.Session prefetch(List<TripDraft> drafts){return null;}
            @Override List<TripDraft> adjustOptions(List<TripDraft> drafts,Function<TripDraft,BiPredicate<PlaceDraft,String>> eligible,RouteTimeClient.Session session) {
                return drafts.stream().map(d->DraftVariants.withDays(d,d.days().stream().map(day->new DayDraft(day.dayNumber(),day.date(),day.places().stream()
                    .map(p->TripRouteOptimizer.at(p,TravelSchedule.clock(TravelSchedule.minute(p.visitTime())+1))).toList())).toList(),d.warnings(),null,null)).toList();
            }
        }:null;
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new TripGenerationRulesTests.FakePlaces(),routes,hours);
        try {
            var result=service.generateOptions(TripGenerationRulesTests.request());
            assertEquals(3,result.options().size());
            assertEquals(expectedChecks,checks.get(),"No further retries after unchanged or repeated failures");
            assertEquals(1,ai.calls);
            for(var option:result.options()) {
                assertEquals("BEST_EFFORT",option.draft().source());
                assertFalse(option.draft().days().isEmpty());
                assertDoesNotThrow(()->TravelSchedule.validateDraft(option.draft()));
            }
        }finally{service.shutdown();hours.close();}
    }
}
