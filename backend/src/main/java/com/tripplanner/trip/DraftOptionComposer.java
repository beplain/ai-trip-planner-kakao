package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.place.Geo;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;

/** Recombines server-verified candidates. Does not call AI, accept client candidates or relax fixed commitments. */
final class DraftOptionComposer {
    private DraftOptionComposer() {}
    static List<TripDraft> compose(TripDraft base, Map<Integer,List<PlaceDraft>> pool) {
        List<TripDraft> options = new ArrayList<>(); options.add(base);
        for (boolean relaxed : new boolean[]{true,false}) {
            try { options.add(variant(base,pool,relaxed)); }
            catch (IllegalArgumentException | com.tripplanner.common.ApiException e) { options.add(fallback(base,relaxed)); }
        }
        return options;
    }
    private static TripDraft variant(TripDraft base, Map<Integer,List<PlaceDraft>> pool, boolean relaxed) {
        TravelDetails details = base.travelDetails() == null ? new TravelDetails(MovementMode.PUBLIC_TRANSIT,List.of(),null) : base.travelDetails();
        var request = new ItineraryRequest(base.destination(),base.startDate(),base.days().size(),base.budget(),base.styles(),base.effectiveRequest(),details);
        List<DayDraft> days = new ArrayList<>(); Set<String> used = new HashSet<>(), names = new HashSet<>();
        List<String> warnings = new ArrayList<>(base.warnings() == null ? List.of() : base.warnings());
        for (DayDraft day : base.days()) {
            LinkedHashMap<String,PlaceDraft> candidates = new LinkedHashMap<>();
            // Keep base candidates even when AI alternatives were unavailable.
            for (PlaceDraft p : day.places()) if (p.fixedEventId() == null && p.verified()) candidates.put(p.placeId(),p);
            for (PlaceDraft p : pool.getOrDefault(day.dayNumber(),List.of())) if (p.fixedEventId() == null && p.verified()) candidates.putIfAbsent(p.placeId(),p);
            candidates.values().removeIf(p -> !ExtraRequestRules.allows(base.effectiveRequest(), p.name(), p.category()));
            List<PlaceDraft> ordered = new ArrayList<>(candidates.values());
            if (relaxed && !ordered.isEmpty()) {
                PlaceDraft anchor = day.places().stream().filter(TripRouteOptimizer::known).findFirst().orElse(ordered.get(0));
                ordered.sort(Comparator.comparingDouble(p -> TripRouteOptimizer.known(p) && TripRouteOptimizer.known(anchor)
                        ? Geo.distanceMeters(anchor.latitude(),anchor.longitude(),p.latitude(),p.longitude()) : Double.MAX_VALUE));
            } else Collections.reverse(ordered);
            List<ExtraRequestRules.Visit> counted=new ArrayList<>();
            for(DayDraft previousDay:days) for(PlaceDraft previous:previousDay.places()) if(previous.fixedEventId()==null)
                counted.add(new ExtraRequestRules.Visit(previousDay.dayNumber(),previous.name(),previous.category()));
            ordered.removeIf(p -> {
                if(!ExtraRequestRules.allowsAdditional(base.effectiveRequest(),p.name(),p.category(),day.dayNumber(),base.days().size(),counted)) return true;
                counted.add(new ExtraRequestRules.Visit(day.dayNumber(),p.name(),p.category())); return false;
            });
            List<AiPlace> alternatives = ordered.stream().filter(p -> !used.contains(p.placeId())
                    && !names.contains(ItineraryRules.nameKey(p.name(),base.destination()))).map(p -> asAi(p,base.styles())).toList();
            int limit = relaxed ? 2 : 8;
            var remaining=new LinkedHashSet<>(base.styles()==null?List.<String>of():base.styles());
            days.forEach(d->remaining.removeAll(coverage(d.places(),base.styles())));
            var dailyRequest=new ItineraryRequest(request.destination(),request.startDate(),request.days(),request.budget(),List.copyOf(remaining),request.extraRequest(),details);
            var plan = ItineraryScheduler.prepareBestEffort(new AiItinerary(base.title(),List.of(new AiDay(day.dayNumber(),List.of(),alternatives))),dailyRequest,limit).days().get(0);
            List<PlaceDraft> places = new ArrayList<>();
            for (AiPlace p : plan.places()) {
                PlaceDraft original = candidates.get(p.searchQuery());
                places.add(TripRouteOptimizer.at(original,p.visitTime()));
                used.add(original.placeId()); names.add(ItineraryRules.nameKey(original.name(),base.destination()));
            }

            if (places.isEmpty() && day.places().stream().anyMatch(p -> p.fixedEventId() == null)) throw new IllegalArgumentException("No visits");
            day.places().stream().filter(p -> p.fixedEventId() != null).forEach(places::add);
            places.sort(Comparator.comparing(PlaceDraft::visitTime));
            days.add(new DayDraft(day.dayNumber(),day.date(),List.copyOf(places)));
        }
        var result = new TripDraft(base.title(),base.destination(),base.startDate(),base.endDate(),base.budget(),base.styles(),days,
                base.source(),warnings.stream().distinct().toList(),base.request(),base.travelDetails()).withRequestMetadata(base.request(),base.interpretedRequest());
        requireCoverage(result);
        ExtraRequestRules.require(result);
        TravelSchedule.validateDraft(result);
        return TripRouteOptimizer.optimize(result);
    }
    static void requireCoverage(TripDraft draft) {
        var missing=new LinkedHashSet<>(draft.styles()==null?List.<String>of():draft.styles());
        draft.days().forEach(d->missing.removeAll(coverage(d.places(),draft.styles())));
        if(!missing.isEmpty())throw new IllegalArgumentException("여행 전체에 빠진 스타일: "+String.join(", ",missing));
    }
    // Build another feasible profile from already checked visits, never copy the same plan as a new type.
    static TripDraft fallback(TripDraft base,boolean relaxed) {
        TripDraft result=base;
        if(relaxed) {
            for(var day:base.days()) for(var p:day.places()) {
                if(p.fixedEventId()!=null)continue;
                var current=result;
                var days=current.days().stream().map(d->d.dayNumber()==day.dayNumber()
                    ?new DayDraft(d.dayNumber(),d.date(),d.places().stream().filter(v->!Objects.equals(v.placeId(),p.placeId())).toList()):d).toList();
                var trial=DraftVariants.withDays(current,days,current.warnings(),null,null);
                if(trial.days().stream().anyMatch(d->d.places().stream().noneMatch(v->v.fixedEventId()==null)))continue;
                try {requireCoverage(trial);ExtraRequestRules.require(trial);TravelSchedule.validateDraft(trial);result=trial;}
                catch(IllegalArgumentException ignored) {} catch(com.tripplanner.common.ApiException ignored) {}
            }
            if(!fingerprint(result).equals(fingerprint(base)))return result;
        }
        // With no optional visit to drop, use a different feasible order of the confirmed visits.
        for(var day:base.days())for(int i=0;i<day.places().size();i++)for(int j=i+1;j<day.places().size();j++) {
            var a=day.places().get(i);var b=day.places().get(j);
            if(a.fixedEventId()!=null||b.fixedEventId()!=null)continue;
            var visits=new ArrayList<>(day.places());
            visits.set(i,TripRouteOptimizer.at(b,a.visitTime()).withBusinessHours(null));
            visits.set(j,TripRouteOptimizer.at(a,b.visitTime()).withBusinessHours(null));
            var days=base.days().stream().map(d->d.dayNumber()==day.dayNumber()?new DayDraft(d.dayNumber(),d.date(),visits):d).toList();
            var trial=DraftVariants.withDays(base,days,base.warnings(),null,null);
            try {requireCoverage(trial);ExtraRequestRules.require(trial);TravelSchedule.validateDraft(trial);return trial;}
            catch(IllegalArgumentException ignored) {} catch(com.tripplanner.common.ApiException ignored) {}
        }
        throw new IllegalArgumentException("세 가지 유형을 구성할 방문 후보를 보완해야 합니다.");
    }
    static List<TripDraft> recoveryCandidates(TripDraft base,Map<Integer,List<PlaceDraft>> pool) {
        List<TripDraft> beam=List.of(base);
        var missing=new LinkedHashSet<>(base.styles()==null?List.<String>of():base.styles());
        base.days().forEach(d->missing.removeAll(coverage(d.places(),base.styles())));
        // Restore styles removed by travel-time adjustment before trying profile diversity.
        for(String style:missing) {
            List<TripDraft> next=new ArrayList<>();
            for(var current:beam) next.addAll(substitutions(current,pool,style));
            if(next.isEmpty())return List.of();
            beam=next.stream().limit(8).toList();
        }
        List<TripDraft> result=new ArrayList<>();
        for(var current:beam) {
            if(!missing.isEmpty())result.add(current);
            result.addAll(substitutions(current,pool,null));
        }
        return result.stream().limit(24).toList();
    }
    private static List<TripDraft> substitutions(TripDraft base,Map<Integer,List<PlaceDraft>> pool,String needed) {
        List<TripDraft> result=new ArrayList<>();
        Set<String> used=new HashSet<>(),names=new HashSet<>(),covered=new HashSet<>();
        base.days().forEach(d->{covered.addAll(coverage(d.places(),base.styles()));d.places().forEach(p->{used.add(p.placeId());names.add(ItineraryRules.nameKey(p.name(),base.destination()));});});
        for(var day:base.days()) {
            var anchor=day.places().stream().filter(TripRouteOptimizer::known).findFirst().orElse(null);
            var candidates=pool.getOrDefault(day.dayNumber(),List.of()).stream()
                .filter(p->p.verified()&&p.fixedEventId()==null&&!used.contains(p.placeId())&&!names.contains(ItineraryRules.nameKey(p.name(),base.destination())))
                .filter(p->ExtraRequestRules.allows(base.effectiveRequest(),p.name(),p.category()))
                .filter(p->needed==null||coverage(List.of(p),base.styles()).contains(needed))
                .sorted(Comparator.comparingDouble(p->anchor!=null&&TripRouteOptimizer.known(p)?Geo.distanceMeters(anchor.latitude(),anchor.longitude(),p.latitude(),p.longitude()):0))
                .limit(8).toList();
            for(var candidate:candidates) {
                var slots=new LinkedHashSet<String>();day.places().stream().filter(p->p.fixedEventId()==null).map(PlaceDraft::visitTime).forEach(slots::add);
                for(int time=TravelSchedule.start(day.dayNumber(),base.travelDetails());time+60<=TravelSchedule.end(day.dayNumber(),base.days().size(),base.travelDetails());time+=60)slots.add(TravelSchedule.clock(time));
                for(String time:slots) {
                    var visits=new ArrayList<>(day.places());
                    visits.removeIf(p->p.fixedEventId()==null&&Objects.equals(p.visitTime(),time));
                    visits.add(TripRouteOptimizer.at(candidate,time).withBusinessHours(null));visits.sort(Comparator.comparing(PlaceDraft::visitTime));
                    Set<String> actual=new HashSet<>();
                    base.days().forEach(d->actual.addAll(coverage(d.dayNumber()==day.dayNumber()?visits:d.places(),base.styles())));
                    if(!actual.containsAll(covered)||needed!=null&&!actual.contains(needed))continue;
                    var days=base.days().stream().map(d->d.dayNumber()==day.dayNumber()?new DayDraft(d.dayNumber(),d.date(),List.copyOf(visits)):d).toList();
                    var trial=DraftVariants.withDays(base,days,base.warnings(),null,null);
                    try {ExtraRequestRules.require(trial);TravelSchedule.validateDraft(trial);result.add(trial);}
                    catch(IllegalArgumentException | com.tripplanner.common.ApiException ignored){}
                    if(result.size()>=24)return result;
                }
            }
        }
        return result;
    }
    private static AiPlace asAi(PlaceDraft p, List<String> styles) {
        var tagged=new AiPlace(p.name(),p.placeId(),p.visitTime(),p.category(),p.reason()+" "+Objects.toString(p.categoryPath(),""),p.note(),styles == null ? List.of() : styles);
        return new AiPlace(tagged.name(),tagged.searchQuery(),tagged.visitTime(),tagged.category(),tagged.reason(),tagged.note(),List.copyOf(ItineraryRules.stylesFor(tagged)));
    }
    private static Set<String> coverage(List<PlaceDraft> places, List<String> styles) {
        Set<String> result = new HashSet<>();
        for (PlaceDraft p : places) if (p.fixedEventId() == null) result.addAll(ItineraryRules.stylesFor(asAi(p,styles)));
        return result;
    }
    static String fingerprint(TripDraft draft) {
        StringBuilder result = new StringBuilder();
        for (DayDraft d : draft.days()) {
            result.append(d.dayNumber()).append(':');
            d.places().stream().filter(p -> p.fixedEventId() == null).forEach(p -> result.append(p.placeId()).append('|'));
            result.append(';');
        }
        return result.toString();
    }
}
