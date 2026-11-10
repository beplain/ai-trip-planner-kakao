package com.tripplanner.trip;

import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Public hours are checked after road-based scheduling, so travel adjustments cannot bypass closures. */
@Service
public class BusinessHoursService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BusinessHoursService.class);
    private final TourApiClient client;
    private final int maxCalls;
    private final ExecutorService pool=Executors.newFixedThreadPool(4);
    public BusinessHoursService(TourApiClient client,@Value("${app.tourism.max-calls:40}") int maxCalls) {
        this.client=client;this.maxCalls=Math.min(100,Math.max(0,maxCalls));
    }
    @PreDestroy void close(){pool.shutdownNow();}
    public TripDraft check(TripDraft draft) {
        var budget=new TourApiClient.Budget(maxCalls);
        Map<String,Future<BusinessHoursInfo>> pending=new LinkedHashMap<>();
        for(var day:draft.days()) for(var p:day.places()) if(p.fixedEventId()==null && p.latitude()!=null && p.longitude()!=null)
            pending.computeIfAbsent(key(p),k->pool.submit(()->client.lookup(p.placeId(),p.name(),p.address(),p.latitude(),p.longitude(),budget)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        Map<String,BusinessHoursInfo> results=new HashMap<>();
        for(var entry:pending.entrySet()) {
            try {results.put(entry.getKey(),entry.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS));}
            catch(Exception e) {entry.getValue().cancel(true);results.put(entry.getKey(),BusinessHoursInfo.unknown("운영시간 조회를 완료하지 못했어요."));if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
        List<String> warnings=new ArrayList<>(draft.warnings()==null?List.of():draft.warnings());
        List<DayDraft> days=new ArrayList<>();
        for(var day:draft.days()) {
            LocalDate date=day.date()!=null?day.date():draft.startDate()==null?null:draft.startDate().plusDays(day.dayNumber()-1);
            List<PlaceDraft> visits=new ArrayList<>();
            for(var p:day.places()) {
                if(p.fixedEventId()!=null){visits.add(p);continue;}
                var info=results.getOrDefault(key(p),BusinessHoursInfo.unknown("운영시간 미확인"));
                var assessment=BusinessHoursRules.evaluate(info,date,p.visitTime(),60);
                if(assessment.closed()) {warnings.add(p.name()+": 휴무 또는 운영시간과 겹쳐 추천에서 제외했어요.");continue;}
                visits.add(p.withBusinessHours(info.assessed(date==null?null:date.toString(),assessment.status(),assessment.detail())));
            }
            days.add(new DayDraft(day.dayNumber(),day.date(),List.copyOf(visits)));
        }
        if(!client.enabled())warnings.add("운영시간 정보가 연결되지 않아 방문 전 영업시간·휴무일 확인이 필요해요.");
        long matched=results.values().stream().filter(i->i.contentId()!=null).count();
        long withHours=results.values().stream().filter(i->i.hoursText()!=null && !i.hoursText().isBlank()).count();
        log.info("관광 운영시간 조회 결과: 조회대상={} 지점연결={} 시간정보={} 미완료={}",results.size(),matched,withHours,budget.incomplete());
        return DraftVariants.withDays(draft,List.copyOf(days),warnings.stream().distinct().toList(),draft.options(),draft.selectedOption());
    }
    private static String key(PlaceDraft p){return p.placeId()+"|"+p.name()+"|"+p.address()+"|"+p.latitude()+"|"+p.longitude();}
}
