package com.tripplanner.trip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class FlightTimetableClientTests {
    private static final LocalDate DATE=LocalDate.of(2026,11,26);
    private static final String AIRPORTS="""
        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{"airportId":"GMP","airportNm":"김포국제공항"},{"airportId":"CJU","airportNm":"제주국제공항"}]}}}}
        """;
    private static final String ROW="""
        {"airlineNm":"아시아나항공","vihicleId":"OZ8911","depPlandTime":202611260705,"arrPlandTime":202611260820}
        """;
    private static SelectedPlace place(String id,String name) { return new SelectedPlace(id,name,"공항 주소",33.5,126.5,null); }
    private static FlightOption option(boolean back) { return new FlightOption(back?"back":"out",DATE,"항공사",back?"A2":"A1",back?"CJU":"GMP",back?"GMP":"CJU",back?"제주국제공항":"김포국제공항",back?"김포국제공항":"제주국제공항",back?"20:00":"07:05",back?"21:10":"08:20"); }
    @Test void parsesNumericTimestampsAndRejectsOvernightOrWrongDates() throws Exception {
        var mapper=new ObjectMapper(); var dep=new Airport("GMP","김포국제공항",null); var arr=new Airport("CJU","제주국제공항",null);
        var row=mapper.readTree(ROW); var f=FlightTimetableClient.parse(row,DATE,dep,arr);
        assertEquals("07:05",f.departureTime()); assertEquals("08:20",f.arrivalTime());
        assertNull(FlightTimetableClient.parse(row,DATE.plusDays(1),dep,arr));
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("arrPlandTime",202611270020L);
        assertNull(FlightTimetableClient.parse(row,DATE,dep,arr));
    }
    @Test void handlesSingletonAndDuplicateRowsWithCorrectCaseEndpoint() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/GetArprtList?"))).andRespond(withSuccess(AIRPORTS,MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/GetFlightOpratInfoList?"))).andExpect(queryParam("depPlandTime","20261126"))
            .andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":2,\"items\":{\"item\":["+ROW+","+ROW+"]}}}}",MediaType.APPLICATION_JSON));
        var client=new FlightTimetableClient("test","map",builder.build());
        assertEquals(1,client.options(new FlightLookupRequest(DATE,"GMP","CJU")).size());
        server.verify();
    }
    @Test void separatesEmptyScheduleFromUpstreamFailureAndDoesNotLeakKey() {
        var b=RestClient.builder(); var server=MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(containsString("GetArprtList"))).andRespond(withSuccess(AIRPORTS,MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("GetFlightOpratInfoList"))).andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("GetFlightOpratInfoList"))).andRespond(withServerError());
        var client=new FlightTimetableClient("secret-test-key","map",b.build());
        assertTrue(client.options(new FlightLookupRequest(DATE,"GMP","CJU")).isEmpty());
        var error=assertThrows(ApiException.class,()->client.options(new FlightLookupRequest(DATE,"GMP","CJU")));
        assertFalse(error.getMessage().contains("secret-test-key")); server.verify();
    }
    @Test void usesDomesticTerminalInsteadOfInternationalOrParkingAndKeepsAirportIdentity() {
        var b=RestClient.builder(); var server=MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(containsString("GetArprtList"))).andRespond(withSuccess(AIRPORTS,MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("keyword.json"))).andRespond(withSuccess("""
          {"documents":[
            {"id":"9","place_name":"김포국제공항 국제선","category_name":"교통,수송 > 교통시설 > 공항","x":"126.8","y":"37.5","address_name":"서울"},
            {"id":"8","place_name":"김포국제공항 국내선 제1주차장","category_name":"교통 > 공항주차장","x":"126.8","y":"37.5","address_name":"서울"},
            {"id":"1","place_name":"김포국제공항 국내선","category_name":"교통,수송 > 교통시설 > 공항","x":"126.8","y":"37.5","address_name":"서울"}]}
          """,MediaType.APPLICATION_JSON));
        var client=new FlightTimetableClient("key","map",b.build());
        var selected=client.location("GMP"); assertEquals("1",selected.placeId()); assertEquals("김포국제공항",selected.name());
        assertEquals(selected,client.location("GMP")); server.verify();
    }
    @Test void resolvesBothLegsPreservesFlightsInStorageAndAppliesAirportBuffers() {
        var flights=mock(FlightTimetableClient.class); var out=option(false); var back=option(true);
        var gmp=place("1","김포국제공항"); var cju=place("2","제주국제공항");
        when(flights.options(new FlightLookupRequest(DATE,"GMP","CJU"))).thenReturn(List.of(out));
        when(flights.options(new FlightLookupRequest(DATE,"CJU","GMP"))).thenReturn(List.of(back));
        when(flights.location("GMP")).thenReturn(gmp); when(flights.location("CJU")).thenReturn(cju);
        var home=place("3","출발 장소"); var hotel=place("4","제주 숙소");
        var t=new Transport(TransportMode.PLANE,cju.name(),"09:00",hotel.name(),"19:00",cju,hotel,home,"08:00",true,null,gmp,null,"22:00",true,gmp,cju,null,null,out,back);
        var request=new GenerateRequest("제주",DATE,1,null,List.of("맛집"),null,new TravelDetails(MovementMode.CAR,List.of(),t));
        var resolved=new TransportArrivalService(null,null,flights).resolve(request).travelDetails();
        assertEquals("08:20",resolved.transport().arrivalTime()); assertEquals("20:00",resolved.transport().departureTime());
        assertEquals(560,TravelSchedule.start(1,resolved)); assertEquals(1080,TravelSchedule.end(1,1,resolved));
        assertEquals(1080,TravelSchedule.events(resolved,1).stream().filter(e->e.id().equals("transport:boarding")).findFirst().orElseThrow().minute());
        assertEquals(cju,TravelSchedule.events(resolved,1).stream().filter(e->e.id().equals("transport:boarding")).findFirst().orElseThrow().selectedPlace());
        assertTrue(TravelSchedule.prompt(resolved,1).contains("김포국제공항"));
        var converter=new TravelDetailsConverter(); assertEquals(resolved,converter.convertToEntityAttribute(converter.convertToDatabaseColumn(resolved)));
        assertEquals(out,resolved.transport().withStations(null,null).withAlightingStations(null,null).flight());
    }
    @Test void recommendsTwoClosestVerifiedAirportsAndPreservesTheirIdentity() {
        var builder=RestClient.builder(); MockRestServiceServer.bindTo(builder).build();
        var client=spy(new FlightTimetableClient("key","map",builder.build()));
        var origin=new SelectedPlace("1","출발지","서울",37.5,127.0,null);
        var near=new SelectedPlace("2","김포국제공항","서울",37.55,126.8,null);
        var second=new SelectedPlace("3","인천국제공항","인천",37.45,126.45,null);
        var far=new SelectedPlace("4","제주국제공항","제주",33.5,126.5,null);
        doReturn(List.of(new Airport("CJU",far.name(),null),new Airport("ICN",second.name(),null),new Airport("GMP",near.name(),null))).when(client).airports();
        doReturn(near).when(client).location("GMP"); doReturn(second).when(client).location("ICN"); doReturn(far).when(client).location("CJU");
        try { var result=client.nearby(origin); assertEquals(List.of("GMP","ICN"),result.stream().map(Airport::id).toList()); assertEquals(near,result.get(0).location()); }
        finally { client.close(); }
    }
    @Test void rejectsStaleDateAndAllowsManualFlightsWithoutApi() {
        var gmp=place("1","김포국제공항"); var cju=place("2","제주국제공항");
        var t=new Transport(TransportMode.PLANE,cju.name(),"08:20",cju.name(),"20:00",cju,cju,gmp,"07:05",true,null,gmp,null,"21:10",true,null,null,null,null,option(false),option(true));
        var request=new GenerateRequest("제주",DATE.plusDays(1),1,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),t));
        assertThrows(ApiException.class,()->new TransportArrivalService(null).resolve(request));
        var manual=new Transport(TransportMode.PLANE,cju.name(),"08:20",cju.name(),"20:00",cju,cju,gmp,"07:05",false,null,gmp,null,"21:10",false);
        var resolved=new TransportArrivalService(null).resolve(new GenerateRequest("제주",DATE,1,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),manual)));
        assertEquals("08:20",resolved.travelDetails().transport().arrivalTime()); assertNull(resolved.travelDetails().transport().flight());
    }
}
