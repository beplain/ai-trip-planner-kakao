package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.ai.*;
import com.tripplanner.config.*;
import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import java.time.*;
import java.net.http.HttpClient;
import java.util.*;

/** Opt-in live API timing probe; no Spring application, database, HTTP listener or saved drafts. */
public class GenerationMatrixProbe {
    private static String env(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    static void check(com.fasterxml.jackson.databind.node.ArrayNode issues,int profile,String rule,Runnable validate) {
        try{validate.run();}catch(RuntimeException failure){issues.add("profile="+profile+" "+rule+": "+failure.getMessage());}
    }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var input=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
        int days=input.path("days").asInt();String destination=input.path("destination").asText();
        var out=json.createObjectNode();out.set("case",input);out.put("startedAt",Instant.now().toString());var issues=out.putArray("issues");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_2).build();
        var mapHttp=HttpClientConfig.client(http,Duration.ofSeconds(8));
        var props=new AppProperties(null,new AppProperties.Kakao(env("KAKAO_REST_API_KEY",""),null,List.of()),
            new AppProperties.OpenAi(env("OPENAI_API_KEY",""),env("OPENAI_MODEL","gpt-4o-mini"),env("OPENAI_BASE_URL","https://api.openai.com/v1"),env("OPENAI_REASONING_EFFORT","low")),false,"",7,"");
        var ai=new OpenAiItineraryClient(HttpClientConfig.client(http,Duration.ofSeconds(60)),props,new ObjectMapper().findAndRegisterModules());
        var maps=new GenerationPlaces(new KakaoLocalClient(mapHttp,props),Map.of(),destination);
        var routes=new RouteTimeClient(env("KAKAO_MOBILITY_API_KEY",env("KAKAO_REST_API_KEY","")),env("KAKAO_MAP_API_KEY",env("KAKAO_REST_API_KEY","")),Integer.parseInt(env("ROUTING_MAX_CALLS","12")),http);
        var hours=new GoogleOpeningHoursClient(mapHttp,env("GOOGLE_PLACES_API_KEY",""),Integer.parseInt(env("GOOGLE_PLACES_MAX_CALLS","100")),Clock.system(ZoneId.of("Asia/Seoul")));
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),maps,new RoutedItineraryService(routes),null,new GoogleHoursScheduler(hours));
        long started=System.nanoTime();
        try {
            var area=maps.resolveArea(destination).orElseThrow(()->new IllegalStateException("Destination coordinates unavailable"));
            var anchor=new SelectedPlace(String.format(Locale.ROOT,"point:%.6f,%.6f",area.lat(),area.lng()),destination+" 도착·출발 장소",destination,area.lat(),area.lng(),null);
            var transport=new Transport(TransportMode.CAR,anchor.name(),"09:30",anchor.name(),"20:00",anchor,anchor);
            var styles=new ArrayList<String>();input.path("styles").forEach(p->styles.add(p.asText()));
            var req=new GenerateRequest(destination,LocalDate.parse(input.path("startDate").asText()),days,null,styles,null,new TravelDetails(MovementMode.CAR,List.of(),transport));
            out.set("request",json.valueToTree(req));out.put("setupMs",(System.nanoTime()-started)/1_000_000);started=System.nanoTime();
            var result=service.generateOptions(req);long elapsed=(System.nanoTime()-started)/1_000_000;
            out.put("elapsedMs",elapsed);out.put("slow",elapsed>input.path("targetMs").asLong()+5000);out.set("result",json.valueToTree(result));
            if(result.options().size()!=3)issues.add("Expected three profiles");
            var fingerprints=new HashSet<String>();int missing=0;
            for(int profile=0;profile<result.options().size();profile++) {
                var draft=result.options().get(profile).draft();var p=out.withArray("profiles").addObject();p.put("profile",profile);p.put("source",draft.source());
                final int currentProfile=profile;int priorIssues=issues.size();
                check(issues,profile,"COUNT",()->DraftVisitPlanner.require(draft,currentProfile));
                check(issues,profile,"MEALS",()->DraftMealPlanner.require(draft));
                check(issues,profile,"STYLES",()->DraftOptionComposer.requireCoverage(draft));
                check(issues,profile,"TIMES",()->TravelSchedule.validateDraft(draft));
                check(issues,profile,"REQUEST",()->ExtraRequestRules.require(draft));
                p.put("strict",priorIssues==issues.size());
                if(!fingerprints.add(DraftVisitPlanner.fingerprint(draft)))issues.add("Repeated venue set: profile="+profile);
                if(draft.days().size()!=days)issues.add("Wrong day count: profile="+profile);
                for(var day:draft.days())for(var place:day.places())if(place.fixedEventId()==null) {
                    if(!place.verified()||!TripRouteOptimizer.known(place))issues.add("Unverified venue: "+place.placeId());
                    if(!DestinationRegions.contains(destination,place.address()))issues.add("Out-of-destination venue: "+place.placeId());
                    if(FinalRecommendations.text(place).isBlank())missing++;
                }
            }
            out.put("missingDescriptions",missing);if(missing>0)issues.add("Missing descriptions: "+missing);
        }catch(Throwable failure){out.put("exception",failure.getClass().getSimpleName());issues.add(Objects.toString(failure.getMessage(),"unknown"));out.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
        }finally{service.shutdown();hours.close();routes.shutdown();}
        boolean expected=input.has("expectedConflict")&&!issues.isEmpty();
        for(var issue:issues)if(!issue.asText().startsWith("profile=1 COUNT:"))expected=false;
        out.put("expectedConflict",expected);
        out.put("ok",issues.isEmpty());out.put("finishedAt",Instant.now().toString());
        var path=java.nio.file.Path.of(args[1]);java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());java.nio.file.Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("MATRIX_RESULT id="+input.path("id").asText()+" ok="+out.path("ok").asBoolean()+" ms="+out.path("elapsedMs").asLong()+" issues="+issues.size());
    }
}
