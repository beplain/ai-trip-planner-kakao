package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.place.GoogleOpeningHours;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequiredPlaceClosureTests {
    private final PlaceDraft place=new PlaceDraft("required","정동문화사","대전 동구",36.33,127.43,"10:00","","","카페",true,null,null);
    private TripDraft draft(int days,String request) {
        var date=LocalDate.of(2026,11,23);
        return new TripDraft("대전 여행","대전",date,date.plusDays(days-1),null,List.of("맛집","쇼핑"),
                java.util.stream.IntStream.rangeClosed(1,days).mapToObj(n->new DayDraft(n,date.plusDays(n-1),List.<PlaceDraft>of())).toList(),
                "AI",List.of(),request,null);
    }
    @Test void explainsRequiredPlaceAndClosedTravelDate() {
        String reason=ExtraRequestRules.closedRequiredPlaceReason(draft(1,"정동문화사 넣어줘"),List.of(place),(d,p)->GoogleOpeningHours.State.CLOSED);
        assertNotNull(reason); assertTrue(reason.contains("정동문화사")); assertTrue(reason.contains("2026-11-23(월)"));
        assertTrue(reason.contains("휴무일과 겹칩니다"));
    }
    @Test void unknownMissingAndOpenEvidenceCannotBeCalledClosed() {
        for(var state:List.of(GoogleOpeningHours.State.OPEN,GoogleOpeningHours.State.UNKNOWN))
            assertNull(ExtraRequestRules.closedRequiredPlaceReason(draft(1,"정동문화사 넣어줘"),List.of(place),(d,p)->state));
        assertNull(ExtraRequestRules.closedRequiredPlaceReason(draft(1,"정동문화사 넣어줘"),List.of(),(d,p)->GoogleOpeningHours.State.CLOSED));
        assertNull(ExtraRequestRules.closedRequiredPlaceReason(draft(1,"정동문화사 제외해줘"),List.of(place),(d,p)->GoogleOpeningHours.State.CLOSED));
    }
    @Test void anotherOpenTravelDayAllowsRescheduling() {
        assertNull(ExtraRequestRules.closedRequiredPlaceReason(draft(2,"정동문화사 넣어줘"),List.of(place),
                (d,p)->d.dayNumber()==1?GoogleOpeningHours.State.CLOSED:GoogleOpeningHours.State.OPEN));
    }
    @Test void narrowOpeningWindowIsNotMistakenForAClosedDay() throws Exception {
        var json=new ObjectMapper().readTree("{\"regularOpeningHours\":{\"periods\":[{\"open\":{\"day\":1,\"hour\":12,\"minute\":3},\"close\":{\"day\":1,\"hour\":12,\"minute\":7}}]}}");
        var hours=new GoogleOpeningHours(json,LocalDate.of(2026,11,20));
        assertEquals(GoogleOpeningHours.State.OPEN,hours.dayState(LocalDate.of(2026,11,23)));
        assertEquals(GoogleOpeningHours.State.CLOSED,hours.dayState(LocalDate.of(2026,11,24)));
        assertEquals(GoogleOpeningHours.State.UNKNOWN,GoogleOpeningHours.unknown("조회 실패").dayState(LocalDate.of(2026,11,23)));
    }
}
