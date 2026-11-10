package com.tripplanner.place;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class GoogleOpeningHoursTests {
    private final ObjectMapper json=new ObjectMapper();
    private GoogleOpeningHours hours(String fields)throws Exception{return new GoogleOpeningHours(json.readTree("{"+fields+"}"),LocalDate.of(2026,11,4));}
    private String period(int od,int oh,int om,int cd,int ch,int cm){return "{\"open\":{\"day\":"+od+",\"hour\":"+oh+",\"minute\":"+om+"},\"close\":{\"day\":"+cd+",\"hour\":"+ch+",\"minute\":"+cm+"}}";}
    @Test void rejectsNoonForThreePmOpeningAndRequiresWholeVisit()throws Exception {
        var h=hours("\"regularOpeningHours\":{\"periods\":["+period(1,15,0,1,22,0)+"]}");
        var date=LocalDate.of(2026,11,23);
        assertEquals(GoogleOpeningHours.State.CLOSED,h.assess(date,"12:00",60).state());
        assertTrue(h.assess(date,"15:00",60).open());
        assertTrue(h.assess(date,"21:00",60).open());
        assertFalse(h.assess(date,"21:10",60).open());
        assertFalse(h.assess(date.plusDays(1),"15:00",60).open());
        assertTrue(h.assess(date,"15:00",60).regularOnly());
    }
    @Test void handlesBreaksAndOvernightAcrossWeekBoundary()throws Exception {
        var h=hours("\"regularOpeningHours\":{\"periods\":["+period(1,11,0,1,15,0)+","+period(1,17,0,1,22,0)+","+period(6,20,0,0,2,0)+"]}");
        assertFalse(h.assess(LocalDate.of(2026,11,23),"14:30",60).open());
        assertTrue(h.assess(LocalDate.of(2026,11,23),"17:00",60).open());
        assertTrue(h.assess(LocalDate.of(2026,11,22),"00:30",60).open());
        assertFalse(h.assess(LocalDate.of(2026,11,22),"01:30",60).open());
    }
    @Test void currentSpecialClosureOverridesRegularHoursOnlyWithinSevenDays()throws Exception {
        var h=hours("\"currentOpeningHours\":{\"periods\":[]},\"regularOpeningHours\":{\"periods\":["+period(4,10,0,4,20,0)+"]}");
        assertFalse(h.assess(LocalDate.of(2026,11,5),"12:00",60).open());
        assertTrue(h.assess(LocalDate.of(2026,11,12),"12:00",60).open());
    }
    @Test void currentDatedHoursArePreferredToRegularSchedule()throws Exception {
        var h=hours("\"currentOpeningHours\":{\"periods\":[{\"open\":{\"day\":4,\"hour\":15,\"minute\":0,\"date\":{\"year\":2026,\"month\":11,\"day\":5}},\"close\":{\"day\":4,\"hour\":20,\"minute\":0,\"date\":{\"year\":2026,\"month\":11,\"day\":5}}}]},\"regularOpeningHours\":{\"periods\":["+period(4,10,0,4,20,0)+"]}");
        assertFalse(h.assess(LocalDate.of(2026,11,5),"12:00",60).open());
        assertTrue(h.assess(LocalDate.of(2026,11,5),"15:00",60).open());
        assertFalse(h.assess(LocalDate.of(2026,11,5),"15:00",60).regularOnly());
    }
    @Test void unknownHoursAreNeverAssumedOpen()throws Exception {
        assertEquals(GoogleOpeningHours.State.UNKNOWN,hours("").assess(LocalDate.of(2026,11,23),"12:00",60).state());
        assertEquals(GoogleOpeningHours.State.UNKNOWN,hours("\"regularOpeningHours\":{\"periods\":[{}]}").assess(LocalDate.of(2026,11,23),"12:00",60).state());
        assertFalse(hours("\"businessStatus\":\"CLOSED_TEMPORARILY\"").assess(LocalDate.of(2026,11,23),"12:00",60).open());
    }
    @Test void supportsDocumentedTwentyFourHourConvention()throws Exception {
        var h=hours("\"regularOpeningHours\":{\"periods\":[{\"open\":{\"day\":0,\"hour\":0,\"minute\":0}}]}");
        assertTrue(h.assess(LocalDate.of(2026,11,23),"23:30",60).open());
    }
}
