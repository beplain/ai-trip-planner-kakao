package com.tripplanner.place;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Korea Tourism Organization public data. Kakao remains the source of venue identity and coordinates. */
@Component
public class TourApiClient {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TourApiClient.class);
    private final RestClient http;private final String key,baseUrl;
    private record Cached(BusinessHoursInfo info,Instant until) {}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    public static class Budget {
        private final AtomicInteger remaining;private volatile boolean incomplete;
        public Budget(int maxCalls){remaining=new AtomicInteger(Math.max(0,maxCalls));}
        boolean claim(){boolean ok=remaining.getAndDecrement()>0;if(!ok)incomplete=true;return ok;}
        public boolean incomplete(){return incomplete;}
    }
    public TourApiClient(@Qualifier("placesRestClient") RestClient http,@Value("${app.tourism.service-key:}") String key,
            @Value("${app.tourism.base-url:https://apis.data.go.kr/B551011/KorService2}") String baseUrl) {
        this.http=http;this.key=(key.contains("%")?URLDecoder.decode(key,StandardCharsets.UTF_8):key).trim();this.baseUrl=baseUrl.replaceAll("/$","");
        log.info("관광 운영시간 조회 설정: {}",enabled()?"활성":"키 미등록");
    }
    public boolean enabled(){return !key.isBlank();}
    public BusinessHoursInfo lookup(String id,String name,double lat,double lng,Budget budget) {
        return lookup(id,name,null,lat,lng,budget);
    }
    public BusinessHoursInfo lookup(String id,String name,String address,double lat,double lng,Budget budget) {
        if(!enabled())return BusinessHoursInfo.unknown("운영시간 조회가 아직 연결되지 않았어요.");
        String cacheKey=id+"|"+name+"|"+address+"|"+lat+"|"+lng;Instant now=Instant.now();Cached previous=cache.get(cacheKey);
        if(previous!=null && previous.until().isAfter(now))return previous.info();
        try {
            JsonNode listings=get("searchKeyword2",Map.of("keyword",name,"numOfRows",20,"pageNo",1),budget);
            Map<String,JsonNode> matches=new LinkedHashMap<>();
            int sameNames=0,addressMatches=0,coordinateMatches=0;
            String requestedAddress=roadIdentity(address);
            for(JsonNode row:listings) {
                if(!Set.of("12","14","28","38","39").contains(row.path("contenttypeid").asText()))continue;
                if(!identity(name).equals(identity(row.path("title").asText())))continue;
                sameNames++;
                String candidateAddress=roadIdentity(row.path("addr1").asText());
                boolean sameAddress=!requestedAddress.isEmpty() && requestedAddress.equals(candidateAddress);
                double x=row.path("mapx").asDouble(Double.NaN),y=row.path("mapy").asDouble(Double.NaN);
                boolean nearby=Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<=180&&Math.abs(y)<=90
                    && Geo.distanceMeters(lat,lng,y,x)<=150;
                if(sameAddress)addressMatches++;
                if(nearby)coordinateMatches++;
                // A complete matching road address is stronger evidence than a provider's map pin.
                // If both road addresses are available but conflict, proximity cannot override them.
                boolean conflictingAddress=!requestedAddress.isEmpty()&&!candidateAddress.isEmpty()&&!sameAddress;
                if(sameAddress || nearby&&!conflictingAddress)matches.put(row.path("contentid").asText(),row);
            }
            log.info("관광 운영시간 지점 매칭: 장소id={} 검색={} 이름일치={} 주소일치={} 좌표일치={} 연결={}",
                id,listings.size(),sameNames,addressMatches,coordinateMatches,matches.size());
            BusinessHoursInfo info;
            if(matches.size()!=1)info=BusinessHoursInfo.unknown(matches.isEmpty()?"공공데이터에서 같은 지점의 운영시간을 확인하지 못했어요.":"같은 이름의 지점이 겹쳐 운영시간을 확정하지 못했어요.");
            else {
                var row=matches.values().iterator().next();String contentId=row.path("contentid").asText(),type=row.path("contenttypeid").asText();
                if(!contentId.matches("[0-9]{1,30}"))throw new IllegalArgumentException();
                JsonNode intro=get("detailIntro2",Map.of("contentId",contentId,"contentTypeId",type),budget);
                JsonNode details=intro.isEmpty()?null:intro.get(0);
                if(details==null)info=BusinessHoursInfo.unknown("공공데이터에 운영시간이 등록되지 않았어요.");
                else {
                    String hoursField=switch(type){case "39"->"opentimefood";case "14"->"usetimeculture";case "28"->"usetimeleports";case "38"->"opentime";default->"usetime";};
                    String restField=switch(type){case "39"->"restdatefood";case "14"->"restdateculture";case "28"->"restdateleports";default->"restdate";};
                    String hours=BusinessHoursRules.plain(details.path(hoursField).asText("")),rest=BusinessHoursRules.plain(details.path(restField).asText(""));
                    if(hours.length()>2000 || rest.length()>1000)info=BusinessHoursInfo.unknown("운영시간 안내가 복잡해 원문 확인이 필요해요.");
                    else info=new BusinessHoursInfo(contentId,hours,rest,now.toString(),null,"UNKNOWN","공공데이터 원문을 확인했어요.");
                }
            }
            if(cache.size()>4096)cache.clear();
            cache.put(cacheKey,new Cached(info,now.plus(Duration.ofHours(info.contentId()==null?6:24))));
            return info;
        } catch(ProviderFailure e) {
            budget.incomplete=true;
            log.warn("관광 운영시간 조회 실패: 제공기관 응답 코드={}",e.code);
            return BusinessHoursInfo.unknown("관광정보 제공기관에서 조회를 거절했어요. 잠시 후 다시 확인해 주세요.");
        } catch(org.springframework.web.client.RestClientResponseException e) {
            budget.incomplete=true;
            log.warn("관광 운영시간 조회 실패: HTTP 상태={}",e.getStatusCode().value());
            return BusinessHoursInfo.unknown("관광정보 제공기관에 연결하지 못했어요. 잠시 후 다시 확인해 주세요.");
        } catch(Exception e) {
            budget.incomplete=true;
            // Never log the request URI: its query contains the service key.
            log.warn("관광 운영시간 조회 실패: 유형={}",e.getClass().getSimpleName());
            return BusinessHoursInfo.unknown("공공데이터 조회 한도 또는 응답 문제로 운영시간을 확인하지 못했어요.");
        }
    }
    private JsonNode get(String operation,Map<String,Object> fields,Budget budget) {
        if(!budget.claim() || Thread.currentThread().isInterrupted())throw new IllegalStateException("budget");
        var builder=UriComponentsBuilder.fromUriString(baseUrl+"/"+operation).queryParam("serviceKey","{serviceKey}")
            .queryParam("MobileOS","ETC").queryParam("MobileApp","AiTripPlanner").queryParam("_type","json");
        fields.forEach(builder::queryParam);
        // URI variables are strictly encoded: a raw '+' in a key must become %2B, never a form-space.
        JsonNode result=http.get().uri(builder.encode().buildAndExpand(Map.of("serviceKey",key)).toUri()).retrieve().body(JsonNode.class);
        String code=result==null?"EMPTY":result.path("response").path("header").path("resultCode").asText("MISSING");
        if(!Set.of("0000","00").contains(code))throw new ProviderFailure(code.matches("[A-Za-z0-9_-]{1,20}")?code:"INVALID");
        JsonNode items=result.path("response").path("body").path("items").path("item");
        if(items.isArray())return items;
        var array=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();if(items.isObject())array.add(items);return array;
    }
    private static class ProviderFailure extends RuntimeException {
        final String code;
        ProviderFailure(String code){this.code=code;}
    }
    static String roadIdentity(String address) {
        if(address==null || address.isBlank())return "";
        String value=Normalizer.normalize(address,Normalizer.Form.NFKC).trim()
            .replaceAll("\\([^)]*\\)"," ").replaceAll("\\s+"," ").trim();
        String[][] regions={{"서울특별시","서울"},{"부산광역시","부산"},{"대구광역시","대구"},
            {"인천광역시","인천"},{"광주광역시","광주"},{"대전광역시","대전"},{"울산광역시","울산"},
            {"세종특별자치시","세종"},{"경기도","경기"},{"강원특별자치도","강원"},{"강원도","강원"},
            {"충청북도","충북"},{"충청남도","충남"},{"전북특별자치도","전북"},{"전라북도","전북"},
            {"전라남도","전남"},{"경상북도","경북"},{"경상남도","경남"},{"제주특별자치도","제주"},{"제주도","제주"}};
        for(String[] region:regions)if(value.startsWith(region[0]+" ")){value=region[1]+value.substring(region[0].length());break;}
        // Region, road, and complete building number are required; a city/district alone is not identity.
        if(!value.matches(".+\\s+[^ ]+(?:대로|로|길)\\s+\\d+(?:-\\d+)?"))return "";
        return value.replace(" ","");
    }
    private static String identity(String name){return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
        .replaceAll("\\[(?:유네스코|세계문화유산)[^]]*]","").replaceAll("[^\\p{L}\\p{N}]","");}
}
