package com.tripplanner.trip;
import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
class BusinessHoursServiceTests {
    @Test void excludesClosedButRetainsUnknownAndPreservesRequest() {
        var client=new TourApiClient(RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build(),"key","https://example.test") {
            @Override public BusinessHoursInfo lookup(String id,String name,String address,double lat,double lng,Budget budget) {
                return id.equals("1")?new BusinessHoursInfo("42","10:00~20:00","월요일",null,null,"UNKNOWN",null):BusinessHoursInfo.unknown("미확인");
            }
        };
        var service=new BusinessHoursService(client,10);
        var date=LocalDate.of(2026,11,2);
        var closed=new PlaceDraft("1","식당","주소",37.0,127.0,"12:00",null,null,"식당",true,null,null);
        var unknown=new PlaceDraft("2","카페","주소",37.0,127.0,"14:00",null,null,"카페",true,null,null);
        var draft=new TripDraft("여행","서울",date,date,null,List.of(),List.of(new DayDraft(1,date,List.of(closed,unknown))),"AI",List.of(),"요청").withRequestMetadata("요청","해석");
        try {
            var result=service.check(draft);
            assertEquals(1,result.days().get(0).places().size());
            assertEquals("UNKNOWN",result.days().get(0).places().get(0).businessHours().status());
            assertEquals("해석",result.interpretedRequest());assertFalse(result.warnings().isEmpty());
            var invalid=new TripDraft("여행","서울",date,date,null,List.of(),List.of(new DayDraft(1,date,List.of(closed.withBusinessHours(new BusinessHoursInfo("42","10:00~20:00","월요일",null,date.toString(),"CLOSED",null))))),"AI",List.of());
            assertThrows(com.tripplanner.common.ApiException.class,()->TravelSchedule.validateDraft(invalid));
        } finally { service.close(); }
    }
}
