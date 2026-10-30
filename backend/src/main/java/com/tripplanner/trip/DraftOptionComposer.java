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
            catch (IllegalArgumentException e) { options.add(base); /* Preserve profile positions; deduplicate before returning. */ }
        }
        return options;
    }
    private static TripDraft variant(TripDraft base, Map<Integer,List<PlaceDraft>> pool, boolean relaxed) {
        TravelDetails details = base.travelDetails() == null ? new TravelDetails(MovementMode.PUBLIC_TRANSIT,List.of(),null) : base.travelDetails();
        var request = new ItineraryRequest(base.destination(),base.startDate(),base.days().size(),base.budget(),base.styles(),base.request(),details);
        List<DayDraft> days = new ArrayList<>(); Set<String> used = new HashSet<>(), names = new HashSet<>();
        List<String> warnings = new ArrayList<>(base.warnings() == null ? List.of() : base.warnings());
        for (DayDraft day : base.days()) {
            LinkedHashMap<String,PlaceDraft> candidates = new LinkedHashMap<>();
            // Keep base candidates even when AI alternatives were unavailable.
            for (PlaceDraft p : day.places()) if (p.fixedEventId() == null && p.verified()) candidates.put(p.placeId(),p);
            for (PlaceDraft p : pool.getOrDefault(day.dayNumber(),List.of())) if (p.fixedEventId() == null && p.verified()) candidates.putIfAbsent(p.placeId(),p);
            candidates.values().removeIf(p -> !ExtraRequestRules.allows(base.request(), p.name(), p.category()));
            List<PlaceDraft> ordered = new ArrayList<>(candidates.values());
            if (relaxed && !ordered.isEmpty()) {
                PlaceDraft anchor = day.places().stream().filter(TripRouteOptimizer::known).findFirst().orElse(ordered.get(0));
                ordered.sort(Comparator.comparingDouble(p -> TripRouteOptimizer.known(p) && TripRouteOptimizer.known(anchor)
                        ? Geo.distanceMeters(anchor.latitude(),anchor.longitude(),p.latitude(),p.longitude()) : Double.MAX_VALUE));
            } else Collections.reverse(ordered);
            List<AiPlace> alternatives = ordered.stream().filter(p -> !used.contains(p.placeId())
                    && !names.contains(ItineraryRules.nameKey(p.name(),base.destination()))).map(p -> asAi(p,base.styles())).toList();
            int limit = relaxed ? Math.max(2,base.styles() == null ? 0 : base.styles().size()) : 8;
            var plan = ItineraryScheduler.prepareBestEffort(new AiItinerary(base.title(),List.of(new AiDay(day.dayNumber(),List.of(),alternatives))),request,limit).days().get(0);
            List<PlaceDraft> places = new ArrayList<>();
            for (AiPlace p : plan.places()) {
                PlaceDraft original = candidates.get(p.searchQuery());
                places.add(TripRouteOptimizer.at(original,p.visitTime()));
                used.add(original.placeId()); names.add(ItineraryRules.nameKey(original.name(),base.destination()));
            }
            Set<String> baselineCoverage = coverage(day.places(),base.styles()), actualCoverage = coverage(places,base.styles());
            if (!actualCoverage.containsAll(baselineCoverage)) throw new IllegalArgumentException("Variant loses available styles");
            if (places.isEmpty() && day.places().stream().anyMatch(p -> p.fixedEventId() == null)) throw new IllegalArgumentException("No visits");
            Set<String> missing = new LinkedHashSet<>(base.styles() == null ? List.of() : base.styles()); missing.removeAll(actualCoverage);
            if (!missing.isEmpty()) warnings.add(day.dayNumber()+"일차: 확인된 후보와 시간의 제약으로 "+String.join(", ",missing)+" 스타일을 충분히 반영하지 못했습니다.");
            day.places().stream().filter(p -> p.fixedEventId() != null).forEach(places::add);
            places.sort(Comparator.comparing(PlaceDraft::visitTime));
            days.add(new DayDraft(day.dayNumber(),day.date(),List.copyOf(places)));
        }
        var result = new TripDraft(base.title(),base.destination(),base.startDate(),base.endDate(),base.budget(),base.styles(),days,
                base.source(),warnings.stream().distinct().toList(),base.request(),base.travelDetails());
        TravelSchedule.validateDraft(result);
        return TripRouteOptimizer.optimize(result);
    }
    private static AiPlace asAi(PlaceDraft p, List<String> styles) {
        return new AiPlace(p.name(),p.placeId(),p.visitTime(),p.category(),p.reason()+" "+Objects.toString(p.categoryPath(),""),p.note(),styles == null ? List.of() : styles);
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
