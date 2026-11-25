package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.place.BusinessHoursRules;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;

/** Preserve usable results when recommendation constraints or external lookups fail. */
final class BestEffortDrafts {
    private BestEffortDrafts() {}
    static TripDraft create(GenerateRequest input, TripDraft seed, RuntimeException failure) {
        var details=seed==null?input.travelDetails():seed.travelDetails();
        var events=TravelSchedule.events(details,input.days());
        var warnings=new LinkedHashSet<String>();
        if(seed!=null && seed.warnings()!=null)warnings.addAll(seed.warnings());
        warnings.add("일부 조건을 충족하지 못해 가능한 일정으로 먼저 만들었어요. 방문 수·스타일·식사·동선은 목표와 다를 수 있어요. 일정을 확인하고 추가·교체해 주세요.");
        if(failure instanceof ApiException && failure.getMessage()!=null) {
            String message=failure.getMessage().split(" 실제=",2)[0];
            warnings.add("조정한 조건: "+message.substring(0,Math.min(450,message.length())));
        } else warnings.add("외부 정보 조회 또는 자동 배치를 완료하지 못했습니다. 조회되지 않은 장소는 임의로 만들지 않았어요.");
        var days=new ArrayList<DayDraft>();
        Set<String> used=new HashSet<>();
        for(int day=1;day<=input.days();day++) {
            final int number=day;
            var date=input.startDate()==null?null:input.startDate().plusDays(day-1);
            var visits=new ArrayList<PlaceDraft>();
            int previousEnd=-1;
            var candidates=seed==null?List.<PlaceDraft>of():seed.days().stream().filter(d->d.dayNumber()==number)
                    .flatMap(d->d.places().stream()).filter(p->p.fixedEventId()==null)
                    .sorted(Comparator.comparing(p->Objects.toString(p.visitTime(),"99:99"))).toList();
            for(var place:candidates) {
                if(!place.verified() || !TripRouteOptimizer.known(place) || place.placeId()==null || used.contains(place.placeId()))continue;
                try {
                    int time=TravelSchedule.minute(place.visitTime());
                    if(BusinessHoursRules.evaluate(place.businessHours(),date,place.visitTime(),place.stayMinutes()).closed()) {
                        warnings.add(day+"일차 "+place.name()+": 운영시간·휴무일과 겹쳐 제외했어요.");continue;
                    }
                    if(!ExtraRequestRules.allows(input.request(),place.name(),place.category(),place.placeId()))continue;
                    if(time<previousEnd || !TravelSchedule.allowed(day,input.days(),time,details,events,place.stayMinutes()))continue;
                    visits.add(place);used.add(place.placeId());previousEnd=time+place.stayMinutes()+TravelSchedule.gap(details);
                } catch(IllegalArgumentException | ApiException ignored) { /* Keep remaining valid visits and all fixed events. */ }
            }
            long meals=visits.stream().filter(p->"식당".equals(p.category())).count();
            long count=visits.size()-meals;
            warnings.add(day+"일차: 식당 제외 "+count+"곳, 식당 "+meals+"곳을 배치했어요."+(visits.isEmpty()?" 일정 확정 후 일정 수정의 장소 추가에서 방문할 곳을 선택해 주세요.":" 영업시간과 실제 이동편을 확인해 주세요."));
            events.stream().filter(e->e.day()==number).map(TravelSchedule.Event::place).forEach(visits::add);
            visits.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
            days.add(new DayDraft(day,date,List.copyOf(visits)));
        }
        var draft=new TripDraft(seed==null?input.destination()+" 여행":seed.title(),input.destination(),input.startDate(),
                input.startDate()==null?null:input.startDate().plusDays(input.days()-1),input.budget(),input.styles(),
                List.copyOf(days),"BEST_EFFORT",List.copyOf(warnings),seed==null?input.request():seed.request(),details,null,null,
                seed==null?null:seed.interpretedRequest());
        TravelSchedule.validateDraft(draft);
        return draft;
    }
    static DraftOptions options(GenerateRequest input,TripDraft seed,RuntimeException failure) {
        var base=create(input,seed,failure);
        var warnings=new ArrayList<>(base.warnings());
        warnings.add("확보된 장소가 부족해 세 유형이 같은 일정일 수 있어요. 유형별 방문 수 기준은 이번 초안에 보장되지 않아요.");
        base=DraftVariants.withDays(base,base.days(),List.copyOf(warnings),null,null);
        var draft=base;
        return new DraftOptions(List.of(
            new DraftOption("compact","이동 최소형","조건 완화 초안 · 이동 최소 기준 일부 미충족",draft),
            new DraftOption("relaxed","최소 방문형","조건 완화 초안 · 하루 2곳 기준 일부 미충족",draft),
            new DraftOption("varied","다양한 방문형","조건 완화 초안 · 하루 4~5곳 기준 일부 미충족",draft)),List.copyOf(warnings));
    }
}
