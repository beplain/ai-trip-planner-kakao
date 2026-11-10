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

/** Official TAGO 15098552 Swagger: TrainInfo, case-sensitive Get* operations. Never estimate rail times. */
@Component
public class TrainTimetableClient {
    private static final String BASE="https://apis.data.go.kr/1613000/TrainInfo/";
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm");
    private final String key;
    private final RestClient http;
    private record Cache(long expires,List<JsonNode> rows) {}
    private final Map<String,Cache> metadata=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public TrainTimetableClient(@Value("${app.train.service-key:}") String key) { this(key,client()); }
    TrainTimetableClient(String key,RestClient http) { this.key=key==null?"":key.trim(); this.http=http; }
    private static RestClient client() {
        var f=new SimpleClientHttpRequestFactory(); f.setConnectTimeout(Duration.ofSeconds(2)); f.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(f).build();
    }
    public List<TrainOption> options(Transport transport,LocalDate date) {
        if(key.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TRAIN_NOT_CONFIGURED","기차 시간표 조회가 아직 준비되지 않았어요. 우선 승차권의 출발·도착 시각을 직접 선택해 주세요.");
        if(transport==null || transport.mode()!=TransportMode.KTX || date==null) throw ApiException.badRequest("여행 날짜와 기차 교통편을 선택해 주세요.");
        TravelSchedule.minute(transport.originTime());
        var boarding=transport.boardingStation()!=null?transport.boardingStation():transport.originLocation();
        var alighting=transport.alightingStation()!=null?transport.alightingStation():transport.arrivalLocation();
        String origin=station(boarding),arrival=station(alighting);
        if(origin.equals(arrival)) throw ApiException.badRequest("기차 출발역과 도착역을 다르게 선택해 주세요.");
        var body=pages("GetStrtpntAlocFndTrainInfo",Map.of("depPlaceId",origin,"arrPlaceId",arrival,"depPlandTime",date.format(DateTimeFormatter.BASIC_ISO_DATE)));
        List<TrainOption> result=new ArrayList<>();
        int threshold=Math.max(0,TravelSchedule.minute(transport.originTime())-10);
        for(JsonNode row:body) {
            try {
                LocalDateTime dep=LocalDateTime.parse(row.path("depplandtime").asText(),STAMP);
                LocalDateTime arr=LocalDateTime.parse(row.path("arrplandtime").asText(),STAMP);
                // Preserve calendar-day semantics. Cross-midnight trips need an explicit multi-day model.
                if(!dep.toLocalDate().equals(date) || !arr.toLocalDate().equals(date) || !arr.isAfter(dep)
                        || dep.getHour()*60+dep.getMinute()<threshold) continue;
                if(!stationKey(row.path("depplacename").asText()).equals(placeStation(boarding))
                    || !stationKey(row.path("arrplacename").asText()).equals(placeStation(alighting))) continue;
                String trainNo=row.path("trainno").asText("").trim(),type=row.path("traingradename").asText("").trim();
                if(trainNo.isEmpty() || type.isEmpty()) continue;
                String id=origin+"_"+arrival+"_"+trainNo+"_"+dep.format(STAMP)+"_"+arr.format(STAMP);
                result.add(new TrainOption(id,date,trainNo,type,row.path("depplacename").asText(),row.path("arrplacename").asText(),dep.format(CLOCK),arr.format(CLOCK)));
            } catch(DateTimeException ignored) { /* Malformed provider rows cannot become a timetable. */ }
        }
        List<TrainOption> sorted=result.stream().distinct().sorted(Comparator.comparing(TrainOption::departureTime).thenComparing(TrainOption::arrivalTime).thenComparing(TrainOption::id)).toList();
        Map<String,TrainOption> unique=new LinkedHashMap<>(); sorted.forEach(option->unique.putIfAbsent(option.id(),option));
        if(unique.isEmpty()) throw ApiException.badRequest("해당 날짜와 출발 희망 시각 10분 전부터의 당일 도착 직통열차를 찾지 못했어요. 아직 시간표가 제공되지 않았거나 이 구간에 직통편이 없을 수 있어요. 날짜·역·시각을 확인하거나 승차권 시각을 직접 선택해 주세요.");
        return List.copyOf(unique.values());
    }
    private String station(SelectedPlace selected) {
        if(selected==null || selected.name()==null || selected.address()==null) throw ApiException.badRequest("카카오맵에서 출발 기차역과 도착 기차역을 선택해 주세요.");
        String first=selected.name().trim().split("\\s+")[0].replaceAll("[()]","");
        if(!first.endsWith("역")) throw ApiException.badRequest("'"+selected.name()+"'은(는) 기차역 이름으로 확인하기 어려워요. 역 건물을 다시 선택해 주세요.");
        String name=first.substring(0,first.length()-1);
        String city=cityKey(selected.address());
        var cities=cached("cities",()->get("GetCtyCodeList",Map.of()).rows());
        var cityRows=cities.stream().filter(c->cityKey(c.path("cityname").asText()).equals(city)).toList();
        if(city.isBlank() || cityRows.size()!=1) throw ApiException.badRequest("선택한 기차역의 지역을 시간표와 연결하지 못했어요. 카카오맵에서 역 건물을 다시 선택해 주세요.");
        String cityCode=cityRows.get(0).path("citycode").asText();
        var stations=cached("stations:"+cityCode,()->pages("GetCtyAcctoTrainSttnList",Map.of("cityCode",cityCode)));
        var matches=stations.stream().filter(r->stationKey(r.path("nodename").asText()).equals(stationKey(name)))
                .map(r->r.path("nodeid").asText()).filter(id->id.matches("[A-Za-z0-9]{1,30}")).distinct().toList();
        if(matches.size()!=1) throw ApiException.badRequest("'"+selected.name()+"'에 대응하는 기차역을 하나로 확인하지 못했어요. 지하철역·출구 대신 기차역을 선택하거나 승차권 시각을 직접 선택해 주세요.");
        return matches.get(0);
    }
    private static String stationKey(String name) { return name.replaceAll("[()\\s]","").replaceAll("역$",""); }
    private static String placeStation(SelectedPlace p) { return stationKey(p.name().trim().split("\\s+")[0]); }
    private static String cityKey(String text) {
        String first=text.trim().split("\\s+")[0];
        if(first.startsWith("충청북")||first.equals("충북")) return "충북";
        if(first.startsWith("충청남")||first.equals("충남")) return "충남";
        if(first.startsWith("전라북")||first.startsWith("전북")) return "전북";
        if(first.startsWith("전라남")||first.equals("전남")) return "전남";
        if(first.startsWith("경상북")||first.equals("경북")) return "경북";
        if(first.startsWith("경상남")||first.equals("경남")) return "경남";
        return first.length()<2?"":first.substring(0,2);
    }
    private synchronized List<JsonNode> cached(String name,java.util.function.Supplier<List<JsonNode>> load) {
        Cache existing=metadata.get(name);
        if(existing!=null && existing.expires()>System.currentTimeMillis()) return existing.rows();
        List<JsonNode> rows=List.copyOf(load.get());
        metadata.put(name,new Cache(System.currentTimeMillis()+Duration.ofHours(12).toMillis(),rows));
        return rows;
    }
    private record Page(List<JsonNode> rows,int total) {}
    private List<JsonNode> pages(String operation,Map<String,String> parameters) {
        List<JsonNode> rows=new ArrayList<>();
        for(int page=1;page<=5;page++) {
            Map<String,String> p=new LinkedHashMap<>(parameters); p.put("numOfRows","100"); p.put("pageNo",Integer.toString(page));
            Page data=get(operation,p); rows.addAll(data.rows());
            if(rows.size()>=data.total()) return rows;
            if(data.rows().isEmpty()) break;
        }
        throw unavailable(); // Never choose from an incomplete page set as though it were the full timetable.
    }
    private Page get(String operation,Map<String,String> parameters) {
        try {
            var uri=UriComponentsBuilder.fromUriString(BASE+operation).queryParam("serviceKey","{serviceKey}").queryParam("_type","json");
            parameters.forEach(uri::queryParam);
            JsonNode data=http.get().uri(uri.encode().buildAndExpand(Map.of("serviceKey",key)).toUri()).retrieve().body(JsonNode.class);
            if(data==null) throw unavailable();
            JsonNode response=data.has("response")?data.path("response"):data;
            String code=response.path("header").path("resultCode").asText();
            if(code.equals("03")) return new Page(List.of(),0);
            if(!code.equals("00") && !code.equals("0")) throw unavailable();
            JsonNode body=response.path("body"),items=body.path("items").path("item");
            List<JsonNode> rows=new ArrayList<>();
            if(items.isArray()) items.forEach(rows::add); else if(items.isObject()) rows.add(items);
            return new Page(rows,body.path("totalCount").asInt(rows.size()));
        } catch(ApiException e) { throw e; }
        catch(RuntimeException e) { throw unavailable(); } // Do not expose the URI: serviceKey is a query parameter.
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TRAIN_UNAVAILABLE","열차 시간표를 지금 확인하지 못했어요. 잠시 후 다시 조회하거나 승차권 시각을 직접 선택해 주세요.");
    }
}
