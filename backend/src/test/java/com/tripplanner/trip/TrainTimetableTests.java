package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class TrainTimetableTests {
    static final LocalDate DATE=LocalDate.of(2027,5,1);
    static final ObjectMapper JSON=new ObjectMapper();
    static SelectedPlace station(String name,String address,String id) { return new SelectedPlace(id,name,address,37.,127.,null); }
    static Transport request() { return new Transport(TransportMode.KTX,"대구역","10:00","대구역","20:00",station("대구역 경부선","대구광역시 북구","2"),station("대구역","대구광역시 북구","2"),station("서울역 경부선","서울특별시 용산구","1"),"08:00",true); }
    static String page(Object rows,int total) throws Exception {
        return JSON.writeValueAsString(Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("items",Map.of("item",rows),"totalCount",total))));
    }
    static Map<String,String> row(String train,String dep,String arr) {
        return Map.of("trainno",train,"traingradename","KTX","depplandtime",dep,"arrplandtime",arr,"depplacename","서울","arrplacename","대구");
    }
    static String schedules() throws Exception {
        return page(List.of(row("10","20270501081500","20270501102000"),row("11","20270501075000","20270501095000"),
            row("12","20270501080000","20270501110000"),row("13","20270501080000","20270501100000")),4);
    }
    static void metadata(MockRestServiceServer server) throws Exception {
        server.expect(requestTo(containsString("/TrainInfo/GetCtyCodeList"))).andRespond(withSuccess(page(List.of(Map.of("citycode","11","cityname","서울특별시"),Map.of("citycode","22","cityname","대구광역시")),2),MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/TrainInfo/GetCtyAcctoTrainSttnList"))).andExpect(queryParam("cityCode","11"))
            .andRespond(withSuccess(page(Map.of("nodeid","NAT010000","nodename","서울"),1),MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/TrainInfo/GetCtyAcctoTrainSttnList"))).andExpect(queryParam("cityCode","22"))
            .andRespond(withSuccess(page(List.of(Map.of("nodeid","NAT013239","nodename","대구"),Map.of("nodeid","NAT013271","nodename","동대구")),2),MediaType.APPLICATION_JSON));
    }
    static void timetable(MockRestServiceServer server,String body) {
        server.expect(requestTo(containsString("/TrainInfo/GetStrtpntAlocFndTrainInfo")))
            .andExpect(queryParam("depPlaceId","NAT010000")).andExpect(queryParam("arrPlaceId","NAT013239"))
            .andExpect(queryParam("depPlandTime","20270501")).andExpect(queryParam("_type","json"))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    @Test void officialEndpointUsesRealStationIdsAndSortsActualServicesAfterDesiredTime() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server); timetable(server,schedules());
        var options=new TrainTimetableClient("test+key=",builder.build()).options(request(),DATE);
        assertEquals(List.of("11","13","12","10"),options.stream().map(TrainOption::trainNo).toList());
        assertEquals("07:50",options.get(0).departureTime()); assertEquals("09:50",options.get(0).arrivalTime());
        server.verify();
    }
    @Test void arrivalComesFromSelectedTimetableNotClientSuppliedTimesAndRevalidatesBeforePrompt() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server); timetable(server,schedules()); timetable(server,schedules());
        var client=new TrainTimetableClient("test",builder.build());
        var options=client.options(request(),DATE); var selected=options.get(3);
        var falseTimes=new TrainOption(selected.id(),DATE,"fake","fake","서울","대구","09:00","09:01");
        var t=request(); var chosen=new Transport(t.mode(),t.arrivalPlace(),"09:01",t.departurePlace(),t.departureTime(),t.arrivalLocation(),t.departureLocation(),t.originLocation(),t.originTime(),true,falseTimes);
        var service=new TransportArrivalService(null,client);
        var result=service.resolve(new GenerateRequest("대구광역시",DATE,2,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),chosen)));
        assertEquals("10:20",result.travelDetails().transport().arrivalTime());
        assertEquals("08:15",result.travelDetails().transport().train().departureTime());
        assertEquals("10",result.travelDetails().transport().train().trainNo());
        assertTrue(TravelSchedule.prompt(result.travelDetails(),2).contains("08:15 출발"));
        assertEquals(10*60+50,TravelSchedule.start(1,result.travelDetails()));
        server.verify();
    }
    @Test void defaultSelectionUsesFirstAvailableTrainEvenWhenNoTrainLeavesAtDesiredMinute() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server);
        timetable(server,page(List.of(row("10","20270501081500","20270501102000")),1));
        var result=new TransportArrivalService(null,new TrainTimetableClient("test",builder.build())).calculate(request(),DATE);
        assertEquals("08:00",result.originTime()); assertEquals("08:15",result.train().departureTime()); assertEquals("10:20",result.arrivalTime());
        server.verify();
    }
    @Test void unavailableDatesOvernightWrongDestinationAndMalformedRowsCannotBecomeInventedArrivals() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server);
        var wrongStation=new HashMap<>(row("20","20270501090000","20270501110000")); wrongStation.put("arrplacename","동대구");
        timetable(server,page(List.of(row("10","20270501230000","20270502010000"),row("11","20270502090000","20270502110000"),row("12","bad","bad"),wrongStation),4));
        var failure=assertThrows(ApiException.class,()->new TrainTimetableClient("test",builder.build()).options(request(),DATE));
        assertTrue(failure.getMessage().contains("직통")); server.verify();
    }
    @Test void stationNamesAreExactAndCannotSilentlyReplaceDaeguWithDongdaegu() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var failure=assertThrows(ApiException.class,()->new TrainTimetableClient("test",builder.build()).options(
            new Transport(TransportMode.KTX,"대구역","10:00","대구역","20:00",request().arrivalLocation(),request().departureLocation(),station("서울역사박물관","서울특별시 종로구","3"),"08:00",true),DATE));
        assertTrue(failure.getMessage().contains("기차역")); server.verify();
    }
    @Test void providerErrorDoesNotExposeServiceKeyOrUri() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withUnauthorizedRequest());
        var failure=assertThrows(ApiException.class,()->new TrainTimetableClient("TOP-SECRET",builder.build()).options(request(),DATE));
        assertFalse(failure.getMessage().contains("TOP-SECRET")); assertFalse(failure.getMessage().contains("https:")); server.verify();
    }
    @Test void missingKeyDoesNotSendRequestsAndDateIsRequired() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        assertEquals("TRAIN_NOT_CONFIGURED",assertThrows(ApiException.class,()->new TrainTimetableClient("",builder.build()).options(request(),DATE)).getCode());
        assertThrows(ApiException.class,()->new TrainTimetableClient("test",builder.build()).options(request(),null)); server.verify();
    }
    @Test void allPagesAreReadAndMetadataIsReusedWithoutCachingAnOldTimetable() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server);
        server.expect(queryParam("pageNo","1")).andRespond(withSuccess(page(List.of(row("1","20270501090000","20270501110000")),2),MediaType.APPLICATION_JSON));
        server.expect(queryParam("pageNo","2")).andRespond(withSuccess(page(Map.of("trainno","2","traingradename","ITX","depplandtime","20270501080000","arrplandtime","20270501100000","depplacename","서울","arrplacename","대구"),2),MediaType.APPLICATION_JSON));
        timetable(server,schedules());
        var client=new TrainTimetableClient("test",builder.build());
        assertEquals("2",client.options(request(),DATE).get(0).trainNo());
        assertEquals("11",client.options(request(),DATE).get(0).trainNo()); server.verify();
    }
    @Test void decodingServiceKeyIsEncodedExactlyOnceWithoutLosingPlusCharacters() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("serviceKey","test%2Bkey%3D"))
            .andRespond(withSuccess(page(List.of(),0),MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class,()->new TrainTimetableClient("test+key=",builder.build()).options(request(),DATE)); server.verify();
    }
    @Test void lookupWorksBeforeTheComingLegHasBeenEntered() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server); timetable(server,schedules());
        var controller=new TransportArrivalController(new TransportArrivalService(null,new TrainTimetableClient("test",builder.build())));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var t=request(); var incomplete=new Transport(t.mode(),t.arrivalPlace(),t.arrivalTime(),"","18:00",t.arrivalLocation(),null,t.originLocation(),t.originTime(),true);
        var mapper=new ObjectMapper().findAndRegisterModules();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/trips/transport/trains")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new TrainLookupRequest(incomplete,DATE))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].departureTime").value("07:50"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].arrivalTime").value("09:50"));
        server.verify();
    }
    @Test void nonexistentSelectionCannotBeUsedAfterAServiceDisappears() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server); timetable(server,schedules());
        var t=request(); var fake=new TrainOption("gone",DATE,"99","KTX","서울","대구","08:00","10:00");
        assertThrows(ApiException.class,()->new TransportArrivalService(null,new TrainTimetableClient("test",builder.build())).calculate(
            new Transport(t.mode(),t.arrivalPlace(),t.arrivalTime(),t.departurePlace(),t.departureTime(),t.arrivalLocation(),t.departureLocation(),t.originLocation(),t.originTime(),true,fake),DATE)); server.verify();
    }

    @Test void tenMinuteWindowIncludesBoundaryButExcludesEarlierAndClampsAtMidnight() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server);
        timetable(server,page(List.of(row("early","20270501074900","20270501090000"),row("edge","20270501075000","20270501095000")),2));
        timetable(server,page(List.of(row("midnight","20270501000000","20270501010000")),1));
        var client=new TrainTimetableClient("test",builder.build());
        assertEquals(List.of("edge"),client.options(request(),DATE).stream().map(TrainOption::trainNo).toList());
        var t=request();
        var midnight=new Transport(t.mode(),t.arrivalPlace(),t.arrivalTime(),t.departurePlace(),t.departureTime(),t.arrivalLocation(),t.departureLocation(),t.originLocation(),"00:05",true);
        assertEquals("00:00",client.options(midnight,DATE).get(0).departureTime()); server.verify();
    }
    @Test void returningTrainUsesLastDateReverseStationsAndActualDepartureToEndItinerary() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build(); metadata(server); timetable(server,schedules());
        var row=Map.of("trainno","99","traingradename","KTX","depplandtime","20270502185000","arrplandtime","20270502205000","depplacename","대구","arrplacename","서울");
        for(int i=0;i<2;i++) server.expect(queryParam("depPlandTime","20270502")).andExpect(queryParam("depPlaceId","NAT013239")).andExpect(queryParam("arrPlaceId","NAT010000"))
            .andRespond(withSuccess(page(List.of(row),1),MediaType.APPLICATION_JSON));
        var client=new TrainTimetableClient("test",builder.build()); var t=request(); client.options(t,DATE);
        var q=new Transport(TransportMode.KTX,"서울역","21:00","대구역","19:00",t.originLocation(),null,t.departureLocation(),"19:00",true);
        var selected=client.options(q,DATE.plusDays(1)).get(0);
        var forged=new TrainOption(selected.id(),selected.date(),"fake","fake","대구","서울","19:30","22:00");
        var input=new Transport(t.mode(),t.arrivalPlace(),t.arrivalTime(),t.departurePlace(),"19:00",t.arrivalLocation(),t.departureLocation(),t.originLocation(),t.originTime(),false,null,t.originLocation(),forged);
        var result=new TransportArrivalService(null,client).resolve(new GenerateRequest("대구광역시",DATE,2,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),input)));
        assertEquals("19:00",result.travelDetails().transport().departureTime());
        assertEquals("18:50",result.travelDetails().transport().returnTrain().departureTime());
        assertEquals(18*60+20,TravelSchedule.end(2,2,result.travelDetails()));
        assertTrue(TravelSchedule.prompt(result.travelDetails(),2).contains("18:50 출발")); server.verify();
    }
}
