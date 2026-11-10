package com.tripplanner.trip;

import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.common.ApiException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Verifies hours before scheduling and again after travel-time adjustments. */
@Service
public class GoogleHoursScheduler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GoogleHoursScheduler.class);
    private final GoogleOpeningHoursClient client;
    public GoogleHoursScheduler(GoogleOpeningHoursClient client){this.client=client;}
    public void requireConfigured(){if(!client.enabled())throw ApiException.badRequest("영업시간 조회 설정을 확인해 주세요. 관리자에게 문의해 주세요.");}
    public Session session(){return new Session(client.session());}
    public class Session {
        private final GoogleOpeningHoursClient.Session lookups;
        Session(GoogleOpeningHoursClient.Session lookups){this.lookups=lookups;}
        public void prefetch(List<TripDraft> drafts){
            if(drafts.stream().anyMatch(d->d.days().stream().anyMatch(day->date(d,day)==null)))throw ApiException.badRequest("운영시간을 반영하려면 여행 날짜를 선택해 주세요.");
            lookups.prefetch(drafts.stream().flatMap(d->d.days().stream()).flatMap(d->d.places().stream()).toList());}
        public TripDraft repair(TripDraft originalDraft,Map<Integer,List<PlaceDraft>> pool) {
            final TripDraft draft=relocateClosedVisits(originalDraft,pool);
            List<String> warnings=new ArrayList<>(draft.warnings()==null?List.of():draft.warnings());
            List<DayDraft> days=new ArrayList<>();Set<String> reserved=new HashSet<>();
            for(var day:draft.days())for(var p:day.places())if(p.fixedEventId()==null)reserved.add(p.placeId());
            var events=TravelSchedule.events(draft.travelDetails(),draft.days().size());
            for(var day:draft.days()) {
                LocalDate date=date(draft,day);List<PlaceDraft> out=new ArrayList<>();
                for(int i=0;i<day.places().size();i++) {
                    var p=day.places().get(i);if(p.fixedEventId()!=null){out.add(p);continue;}
                    if(allowed(p,date,p.visitTime())){out.add(p.withBusinessHours(null));continue;}
                    int original=TravelSchedule.minute(p.visitTime());PlaceDraft replacement=null;
                    List<ExtraRequestRules.Visit> others=new ArrayList<>();
                    for(var otherDay:draft.days())for(var other:otherDay.places())if(other.fixedEventId()==null && !Objects.equals(other.placeId(),p.placeId()))others.add(new ExtraRequestRules.Visit(otherDay.dayNumber(),other.name(),other.category()));
                    for(var previousDay:days)for(var previous:previousDay.places())if(previous.fixedEventId()==null && !draft.days().stream().flatMap(d->d.places().stream()).anyMatch(v->Objects.equals(v.placeId(),previous.placeId())))others.add(new ExtraRequestRules.Visit(previousDay.dayNumber(),previous.name(),previous.category()));
                    for(var previous:out)if(previous.fixedEventId()==null && !day.places().stream().anyMatch(v->Objects.equals(v.placeId(),previous.placeId())))others.add(new ExtraRequestRules.Visit(day.dayNumber(),previous.name(),previous.category()));
                    List<PlaceDraft> candidates=pool.getOrDefault(day.dayNumber(),List.of()).stream()
                        .filter(c->c.verified()&&c.fixedEventId()==null&&Objects.equals(c.category(),p.category())&&!reserved.contains(c.placeId()))
                        .sorted(Comparator.comparingDouble(c->distance(p,c))).limit(12).toList();
                    for(var c:candidates) {
                        if(!ExtraRequestRules.allows(draft.effectiveRequest(),c.name(),c.category())
                            ||!ExtraRequestRules.allowsAdditional(draft.effectiveRequest(),c.name(),c.category(),day.dayNumber(),draft.days().size(),others)
                            ||!ExtraRequestRules.allowsMealHit(draft.effectiveRequest(),day.dayNumber(),draft.days().size(),p.name(),p.category(),p.reason(),c.name(),c.categoryPath()))continue;
                        var moved=ExtraRequestRules.annotateMeal(TripRouteOptimizer.at(c,p.visitTime()),draft.effectiveRequest(),day.dayNumber(),draft.days().size());
                        if(!ExtraRequestRules.mealTimeAllowed(moved,p.visitTime(),draft.effectiveRequest(),day.dayNumber(),draft.days().size()))continue;
                        if(allowed(moved,date,p.visitTime())&&requestsPreserved(draft,days,day,out,i,moved)){replacement=moved;break;}
                    }
                    // If no same-slot alternative is confirmed, move the original only within the next commitment.
                    if(replacement==null) {
                        int limit=TravelSchedule.end(day.dayNumber(),draft.days().size(),draft.travelDetails())-60;
                        if(i+1<day.places().size())limit=Math.min(limit,TravelSchedule.minute(day.places().get(i+1).visitTime())-60-TravelSchedule.gap(draft.travelDetails()));
                        for(int time=original+10;time<=limit;time+=10) {
                            String slot=TravelSchedule.clock(time);
                            if(TravelSchedule.allowed(day.dayNumber(),draft.days().size(),time,draft.travelDetails(),events)
                                &&TripRouteOptimizer.fits(p,slot,Map.of(p,original))
                                &&ExtraRequestRules.mealTimeAllowed(p,slot,draft.effectiveRequest(),day.dayNumber(),draft.days().size())&&allowed(p,date,slot)&&requestsPreserved(draft,days,day,out,i,TripRouteOptimizer.at(p,slot))) {
                                replacement=TripRouteOptimizer.at(p,slot);break;
                            }
                        }
                    }
                    if(replacement==null)throw ApiException.badRequest(p.name()+": 여행 기간 중 방문 가능한 날짜·시간이나 대체 장소를 확보하지 못했습니다. 여행 기간 또는 조건을 조정해 주세요.");
                    else {
                        reserved.add(replacement.placeId());out.add(replacement.withBusinessHours(null));
                        warnings.add(day.dayNumber()+"일차 "+p.name()+": 운영시간을 고려해 "+replacement.name()+" "+replacement.visitTime()+" 방문으로 조정했습니다.");
                    }
                }
                days.add(new DayDraft(day.dayNumber(),day.date(),List.copyOf(out)));
            }
            lookups.requireReady();
            return DraftVariants.withDays(draft,days,warnings.stream().distinct().toList(),draft.options(),draft.selectedOption());
        }
        private TripDraft relocateClosedVisits(TripDraft draft,Map<Integer,List<PlaceDraft>> pool) {
            if(draft.days().size()<2)return draft;
            TripDraft current=draft;
            int moved=0;
            // Each originally scheduled visit is considered once. Swaps preserve daily visit counts.
            for(var source:draft.days())for(var original:source.places()) {
                if(original.fixedEventId()!=null)continue;
                int sourceIndex=source.dayNumber()-1;
                var sourceDay=current.days().get(sourceIndex);
                int placeIndex=-1;
                for(int i=0;i<sourceDay.places().size();i++)
                    if(Objects.equals(sourceDay.places().get(i).placeId(),original.placeId())
                        &&Objects.equals(sourceDay.places().get(i).name(),original.name())) {placeIndex=i;break;}
                if(placeIndex<0)continue;
                var place=sourceDay.places().get(placeIndex);
                if(lookups.lookup(place).assess(date(current,sourceDay),place.visitTime(),60).state()!=GoogleOpeningHours.State.CLOSED)continue;
                var changed=relocate(current,sourceIndex,placeIndex,place,pool);
                if(changed!=null){current=changed;moved++;}
            }
            if(moved>0)log.info("Google 영업시간 날짜 재배치: 이동방문={}",moved);
            return current;
        }
        private TripDraft relocate(TripDraft draft,int sourceIndex,int placeIndex,PlaceDraft place,Map<Integer,List<PlaceDraft>> pool) {
            var source=draft.days().get(sourceIndex);
            // Try existing slots first, preferring the same category before a different-category swap.
            for(boolean sameCategory:new boolean[]{true,false})for(int targetIndex=0;targetIndex<draft.days().size();targetIndex++) {
                if(targetIndex==sourceIndex)continue;
                var target=draft.days().get(targetIndex);
                for(int j=0;j<target.places().size();j++) {
                    var partner=target.places().get(j);
                    if(partner.fixedEventId()!=null||Objects.equals(place.category(),partner.category())!=sameCategory)continue;
                    if(!canMove(draft,place,target,partner.visitTime(),true)||!canMove(draft,partner,source,place.visitTime(),false))continue;
                    var left=new ArrayList<>(source.places());var right=new ArrayList<>(target.places());
                    left.set(placeIndex,TripRouteOptimizer.at(partner,place.visitTime()).withBusinessHours(null));
                    right.set(j,TripRouteOptimizer.at(place,partner.visitTime()).withBusinessHours(null));
                    var trial=relocationTrial(draft,sourceIndex,left,targetIndex,right,place);
                    if(trial!=null)return trial;
                }
            }
            // If a swap is impossible, retain the venue by inserting it in a free slot on an open day.
            for(int targetIndex=0;targetIndex<draft.days().size();targetIndex++) {
                if(targetIndex==sourceIndex)continue;
                var target=draft.days().get(targetIndex);
                int start=TravelSchedule.start(target.dayNumber(),draft.travelDetails());
                int end=TravelSchedule.end(target.dayNumber(),draft.days().size(),draft.travelDetails())-60;
                for(int time=((start+9)/10)*10;time<=end;time+=10) {
                    String slot=TravelSchedule.clock(time);
                    if(!canMove(draft,place,target,slot,true))continue;
                    var left=new ArrayList<>(source.places());left.remove(placeIndex);
                    // Keep the source day useful too, while retaining the original venue on its open day.
                    if(left.stream().noneMatch(p->p.fixedEventId()==null)) {
                        var used=draft.days().stream().flatMap(d->d.places().stream()).map(PlaceDraft::placeId).toList();
                        PlaceDraft fill=null;
                        for(var candidate:pool.getOrDefault(source.dayNumber(),List.of()).stream().limit(12).toList()) {
                            if(!candidate.verified()||candidate.fixedEventId()!=null||used.contains(candidate.placeId())
                                ||!Objects.equals(candidate.category(),place.category()))continue;
                            if(canMove(draft,TripRouteOptimizer.at(candidate,place.visitTime()),source,place.visitTime(),false)) {
                                var test=new ArrayList<>(left);test.add(TripRouteOptimizer.at(candidate,place.visitTime()).withBusinessHours(null));
                                test.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
                                var rightTrial=new ArrayList<>(target.places());rightTrial.add(TripRouteOptimizer.at(place,slot).withBusinessHours(null));
                                rightTrial.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
                                if(relocationTrial(draft,sourceIndex,test,targetIndex,rightTrial,place)!=null){fill=candidate;break;}
                            }
                        }
                        if(fill==null)continue;
                        left.add(TripRouteOptimizer.at(fill,place.visitTime()).withBusinessHours(null));
                        left.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
                    }
                    var right=new ArrayList<>(target.places());right.add(TripRouteOptimizer.at(place,slot).withBusinessHours(null));
                    right.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
                    var trial=relocationTrial(draft,sourceIndex,left,targetIndex,right,place);
                    if(trial!=null)return trial;
                }
            }
            return null;
        }
        private boolean canMove(TripDraft draft,PlaceDraft place,DayDraft target,String slot,boolean requireOpen) {
            if(!TripRouteOptimizer.fits(place,slot,Map.of(place,TravelSchedule.minute(place.visitTime())))
                ||!ExtraRequestRules.mealTimeAllowed(place,slot,draft.effectiveRequest(),target.dayNumber(),draft.days().size()))return false;
            var state=lookups.lookup(place).assess(date(draft,target),slot,60).state();
            return requireOpen?state==GoogleOpeningHours.State.OPEN:state!=GoogleOpeningHours.State.CLOSED;
        }
        private TripDraft relocationTrial(TripDraft draft,int source,List<PlaceDraft> left,int target,List<PlaceDraft> right,PlaceDraft moved) {
            if(!validRelocatedDay(draft,draft.days().get(source),left)||!validRelocatedDay(draft,draft.days().get(target),right))return null;
            var days=new ArrayList<>(draft.days());
            var a=days.get(source);var b=days.get(target);
            days.set(source,new DayDraft(a.dayNumber(),a.date(),List.copyOf(left)));
            days.set(target,new DayDraft(b.dayNumber(),b.date(),List.copyOf(right)));
            var trial=DraftVariants.withDays(draft,days,draft.warnings(),draft.options(),draft.selectedOption());
            if(!ExtraRequestRules.violations(trial).isEmpty())return null;
            var warnings=new ArrayList<>(draft.warnings()==null?List.<String>of():draft.warnings());
            warnings.add(moved.name()+": 운영시간을 고려해 "+a.dayNumber()+"일차에서 "+b.dayNumber()+"일차로 재배치했습니다.");
            return DraftVariants.withDays(trial,days,warnings,trial.options(),trial.selectedOption());
        }
        private boolean validRelocatedDay(TripDraft draft,DayDraft day,List<PlaceDraft> places) {
            var events=TravelSchedule.events(draft.travelDetails(),draft.days().size());
            int previous=-1,previousVisit=-1;
            int gap=draft.travelDetails()==null?45:TravelSchedule.gap(draft.travelDetails());
            for(var p:places) {
                int time=TravelSchedule.minute(p.visitTime());
                if(time<=previous)return false;
                previous=time;
                if(p.fixedEventId()!=null)continue;
                if(previousVisit>=0&&time<previousVisit+60+gap)return false;
                previousVisit=time;
                if(!TravelSchedule.allowed(day.dayNumber(),draft.days().size(),time,draft.travelDetails(),events))return false;
            }
            return true;
        }
        public TripDraft verify(TripDraft draft) { return verify(draft,false); }
        public TripDraft verifyOption(TripDraft draft) { return verify(draft,true); }
        private TripDraft verify(TripDraft draft,boolean allowEmpty) {
            List<String> warnings=new ArrayList<>(draft.warnings()==null?List.of():draft.warnings());List<DayDraft> days=new ArrayList<>();
            int count=0,confirmed=0,unconfirmed=0;
            for(var day:draft.days()) {
                List<PlaceDraft> out=new ArrayList<>();
                for(var p:day.places()) {
                    if(p.fixedEventId()!=null){out.add(p);continue;}
                    var assessment=lookups.lookup(p).assess(date(draft,day),p.visitTime(),60);
                    if(assessment.state()!=GoogleOpeningHours.State.CLOSED){
                        // Store only our validation receipt, never Google's raw opening-hour periods or place identifier.
                        var receipt=new BusinessHoursInfo(null,null,null,Instant.now().toString(),date(draft,day).toString(),
                            assessment.open()?"VERIFIED":"UNKNOWN",assessment.reason(),p.visitTime(),assessment.open()?(assessment.regularOnly()?"REGULAR":"DATED"):"UNKNOWN");
                        out.add(p.withBusinessHours(receipt));count++;
                        if(assessment.open())confirmed++;
                        else {
                            unconfirmed++;
                            log.info("Google 영업시간 미확인: 장소id={} 이유={}",p.placeId(),assessment.reason());
                        }
                    }
                    else {
                        // Never return a smaller itinerary by silently discarding a known-closed venue.
                        if(allowEmpty)return null;
                        throw ApiException.badRequest(p.name()+": 이동시간 보정 후 운영시간과 맞지 않습니다. 초안을 다시 생성해 주세요.");
                    }
                }
                if(out.stream().noneMatch(p->p.fixedEventId()==null))warnings.add(day.dayNumber()+"일차는 운영시간과 여행 조건을 충족하는 일반 방문 장소를 확보하지 못했습니다.");
                days.add(new DayDraft(day.dayNumber(),day.date(),List.copyOf(out)));
            }
            lookups.requireReady();
            log.info("Google 영업시간 검증: API호출={} 확인방문={} 미확인유지={} 최종제외={}",lookups.calls(),confirmed,unconfirmed,0);
            if(count==0 && allowEmpty)return null;
            if(count==0)throw ApiException.badRequest("운영시간·휴무와 여행 조건을 충족하는 장소가 없어 초안을 만들지 못했습니다. 여행 시간·스타일을 조정해 주세요.");
            if(confirmed>0)warnings.add("Google Maps 운영시간이 확인된 장소에 한해 일반 방문 60분을 검사했습니다.");
            return DraftVariants.withDays(draft,days,warnings.stream().distinct().toList(),draft.options(),draft.selectedOption());
        }
        public PlaceDraft checkEdited(PlaceDraft place, LocalDate date) {
            if(place.fixedEventId()!=null)return place;
            var assessment=lookups.lookup(place).assess(date,place.visitTime(),60);
            lookups.requireReady();
            if(assessment.state()==GoogleOpeningHours.State.CLOSED)
                throw ApiException.badRequest(place.name()+": 변경한 방문 시각은 운영시간 밖이거나 휴무일입니다. 다른 시각을 선택해 주세요.");
            return place.withBusinessHours(new BusinessHoursInfo(null,null,null,Instant.now().toString(),
                date==null?null:date.toString(),assessment.open()?"VERIFIED":"UNKNOWN",assessment.reason(),
                place.visitTime(),assessment.open()?(assessment.regularOnly()?"REGULAR":"DATED"):"UNKNOWN"));
        }
        void prefetchMealCandidates(List<PlaceDraft> candidates) {
            lookups.prefetchMealCandidates(candidates);
        }

        boolean canVisit(TripDraft draft, DayDraft day, PlaceDraft place) {
            return allowed(place, date(draft, day), place.visitTime());
        }

        public boolean fits(TripDraft draft,PlaceDraft place,String time) {
            if(place.fixedEventId()!=null)return true;
            for(var day:draft.days())if(day.places().stream().anyMatch(p->Objects.equals(p.placeId(),place.placeId())))return allowed(place,date(draft,day),time);
            return false;
        }
        private boolean allowed(PlaceDraft p,LocalDate date,String time){return lookups.lookup(p).assess(date,time,60).state()!=GoogleOpeningHours.State.CLOSED;}
    }
    private static boolean requestsPreserved(TripDraft draft,List<DayDraft> completed,DayDraft current,List<PlaceDraft> before,int index,PlaceDraft replacement) {
        List<PlaceDraft> places=new ArrayList<>(before);places.add(replacement);places.addAll(current.places().subList(index+1,current.places().size()));
        List<DayDraft> trial=new ArrayList<>();
        for(var day:draft.days()) {
            if(day.dayNumber()==current.dayNumber())trial.add(new DayDraft(day.dayNumber(),day.date(),places));
            else trial.add(completed.stream().filter(d->d.dayNumber()==day.dayNumber()).findFirst().orElse(day));
        }
        return ExtraRequestRules.violations(DraftVariants.withDays(draft,trial,draft.warnings(),null,null)).isEmpty();
    }
    private static LocalDate date(TripDraft draft,DayDraft day){return day.date()!=null?day.date():draft.startDate()==null?null:draft.startDate().plusDays(day.dayNumber()-1);}
    private static double distance(PlaceDraft a,PlaceDraft b){return TripRouteOptimizer.known(a)&&TripRouteOptimizer.known(b)?Geo.distanceMeters(a.latitude(),a.longitude(),b.latitude(),b.longitude()):Double.POSITIVE_INFINITY;}
}
