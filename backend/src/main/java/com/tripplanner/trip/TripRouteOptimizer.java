package com.tripplanner.trip;

import com.tripplanner.place.Geo;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;

/** Reduces straight-line travel between confirmed coordinates without moving fixed events or time slots. */
final class TripRouteOptimizer {
    private TripRouteOptimizer() {}

    static TripDraft optimize(TripDraft draft) {
        return optimize(draft, (a, b) -> Geo.distanceMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude()));
    }

    static TripDraft optimize(TripDraft draft, java.util.function.ToDoubleBiFunction<PlaceDraft, PlaceDraft> cost) {
        return optimize(draft,cost,(p,time)->true);
    }
    static TripDraft optimize(TripDraft draft, java.util.function.ToDoubleBiFunction<PlaceDraft, PlaceDraft> cost, java.util.function.BiPredicate<PlaceDraft,String> eligible) {
        List<DayDraft> days = new ArrayList<>();
        for (DayDraft day : draft.days()) {
            List<PlaceDraft> route = new ArrayList<>(day.places());
            Map<PlaceDraft, Integer> originalTimes = new HashMap<>();
            for (PlaceDraft p : route) if (p.fixedEventId() == null && p.visitTime() != null)
                originalTimes.put(p, TravelSchedule.minute(p.visitTime()));
            double best = distance(route, draft.travelDetails(), day.dayNumber(), cost);
            // Small bounded local search; improvements only, never cross a fixed commitment.
            for (int pass = 0; pass < 30; pass++) {
                boolean improved = false;
                for (int i = 0; i < route.size(); i++) {
                    if (route.get(i).fixedEventId() != null) continue;
                    for (int j = i + 1; j < route.size(); j++) {
                        if (route.get(j).fixedEventId() != null) break;
                        PlaceDraft a = route.get(i), b = route.get(j);
                        if (!known(a) || !known(b) || !eligible.test(a,b.visitTime()) || !eligible.test(b,a.visitTime()) || !fits(a, b.visitTime(), originalTimes)
                                || !fits(b, a.visitTime(), originalTimes)
                                || !ExtraRequestRules.mealTimeAllowed(a,b.visitTime(),draft.effectiveRequest(),day.dayNumber(),draft.days().size())
                                || !ExtraRequestRules.mealTimeAllowed(b,a.visitTime(),draft.effectiveRequest(),day.dayNumber(),draft.days().size())) continue;
                        PlaceDraft movedA = at(a, b.visitTime()), movedB = at(b, a.visitTime());
                        route.set(i, movedB); route.set(j, movedA);
                        if((route.stream().anyMatch(DraftMealPlanner::scheduledMeal) && !DraftMealPlanner.validOrder(route))
                                || !ExtraRequestRules.lastRestaurantsSatisfied(draft.effectiveRequest(),day.dayNumber(),draft.days().size(),route)) {
                            route.set(i,a); route.set(j,b); continue;
                        }
                        double next = distance(route, draft.travelDetails(), day.dayNumber(), cost);
                        if (next + 1 < best) {
                            originalTimes.put(movedA, originalTimes.get(a));
                            originalTimes.put(movedB, originalTimes.get(b));
                            best = next; improved = true;
                        } else { route.set(i, a); route.set(j, b); }
                    }
                }
                if (!improved) break;
            }
            days.add(new DayDraft(day.dayNumber(), day.date(), List.copyOf(route)));
        }
        return new TripDraft(draft.title(), draft.destination(), draft.startDate(), draft.endDate(), draft.budget(),
                draft.styles(), days, draft.source(), draft.warnings(), draft.request(), draft.travelDetails()).withRequestMetadata(draft.request(),draft.interpretedRequest());
    }

    static boolean fits(PlaceDraft p, String slot, Map<PlaceDraft, Integer> original) {
        if (slot == null || !original.containsKey(p)) return false;
        if (DraftMealPlanner.scheduledMeal(p)) return true;
        int time = TravelSchedule.minute(slot);
        String text = Objects.toString(p.name(), "") + Objects.toString(p.reason(), "") + Objects.toString(p.note(), "");
        if (!text.contains("마지막 식당 식사 요청:") && text.matches("(?s).*(저녁|디너).*" ) && time < 17 * 60) return false;
        if (!text.contains("마지막 식당 식사 요청:") && text.matches("(?s).*(점심|런치).*" ) && (time < 11 * 60 || time > 14 * 60)) return false;
        if (!text.contains("마지막 식당 식사 요청:") && text.matches("(?s).*(아침|조식).*" ) && time > 10 * 60 + 30) return false;
        boolean food = Set.of("식당", "카페").contains(Objects.toString(p.category(), ""));
        return !(text.contains("야경") || (!food && original.get(p) >= 18 * 60)) || time >= 18 * 60;
    }

    static PlaceDraft at(PlaceDraft p, String time) {
        return new PlaceDraft(p.placeId(), p.name(), p.address(), p.latitude(), p.longitude(), time,
                p.reason(), p.note(), p.category(), p.verified(), p.placeUrl(), p.phone(), p.fixedEventId(), p.categoryPath()).withBusinessHours(p.businessHours());
    }

    static boolean known(PlaceDraft p) {
        return p.latitude() != null && p.longitude() != null && Double.isFinite(p.latitude()) && Double.isFinite(p.longitude());
    }

    private static double distance(List<PlaceDraft> route, TravelDetails details, int day,
                                   java.util.function.ToDoubleBiFunction<PlaceDraft, PlaceDraft> cost) {
        List<PlaceDraft> points = new ArrayList<>();
        if (details != null && details.stays() != null) for (Stay stay : details.stays())
            if (stay.checkInDay() < day && stay.checkOutDay() >= day && stay.latitude() != null && stay.longitude() != null) {
                points.add(anchor(stay)); break;
            }
        for (PlaceDraft p : route) if (known(p)) points.add(p);
        if (details != null && details.stays() != null) for (Stay stay : details.stays())
            if (stay.checkInDay() <= day && stay.checkOutDay() > day && stay.latitude() != null && stay.longitude() != null) {
                points.add(anchor(stay)); break;
            }
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += cost.applyAsDouble(points.get(i - 1), points.get(i));
        return total;
    }
    static PlaceDraft anchor(Stay s) {
        return new PlaceDraft(s.placeId(), s.name(), s.address(), s.latitude(), s.longitude(), "08:00", "", "", "숙소", true, s.placeUrl(), null);
    }
}
