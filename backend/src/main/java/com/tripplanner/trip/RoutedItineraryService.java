package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Applies directional route durations once, after AI/Kakao candidate repair has finished. */
@Service
public class RoutedItineraryService {
    private final RouteTimeClient routes;
    private final TransportArrivalService arrival;
    public RoutedItineraryService(RouteTimeClient routes) { this(routes,new TransportArrivalService(routes)); }
    @org.springframework.beans.factory.annotation.Autowired
    public RoutedItineraryService(RouteTimeClient routes,TransportArrivalService arrival) { this.routes = routes; this.arrival=arrival; }

    GenerateRequest resolveTransport(GenerateRequest request) {
        return arrival.resolve(request);
    }

    public TripDraft adjust(TripDraft draft) { return adjust(draft,(p,time)->true); }
    public TripDraft adjust(TripDraft draft, java.util.function.BiPredicate<PlaceDraft,String> eligible) {
        TravelDetails details = draft.travelDetails();
        var session = routes.session(details == null ? MovementMode.PUBLIC_TRANSIT : details.movementMode());
        return adjust(draft,session,eligible);
    }

    List<TripDraft> adjustOptions(List<TripDraft> drafts) { return adjustOptions(drafts,d->(p,time)->true); }
    List<TripDraft> adjustOptions(List<TripDraft> drafts, java.util.function.Function<TripDraft,java.util.function.BiPredicate<PlaceDraft,String>> eligibility) {
        if(drafts.isEmpty())return List.of();
        TravelDetails details = drafts.get(0).travelDetails();
        var session = routes.session(details == null ? MovementMode.PUBLIC_TRANSIT : details.movementMode());
        return drafts.stream().map(draft -> adjust(draft,session,eligibility.apply(draft))).toList();
    }

