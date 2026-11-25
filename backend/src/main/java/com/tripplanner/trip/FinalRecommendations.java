package com.tripplanner.trip;

import com.tripplanner.ai.OpenAiItineraryClient;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Complete saved descriptions after map/meal/profile substitutions, never while rendering cards. */
final class FinalRecommendations {
    private static final Set<String> STOCK=Set.of("한 상 차림이 중심인 식당입니다.","집밥이 차려지는 식당입니다.","여행 중 든든하게 한 끼를 챙길 수 있어요.","다음 일정 전에 여유롭게 한 끼 할 자리.","국과 반찬이 함께 상에 오릅니다.","앉으면 따뜻한 밥이 나옵니다.","밥과 국이 기본으로 놓입니다.","이 집의 상은 집밥으로 채워집니다.","커피나 디저트와 함께 잠시 쉬어 가세요.","여행지의 풍경과 분위기를 둘러보세요.","여행 중에 들러 상품을 둘러보세요.","자연 풍경을 감상하며 여유를 즐겨 보세요.","여행지의 문화와 이야기를 만나 보세요.","여행지에서 새로운 활동을 경험해 보세요.");
    private FinalRecommendations() {}
    static String text(PlaceDraft place) {
        String value=Objects.toString(place.reason(),"").trim();
        for(int i=0;i<8;i++) {
            if(value.startsWith("식사 일정 · ")) {
                String[] parts=value.split(" · ",4);value=parts.length==4?parts[3].trim():"";
            } else if(value.matches("^(아침|점심|저녁) 식사 요청:.*")) {
                int at=value.indexOf(" · ");value=at<0?"":value.substring(at+3).trim();
            } else break;
        }
        if(STOCK.contains(value) || value.equals(Objects.toString(place.category(),"")+" 방문") || value.equals("장소 방문") || value.equals("대체 장소")
                || value.equals("카카오맵에서 확인한 장소로 이동 가능한 시간대에 배치했습니다.")
                || value.matches("^(맛집|쇼핑|카페|관광|자연|문화|체험|식당|힐링|야경|기타|추천)$")
                || value.matches(".*(한 상 차림|집밥이|밥과 국|국과 반찬|따뜻한 밥이 나옵니다|이 집의 상은|나는\\s*집).*"))return "";
        String name=Objects.toString(place.name(),"").trim();
        if(!name.isEmpty())value=value.replaceFirst("\\s*(?:[·|—–-]\\s*)?"+java.util.regex.Pattern.quote(name)+"[.!?]?\\s*$","").trim();
        return value;
    }
    static List<TripDraft> fill(List<TripDraft> drafts,OpenAiItineraryClient ai) {
        var places=new LinkedHashMap<String,PlaceDraft>();
        for(var draft:drafts)for(var day:draft.days())for(var p:day.places())if(p.fixedEventId()==null && p.verified() && p.placeId()!=null)places.putIfAbsent(p.placeId(),p);
        var existing=new HashMap<String,String>();
        for(var p:places.values())if(!text(p).isBlank())existing.put(p.placeId(),text(p));
        var frequency=new HashMap<String,Integer>();existing.values().forEach(v->frequency.merge(v,1,Integer::sum));
        var targets=places.values().stream().filter(p->text(p).isBlank() || frequency.getOrDefault(text(p),0)>1).toList();
        if(targets.isEmpty())return drafts;
        var targetIds=targets.stream().map(PlaceDraft::placeId).collect(java.util.stream.Collectors.toSet());
        var generated=new HashMap<String,String>();
        try {
            var responses=describeBatches(targets,ai);
            Set<String> sentences=new HashSet<>();
            for(var response:responses)for(var row:response) {
                String id=row.path("id").asText(),line=row.path("reason").asText().replaceAll("\\s+"," ").trim();
                var p=places.get(id);
                if(p==null || !targetIds.contains(id) || generated.containsKey(id) || line.length()<12 || line.length()>140)continue;
                line=text(copy(p,line));
                if(line.isBlank() || line.matches(".*(\\d{1,2}:\\d{2}|도로명|지번|한 상 차림|밥과 국|국과 반찬|메뉴가 나|메뉴를 제공).*"))continue;
                if(sentences.add(line))generated.put(id,line);
            }
        } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(FinalRecommendations.class).warn("최종 장소 추천 문구 보완 미완료: {}",failure.getClass().getSimpleName());
        }
        var result=new ArrayList<TripDraft>();
        for(var draft:drafts) {
            var days=draft.days().stream().map(day->new DayDraft(day.dayNumber(),day.date(),day.places().stream().map(p->{
                if(p.fixedEventId()!=null || !generated.containsKey(p.placeId()))return p;
                String old=Objects.toString(p.reason(),"");
                String[] parts=old.split(" · ",4);
                String prefix=old.startsWith("식사 일정 · ")&&parts.length>=3?parts[0]+" · "+parts[1]+" · "+parts[2]+" · ":"";
                return copy(p,prefix+generated.get(p.placeId()));
            }).toList())).toList();
            var warnings=new ArrayList<>(draft.warnings()==null?List.<String>of():draft.warnings());
            if(days.stream().flatMap(d->d.places().stream()).anyMatch(p->targetIds.contains(p.placeId())&&!generated.containsKey(p.placeId())))
                warnings.add("일부 장소의 추천 문구를 준비하지 못했어요. 일정과 장소 정보는 그대로 이용할 수 있어요.");
            result.add(DraftVariants.withDays(draft,days,List.copyOf(warnings),draft.options(),draft.selectedOption()));
        }
        return List.copyOf(result);
    }
    static List<TripDraft> reuseDescriptions(List<TripDraft> drafts,List<TripDraft> prepared) {
        var saved=new HashMap<String,PlaceDraft>();
        for(var d:prepared)for(var day:d.days())for(var p:day.places())if(p.fixedEventId()==null&&!text(p).isBlank())saved.putIfAbsent(p.placeId(),p);
        var frequency=new HashMap<String,Integer>();var ids=new HashSet<String>();
        for(var d:drafts)for(var day:d.days())for(var p:day.places())if(p.fixedEventId()==null&&ids.add(p.placeId())&&!text(p).isBlank())frequency.merge(text(p),1,Integer::sum);
        return drafts.stream().map(d->DraftVariants.withDays(d,d.days().stream().map(day->new DayDraft(day.dayNumber(),day.date(),day.places().stream().map(p->{
            var prior=saved.get(p.placeId());
            if(p.fixedEventId()!=null||prior==null||!Objects.equals(prior.name(),p.name())||!Objects.equals(prior.category(),p.category())||!Objects.equals(prior.categoryPath(),p.categoryPath())
                ||(!text(p).isBlank()&&frequency.getOrDefault(text(p),0)<=1))return p;
            String[] parts=Objects.toString(p.reason(),"").split(" · ",4);
            String prefix=Objects.toString(p.reason(),"").startsWith("식사 일정 · ")&&parts.length>=3?parts[0]+" · "+parts[1]+" · "+parts[2]+" · ":"";
            return copy(p,prefix+text(prior));
        }).toList())).toList(),d.warnings(),d.options(),d.selectedOption())).toList();
    }

    static List<JsonNode> describeBatches(List<PlaceDraft> targets,OpenAiItineraryClient ai) {
        var facts=targets.stream().map(p->Map.of("id",p.placeId(),"name",p.name(),"category",Objects.toString(p.category(),""),"categoryPath",Objects.toString(p.categoryPath(),""))).toList();
        if(facts.size()<=8)return List.of(ai.describeFinalPlaces(facts));
        int workers=facts.size()>32?8:4;
        int size=(facts.size()+workers-1)/workers;
        var executor=Executors.newFixedThreadPool(workers,task->{var thread=new Thread(task,"place-descriptions");thread.setDaemon(true);return thread;});
        long started=System.nanoTime();
        try {
            var tasks=new ArrayList<Callable<JsonNode>>();
            for(int from=0;from<facts.size();from+=size) {
                var batch=List.copyOf(facts.subList(from,Math.min(facts.size(),from+size)));
                tasks.add(()->ai.describeFinalPlaces(batch));
            }
            var responses=new ArrayList<JsonNode>();
            for(var job:executor.invokeAll(tasks))try{responses.add(job.get());}
            catch(ExecutionException failure){org.slf4j.LoggerFactory.getLogger(FinalRecommendations.class).warn("장소 추천 문구 묶음 보완 미완료: {}",failure.getCause().getClass().getSimpleName());}
            return responses;
        } catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("추천 문구 생성 중단",failure);}
        finally {
            executor.shutdownNow();
            org.slf4j.LoggerFactory.getLogger(FinalRecommendations.class).info("추천 문구 병렬 생성: 장소={} 소요={}ms",facts.size(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        }
    }

    private static PlaceDraft copy(PlaceDraft p,String reason) {
        return new PlaceDraft(p.placeId(),p.name(),p.address(),p.latitude(),p.longitude(),p.visitTime(),reason,p.note(),p.category(),p.verified(),p.placeUrl(),p.phone(),p.fixedEventId(),p.categoryPath(),p.businessHours(),p.durationMinutes());
    }
}
