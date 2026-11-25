package com.tripplanner.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** TAGO domestic flights. Never infer flights or expose credential-bearing request errors. */
@Component
public class FlightTimetableClient {
    private static final String BASE="https://apis.data.go.kr/1613000/DmstcFlightNvgInfo/";
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("uuuuMMddHHmm").withResolverStyle(ResolverStyle.STRICT);
    private final String key,mapKey;
    private final RestClient http;
    private volatile List<Airport> airports=List.of();
    private volatile long airportsUntil;
    private final Map<String,SelectedPlace> locations=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public FlightTimetableClient(@Value("${app.train.service-key:}") String key,@Value("${app.routing.kakao-map-key:}") String mapKey) {
        this(key,mapKey,client());
    }
    FlightTimetableClient(String key,String mapKey,RestClient http) {
        String raw=key==null?"":key.trim();
        this.key=raw.contains("%")?java.net.URLDecoder.decode(raw,java.nio.charset.StandardCharsets.UTF_8):raw;
        this.mapKey=mapKey; this.http=http;
    }
    private static RestClient client() {
        var f=new SimpleClientHttpRequestFactory(); f.setConnectTimeout(Duration.ofSeconds(3)); f.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(f).build();
    }
    public synchronized List<Airport> airports() {
        if(System.currentTimeMillis()<airportsUntil) return airports;
        var rows=get("GetArprtList",Map.of()).rows();
        var list=rows.stream().filter(r->!r.path("airportId").asText().isBlank() && !r.path("airportNm").asText().isBlank())
            .map(r->new Airport(r.path("airportId").asText(),r.path("airportNm").asText(),null)).sorted(Comparator.comparing(Airport::name)).toList();
        if(list.isEmpty()) throw unavailable();
        airports=list; airportsUntil=System.currentTimeMillis()+86_400_000; return list;
    }
    private Airport airport(String id) { return airports().stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->ApiException.badRequest("공항 목록에서 출발·도착 공항을 다시 선택해 주세요.")); }
    public SelectedPlace location(String id) {
        Airport airport=airport(id);
        if(locations.containsKey(id)) return locations.get(id);
        if(mapKey==null || mapKey.isBlank()) throw unavailable();
        try {
            var uri=UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json").queryParam("query",airport.name()).queryParam("size",15).build().encode().toUri();
            var data=http.get().uri(uri).header("Authorization","KakaoAK "+mapKey).retrieve().body(JsonNode.class);
            List<JsonNode> candidates=new ArrayList<>();
            if(data!=null) data.path("documents").forEach(candidates::add);
            candidates.sort(Comparator.comparingInt(r->r.path("place_name").asText().contains("국내선")?0:1));
            for(var row:candidates) {
                String name=row.path("place_name").asText();
                if(!(name.equals(airport.name()) || name.equals(airport.name()+" 국내선") || name.equals(airport.name()+" 국내선청사"))
                    || !row.path("category_name").asText().endsWith(" > 공항")) continue;
                var place=new SelectedPlace(row.path("id").asText(),airport.name(),row.path("address_name").asText(),Double.valueOf(row.path("y").asText()),Double.valueOf(row.path("x").asText()),null);
                if(!place.validId() || !Double.isFinite(place.latitude()) || !Double.isFinite(place.longitude())) continue;
                locations.put(id,place); return place;
            }
        } catch(RuntimeException ignored) { /* No upstream URL or key in user-visible errors. */ }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AIRPORT_LOCATION_UNAVAILABLE","공항의 지도 위치를 확인하지 못했어요. 잠시 후 공항을 다시 선택해 주세요.");
    }
    public List<FlightOption> options(FlightLookupRequest q) {
        if(q.date()==null || q.originAirportId()==null || q.arrivalAirportId()==null) throw ApiException.badRequest("날짜와 출발·도착 공항을 선택해 주세요.");
        Airport dep=airport(q.originAirportId()),arr=airport(q.arrivalAirportId());
        if(dep.id().equals(arr.id())) throw ApiException.badRequest("출발·도착 공항을 다르게 선택해 주세요.");
        Map<String,FlightOption> flights=new LinkedHashMap<>();
        for(int page=1;page<=10;page++) {
            var result=get("GetFlightOpratInfoList",Map.of("depAirportId",dep.id(),"arrAirportId",arr.id(),"depPlandTime",q.date().format(DateTimeFormatter.BASIC_ISO_DATE),"numOfRows","100","pageNo",String.valueOf(page)));
            for(var row:result.rows()) {
                var flight=parse(row,q.date(),dep,arr); if(flight!=null) flights.putIfAbsent(flight.id(),flight);
            }
            if(page*100>=result.total()) return flights.values().stream().sorted(Comparator.comparing(FlightOption::departureTime).thenComparing(FlightOption::flightNo)).toList();
        }
        throw unavailable();
    }
    static FlightOption parse(JsonNode row,LocalDate date,Airport dep,Airport arr) {
        try {
            var start=LocalDateTime.parse(row.path("depPlandTime").asText(),STAMP);
            var end=LocalDateTime.parse(row.path("arrPlandTime").asText(),STAMP);
            String no=row.path("vihicleId").asText(),airline=row.path("airlineNm").asText();
            if(!start.toLocalDate().equals(date) || !end.toLocalDate().equals(date) || !end.isAfter(start) || no.isBlank() || airline.isBlank()) return null;
            return new FlightOption(date+":"+dep.id()+":"+arr.id()+":"+no+":"+start.toLocalTime(),date,airline,no,dep.id(),arr.id(),dep.name(),arr.name(),start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        } catch(RuntimeException ignored) { return null; }
    }
    private record Page(List<JsonNode> rows,int total) {}
    private Page get(String operation,Map<String,String> parameters) {
        if(key.isBlank()) throw unavailable();
        try {
            var uri=UriComponentsBuilder.fromUriString(BASE+operation).queryParam("serviceKey","{key}").queryParam("_type","json");
            parameters.forEach(uri::queryParam);
            var data=http.get().uri(uri.encode().buildAndExpand(Map.of("key",key)).toUri()).retrieve().body(JsonNode.class);
            if(data==null) throw unavailable();
            var response=data.has("response")?data.path("response"):data;
            String code=response.path("header").path("resultCode").asText();
            if(code.equals("03")) return new Page(List.of(),0);
            if(!code.equals("00") && !code.equals("0")) throw unavailable();
            var body=response.path("body"); var items=body.path("items").path("item");
            List<JsonNode> rows=new ArrayList<>(); if(items.isArray())items.forEach(rows::add); else if(items.isObject())rows.add(items);
            return new Page(rows,body.path("totalCount").asInt(rows.size()));
        } catch(RuntimeException ignored) { throw unavailable(); }
    }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"FLIGHT_UNAVAILABLE","항공 시간표를 지금 확인하지 못했어요. 잠시 후 다시 조회하거나 예약한 항공편 시각을 직접 선택해 주세요."); }
}