    private TripDraft adjust(TripDraft draft, RouteTimeClient.Session session, java.util.function.BiPredicate<PlaceDraft,String> eligible) {
        TravelDetails details = draft.travelDetails();
        List<String> warnings = new ArrayList<>(draft.warnings() == null ? List.of() : draft.warnings());
        if (!session.enabled()) {
            warnings.add("실제 경로 조회 키가 설정되지 않아 직선거리로 동선을 보정했습니다. 이동 시간은 확인되지 않았습니다.");
            return copy(draft, draft.days(), warnings);
        }
        List<List<PlaceDraft>> paths = new ArrayList<>();
        for (DayDraft day : draft.days()) {
            List<PlaceDraft> path = new ArrayList<>();
            PlaceDraft start = overnight(details,day.dayNumber(),true), end = overnight(details,day.dayNumber(),false);
            if (start != null) path.add(start);
            path.addAll(day.places());
            if (end != null) path.add(end);
            paths.add(path);
        }
        session.prefetch(paths);
        // Read current legs first. Unknown legs must never make an unmeasured swap look faster.
        TripDraft optimized = TripRouteOptimizer.optimize(draft, (a, b) -> routeCost(session, a, b),eligible);
        List<DayDraft> days = new ArrayList<>();
        var events = TravelSchedule.events(details, draft.days().size());
        for (DayDraft day : optimized.days()) {
            List<PlaceDraft> out = new ArrayList<>();
            PlaceDraft previous = overnight(details, day.dayNumber(), true);
            int previousTime = TravelSchedule.start(day.dayNumber(), details), previousOrdinary = -1;
            boolean virtualStart = previous != null;
            for (int i = 0; i < day.places().size(); i++) {
                PlaceDraft place = day.places().get(i);
                int original = TravelSchedule.minute(place.visitTime());
                OptionalInt incoming = previous == null ? OptionalInt.empty() : session.minutes(previous, place);
                int ready = previous == null ? original : previousTime + (virtualStart ? 0 : dwell(previous, details))
                        + (incoming.isPresent() ? incoming.getAsInt() + (incoming.getAsInt() > 0 ? 10 : 0) : TravelSchedule.gap(details));
                if (place.fixedEventId() != null) {
                    if (previous != null && incoming.isPresent() && ready > original)
                        warnings.add(day.dayNumber()+"일차 고정 일정 사이 이동 시간이 부족합니다: "+place.name()+". 숙소·교통 시각을 조정해 주세요.");
                    out.add(place); previous = place; previousTime = original; virtualStart = false; continue;
                }
                int time = Math.max(original, ready);
                if (previousOrdinary >= 0) time = Math.max(time, previousOrdinary + 60 + TravelSchedule.gap(details));
                if (!DraftMealPlanner.scheduledMeal(place) || time != original) time = ((time + 4) / 5) * 5;
                Map<PlaceDraft, Integer> origin = Map.of(place, original);
                // Moving later must still respect meals/night, fixed commitments and daily bounds.
                while (time < 22 * 60 && (!TravelSchedule.allowed(day.dayNumber(), draft.days().size(), time, details, events) || !eligible.test(place,TravelSchedule.clock(time)))) time += 5;
                PlaceDraft nextFixed = null;
                for (int j = i + 1; j < day.places().size(); j++) if (day.places().get(j).fixedEventId() != null) {
                    nextFixed = day.places().get(j); break;
                }
                PlaceDraft end = nextFixed == null ? overnight(details, day.dayNumber(), false) : nextFixed;
                OptionalInt outgoing = end == null ? OptionalInt.empty() : session.minutes(place, end);
                int limit = nextFixed == null ? TravelSchedule.end(day.dayNumber(), draft.days().size(), details)
                        : TravelSchedule.minute(nextFixed.visitTime());
                boolean fits = time < 22 * 60 && eligible.test(place,TravelSchedule.clock(time)) && time + 60 <= limit && TripRouteOptimizer.fits(place, TravelSchedule.clock(time), origin)
                        && TravelSchedule.allowed(day.dayNumber(), draft.days().size(), time, details, events)
                        && ExtraRequestRules.mealTimeAllowed(place,TravelSchedule.clock(time),draft.effectiveRequest(),day.dayNumber(),draft.days().size())
                        && (outgoing.isEmpty() || time + 60 + outgoing.getAsInt() + (outgoing.getAsInt() > 0 ? 10 : 0) <= limit);
                if (!fits) {
                    warnings.add(day.dayNumber()+"일차 이동·고정 일정 시간을 확보하기 위해 제외한 장소: "+place.name()+". 여행 스타일 일부가 빠질 수 있습니다.");
                    continue;
                }
                PlaceDraft moved = TripRouteOptimizer.at(place, TravelSchedule.clock(time));
                out.add(moved); previous = moved; previousTime = time; previousOrdinary = time; virtualStart = false;
            }
            days.add(new DayDraft(day.dayNumber(), day.date(), List.copyOf(out)));
        }
        if (session.incomplete()) warnings.add("경로 조회 한도·응답 오류·좌표 누락으로 일부 구간의 실제 이동 시간을 확인하지 못했습니다. 해당 구간은 기존 이동 여유 시간을 적용했습니다.");
        if(details!=null && details.movementMode()==MovementMode.WALK) warnings.add("도보 이동 시간은 직선거리·거리 여유 1.3배·시속 4km 기준의 예상치이며 실제 보행 경로와 다를 수 있습니다.");
        else warnings.add("경로 조회에 성공한 구간에만 조회 시점의 예상 이동 시간을 반영합니다. 여행 당일 교통 정체·배차·막차·운행 여부는 달라질 수 있습니다.");
        TripDraft result = copy(draft, days, warnings);
        TravelSchedule.validateDraft(result);
        return result;
    }
    private static double routeCost(RouteTimeClient.Session session, PlaceDraft a, PlaceDraft b) {
        OptionalInt time = session.minutes(a, b);
        return time.isPresent() ? time.getAsInt() : Double.NaN;
    }
    private static int dwell(PlaceDraft p, TravelDetails d) {
        if (p.fixedEventId() == null) return 60;
        if (p.fixedEventId().equals("transport:arrival")) return d != null && d.transport() != null && d.transport().mode() == TransportMode.PLANE ? 60 : 30;
        return p.fixedEventId().startsWith("transport:") ? 0 : 15;
    }
    private static PlaceDraft overnight(TravelDetails d, int day, boolean start) {
        if (d != null && d.stays() != null) for (Stay s : d.stays())
            if ((start ? s.checkInDay() < day && s.checkOutDay() >= day : s.checkInDay() <= day && s.checkOutDay() > day)
                    && s.latitude() != null && s.longitude() != null) return TripRouteOptimizer.anchor(s);
        return null;
    }
    private static TripDraft copy(TripDraft d, List<DayDraft> days, List<String> warnings) {
        return new TripDraft(d.title(), d.destination(), d.startDate(), d.endDate(), d.budget(), d.styles(), days,
                d.source(), warnings.stream().distinct().toList(), d.request(), d.travelDetails()).withRequestMetadata(d.request(),d.interpretedRequest());
    }
}
