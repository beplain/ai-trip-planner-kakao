package com.tripplanner.place;

import com.fasterxml.jackson.databind.*;
import com.tripplanner.trip.TripDtos.PlaceDraft;
import com.tripplanner.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.*;
import org.springframework.web.client.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded Google Text Search requests shared by all variants of one generation, never a persistent cache. */
@Component
public class GoogleOpeningHoursClient {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GoogleOpeningHoursClient.class);
    private static final String MASK="places.id,places.displayName,places.formattedAddress,places.location,places.businessStatus,places.regularOpeningHours,places.currentOpeningHours";
    private final RestClient http;private final String key;private final int maxCalls;private final Clock clock;
    private final ExecutorService pool=Executors.newFixedThreadPool(4);
    @Autowired public GoogleOpeningHoursClient(@Qualifier("placesRestClient") RestClient http,
            @Value("${app.google-places.api-key:}") String key,@Value("${app.google-places.max-calls:100}") int maxCalls) {
        this(http,key,maxCalls,Clock.system(ZoneId.of("Asia/Seoul")));
    }
    public GoogleOpeningHoursClient(RestClient http,String key,int maxCalls,Clock clock) {
        this.http=http;this.key=key.trim();this.maxCalls=Math.min(200,Math.max(1,maxCalls));this.clock=clock;
        log.info("Google 영업시간 설정: {}",enabled()?"활성":"키 미등록");
    }
    public boolean enabled(){return !key.isBlank();}
    public Session session() {
        if(!enabled())throw ApiException.badRequest("영업시간 조회 설정을 확인해 주세요. 관리자에게 문의해 주세요.");
        return new Session();
    }
    @PreDestroy public void close(){pool.shutdownNow();}
    public class Session {
        private final Map<String,GoogleOpeningHours> cache=new ConcurrentHashMap<>();
        private final AtomicInteger calls=new AtomicInteger();
        private final AtomicBoolean authFailure=new AtomicBoolean();
        private final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        public void prefetch(Collection<PlaceDraft> places) {
            Map<String,PlaceDraft> unique=new LinkedHashMap<>();for(var p:places)if(p.fixedEventId()==null)unique.putIfAbsent(key(p),p);
            List<Callable<Void>> tasks=new ArrayList<>();for(var p:unique.values())tasks.add(()->{lookup(p);return null;});
            try {pool.invokeAll(tasks,Math.max(1,Math.min(TimeUnit.SECONDS.toNanos(25),deadline-System.nanoTime())),TimeUnit.NANOSECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw ApiException.badRequest("영업시간 조회가 중단됐습니다. 다시 시도해 주세요.");}
            requireReady();
        }
        public void prefetchMealCandidates(List<PlaceDraft> candidates) {
            if (candidates.size() < 2 || cache.containsKey(key(candidates.get(0)))
                    || calls.get() >= maxCalls / 2) return;
            // Reserve the rest of the request budget for candidates actually needed and final verification.
            prefetch(candidates.stream().filter(p -> !cache.containsKey(key(p))).toList());
        }

        public GoogleOpeningHours lookup(PlaceDraft p) {
            return cache.computeIfAbsent(key(p),ignored->load(p));
        }
        private GoogleOpeningHours load(PlaceDraft p) {
            if(authFailure.get())return GoogleOpeningHours.unknown("영업시간 조회 설정 오류");
            if(p.latitude()==null||p.longitude()==null)return GoogleOpeningHours.unknown("장소 좌표를 확인하지 못했습니다.");
            try {
                String address=Objects.toString(p.address(),"").trim();
                String full=(address+" "+p.name()).trim();
                String region=address.isEmpty()?"":address.split("\\s+")[0];
                String shortQuery=(region+" "+p.name()).trim();
                List<String> queries=full.equals(shortQuery)?List.of(full):List.of(full,shortQuery);
                for(int attempt=0;attempt<queries.size();attempt++) {
                    JsonNode response=search(queries.get(attempt));
                    Map<String,JsonNode> matches=new LinkedHashMap<>(),addressMatches=new LinkedHashMap<>();
                    int rows=0,names=0,addresses=0,near=0,conflicts=0,reformatted=0;
                    if(response!=null)for(JsonNode row:response.path("places")) {
                        rows++;
                        String googleName=row.path("displayName").path("text").asText();
                        boolean exactName=identity(p.name()).equals(identity(googleName));
                        boolean cafeAlias="카페".equals(p.category())&&!cafeIdentity(p.name()).isBlank()&&cafeIdentity(p.name()).equals(cafeIdentity(googleName));
                        if(exactName||cafeAlias)names++;
                        String googleAddress=row.path("formattedAddress").asText().replaceFirst("^대한민국\\s*","");
                        String requested=TourApiClient.roadIdentity(p.address());
                        String found=TourApiClient.roadIdentity(googleAddress);
                        boolean sameAddress=sameRoadAddress(p.address(),googleAddress);
                        boolean repairedAddress=exactName&&sameSplitRoadAddress(p.address(),googleAddress);
                        if(repairedAddress)reformatted++;
                        double lat=row.path("location").path("latitude").asDouble(Double.NaN),lng=row.path("location").path("longitude").asDouble(Double.NaN);
                        boolean nearby=Double.isFinite(lat)&&Double.isFinite(lng)&&Geo.distanceMeters(p.latitude(),p.longitude(),lat,lng)<=150;
                        boolean conflict=!requested.isBlank()&&!found.isBlank()&&!sameAddress;
                        if(sameAddress)addresses++;if(nearby)near++;if(conflict)conflicts++;
                        String matchId=row.path("id").asText(row.toString());
                        // The same venue may have an alternative road address. Nearby coordinates support the exact name.
                        if(exactName&&(sameAddress||nearby||repairedAddress) || cafeAlias&&sameAddress&&nearby)matches.put(matchId,row);
                        else if(sameAddress&&nearby&&googleName.matches(".*[A-Za-z].*"))addressMatches.put(matchId,row);
                    }
                    // Prefer name-supported identity; an address may be shared by several businesses.
                    if(matches.isEmpty())matches.putAll(addressMatches);
                    log.info("Google 영업시간 지점 연결: 장소id={} 매장명={} 검색차수={} 결과={} 이름일치={} 주소일치={} 근접좌표={} 주소충돌={} 주소표기보완={} 연결={}",
                        p.placeId(),p.name(),attempt+1,rows,names,addresses,near,conflicts,reformatted,matches.size());
                    if(matches.size()==1) {
                        JsonNode matched=matches.values().iterator().next();
                        if(!matched.path("regularOpeningHours").path("periods").isArray()
                            &&!matched.path("currentOpeningHours").path("periods").isArray()
                            &&!matched.path("id").asText().isBlank())matched=details(matched.path("id").asText());
                        var result=new GoogleOpeningHours(matched,LocalDate.now(clock));
                        log.info("Google 영업시간 응답: 장소id={} 정규기간={} 날짜별기간={}",p.placeId(),
                            matched.path("regularOpeningHours").path("periods").size(),matched.path("currentOpeningHours").path("periods").size());
                        return result;
                    }
                    if(matches.size()>1)return GoogleOpeningHours.unknown("동일 지점 후보가 여러 개여서 운영시간을 확정하지 못했습니다.");
                }
                return GoogleOpeningHours.unknown("Google Maps에서 동일 지점의 운영시간을 확인하지 못했습니다.");
            } catch(LookupLimitException e) {
                return GoogleOpeningHours.unknown("조회 시간·횟수 한도로 운영시간을 확인하지 못했습니다.");
            } catch(RestClientResponseException e) {
                int status=e.getStatusCode().value();if(status==400||status==401||status==403)authFailure.set(true);
                log.warn("Google 영업시간 조회 실패: HTTP 상태={}",status);
                return GoogleOpeningHours.unknown("운영시간 제공기관 조회에 실패했습니다.");
            } catch(Exception e) {
                log.warn("Google 영업시간 조회 실패: 유형={}",e.getClass().getSimpleName());
                return GoogleOpeningHours.unknown("운영시간 조회를 완료하지 못했습니다.");
            }
        }
        private JsonNode search(String query) {
            if(System.nanoTime()>=deadline||Thread.currentThread().isInterrupted()||calls.incrementAndGet()>maxCalls)throw new LookupLimitException();
            return http.post().uri("https://places.googleapis.com/v1/places:searchText")
                .header("X-Goog-Api-Key",key).header("X-Goog-FieldMask",MASK)
                .body(Map.of("textQuery",query,"languageCode","ko","regionCode","KR","pageSize",3))
                .retrieve().body(JsonNode.class);
        }
        private JsonNode details(String placeId) {
            if(System.nanoTime()>=deadline||Thread.currentThread().isInterrupted()||calls.incrementAndGet()>maxCalls)throw new LookupLimitException();
            return http.get().uri("https://places.googleapis.com/v1/places/{id}",placeId)
                .header("X-Goog-Api-Key",key)
                .header("X-Goog-FieldMask",MASK.replace("places.",""))
                .retrieve().body(JsonNode.class);
        }
        public void requireReady(){if(authFailure.get())throw ApiException.badRequest("영업시간 조회 인증·결제·API 설정을 확인해 주세요. 관리자에게 문의해 주세요.");}
        public int calls(){return Math.min(calls.get(),maxCalls);}
    }
    private static class LookupLimitException extends RuntimeException {}
    private static String town(String address) {
        var matcher=java.util.regex.Pattern.compile("(?:^|\\s)([^\\s]+[읍면])(?=\\s|$)").matcher(Objects.toString(address,""));
        return matcher.find()?matcher.group(1):"";
    }
    private static boolean sameRoadAddress(String a,String b) {
        String first=town(a),second=town(b);
        // Do not collapse two explicitly different towns. A missing 읍/면 is a formatting difference.
        if(!first.isEmpty()&&!second.isEmpty()&&!first.equals(second))return false;
        String left=TourApiClient.roadIdentity(roadPart(a).replaceAll("(?<=\\s)[^\\s]+[읍면](?=\\s|$)"," "));
        String right=TourApiClient.roadIdentity(roadPart(b).replaceAll("(?<=\\s)[^\\s]+[읍면](?=\\s|$)"," "));
        return !left.isEmpty()&&left.equals(right);
    }
    // Compare the complete road/building portion, ignoring building names, floors and units.
    private static String roadPart(String address) {
        String value=Normalizer.normalize(Objects.toString(address,""),Normalizer.Form.NFKC)
                .replaceFirst("^대한민국\\s*","").replaceAll("\\s+"," ").trim();
        var match=java.util.regex.Pattern.compile("^(.+?\\s+[^ ]+(?:대로|로|길)\\s+\\d+(?:-\\d+)?)(?=\\s|$)").matcher(value);
        return match.find()?match.group(1):value;
    }
    private static boolean sameSplitRoadAddress(String a,String b) {
        return splitRoadMatches(a,b)||splitRoadMatches(b,a);
    }
    private static boolean splitRoadMatches(String standard,String split) {
        // Some provider addresses split '체육공원로399번길 324' into '체육공원로 399 324'.
        // Only exact venue names may use this additional evidence; do not flatten arbitrary address numbers.
        String left=Normalizer.normalize(Objects.toString(standard,""),Normalizer.Form.NFKC).trim();
        String right=Normalizer.normalize(Objects.toString(split,""),Normalizer.Form.NFKC).trim();
        var normal=java.util.regex.Pattern.compile("^(.+?)\\s+([^ ]+(?:대로|로))([0-9]+)번길\\s+([0-9]+(?:-[0-9]+)?)(?=\\s|$)").matcher(left);
        var divided=java.util.regex.Pattern.compile("^(.+?)\\s+([^ ]+(?:대로|로))\\s+([0-9]+)\\s+([0-9]+(?:-[0-9]+)?)(?=\\s|$)").matcher(right);
        if(!normal.find()||!divided.find())return false;
        if(!normal.group(2).equals(divided.group(2))||!normal.group(3).equals(divided.group(3))||!normal.group(4).equals(divided.group(4)))return false;
        return sameRoadAddress(normal.group(1)+" "+normal.group(2)+normal.group(3)+"번길 "+normal.group(4),
                divided.group(1)+" "+divided.group(2)+divided.group(3)+"번길 "+divided.group(4));
    }
    private static String key(PlaceDraft p){return p.placeId()+"|"+p.name()+"|"+p.address()+"|"+p.latitude()+"|"+p.longitude();}
    private static String cafeIdentity(String name) {
        String korean=identity(name).replaceAll("[^가-힣0-9]", "").replaceFirst("^카페", "").replaceFirst("카페$", "");
        return korean.length()>=2?korean:"";
    }
    private static String identity(String name){return Normalizer.normalize(Objects.toString(name,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
}
