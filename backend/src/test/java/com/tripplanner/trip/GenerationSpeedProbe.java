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
public class GenerationSpeedProbe {
    private static String env(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    public static void main(String[] args) {
        int days=args.length==0?7:Integer.parseInt(args[0]);
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_2).build();
        var mapHttp=HttpClientConfig.client(http,Duration.ofSeconds(8));
        var props=new AppProperties(null,new AppProperties.Kakao(env("KAKAO_REST_API_KEY",""),null,List.of()),
            new AppProperties.OpenAi(env("OPENAI_API_KEY",""),env("OPENAI_MODEL","gpt-4o-mini"),env("OPENAI_BASE_URL","https://api.openai.com/v1"),env("OPENAI_REASONING_EFFORT","low")),false,"",7,"");
        var ai=new OpenAiItineraryClient(HttpClientConfig.client(http,Duration.ofSeconds(60)),props,new ObjectMapper().findAndRegisterModules());
        var maps=new KakaoLocalClient(mapHttp,props);
        var routes=new RouteTimeClient(env("KAKAO_MOBILITY_API_KEY",env("KAKAO_REST_API_KEY","")),env("KAKAO_MAP_API_KEY",env("KAKAO_REST_API_KEY","")),Integer.parseInt(env("ROUTING_MAX_CALLS","12")),http);
        var hours=new GoogleOpeningHoursClient(mapHttp,env("GOOGLE_PLACES_API_KEY",""),Integer.parseInt(env("GOOGLE_PLACES_MAX_CALLS","100")),Clock.system(ZoneId.of("Asia/Seoul")));
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),maps,new RoutedItineraryService(routes),null,new GoogleHoursScheduler(hours));
        long started=System.nanoTime();
        try {
            Transport transport=null;
            if(args.length>1&&args[1].equals("fixed")) {
                var hit=maps.search("부산역",maps.resolveArea("부산광역시").orElse(null),15).stream().filter(p->p.name().startsWith("부산역")&&p.categoryName().contains("기차역")).findFirst().orElseThrow();
                var station=new SelectedPlace(hit.placeId(),hit.name(),hit.address(),hit.lat(),hit.lng(),hit.placeUrl());
                transport=new Transport(TransportMode.KTX,station.name(),"10:26",station.name(),"19:54",station,station);
            }
            final boolean fixedTransport=transport!=null;
            var req=new GenerateRequest("부산광역시",LocalDate.of(2026,11,30),days,null,List.of("맛집","쇼핑","체험","카페"),null,new TravelDetails(MovementMode.CAR,List.of(),transport));
            var result=service.generateOptions(req);
            int strict=0,missing=0;
            for(int i=0;i<result.options().size();i++) {
                var draft=result.options().get(i).draft();
                try {DraftVisitPlanner.require(draft,i);DraftMealPlanner.require(draft);DraftOptionComposer.requireCoverage(draft);TravelSchedule.validateDraft(draft);ExtraRequestRules.require(draft);strict++;}
                catch(RuntimeException e){System.out.println("PERF_PROFILE type="+i+" source="+draft.source()+" issue="+e.getMessage());}
                for(var day:draft.days())for(var place:day.places())if(place.fixedEventId()==null&&FinalRecommendations.text(place).isBlank())missing++;
            }
            System.out.println("PERF_RESULT fixedTransport="+fixedTransport+" days="+days+" elapsedMs="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" strictProfiles="+strict+" missingDescriptions="+missing);
        }finally{service.shutdown();hours.close();routes.shutdown();}
    }
}
