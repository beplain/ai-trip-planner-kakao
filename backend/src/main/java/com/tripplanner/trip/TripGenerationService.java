package com.tripplanner.trip;

import com.tripplanner.ai.AiItinerary;
import com.tripplanner.ai.AiItinerary.AiDay;
import com.tripplanner.ai.AiItinerary.AiPlace;
import com.tripplanner.ai.ItineraryRequest;
import com.tripplanner.ai.ItineraryRules;
import com.tripplanner.ai.ItineraryScheduler;
import com.tripplanner.common.ApiException;
import org.springframework.http.HttpStatus;
import com.tripplanner.ai.OpenAiItineraryClient;
import com.tripplanner.ai.SampleItineraryGenerator;
import com.tripplanner.place.Geo;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.place.KakaoLocalClient.Area;
import com.tripplanner.place.KakaoLocalClient.PlaceHit;
import com.tripplanner.trip.TripDtos.DayDraft;
import com.tripplanner.trip.TripDtos.GenerateRequest;
import com.tripplanner.trip.TripDtos.PlaceDraft;
import com.tripplanner.trip.TripDtos.TripDraft;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * 일정 초안 생성: AI(장소명·설명) → 카카오 장소 검색으로 검증(장소 id·좌표) → 초안 반환.
 * 이 메서드는 생성 결과만 반환한다. 호출자가 초안 DB에 신규 저장하거나 기존 초안을 교체한다.
 */
@Service
public class TripGenerationService {
    private static final Logger log = LoggerFactory.getLogger(TripGenerationService.class);
    /** 목적지 중심에서 이보다 먼 검색 결과는 동명이인 장소로 보고 제외 (직선거리) */
    private static final double MAX_DISTANCE_METERS = 60_000;

    private final OpenAiItineraryClient openAi;
    private final SampleItineraryGenerator sample;
    private final KakaoLocalClient places;
    private final RoutedItineraryService routedItinerary;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public TripGenerationService(OpenAiItineraryClient openAi, SampleItineraryGenerator sample, KakaoLocalClient places) {
        this(openAi, sample, places, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TripGenerationService(OpenAiItineraryClient openAi, SampleItineraryGenerator sample, KakaoLocalClient places, RoutedItineraryService routedItinerary) {
        this.openAi = openAi;
        this.sample = sample;
        this.places = places;
        this.routedItinerary = routedItinerary;
    }

    private GenerateRequest resolveTransport(GenerateRequest req) {
        return routedItinerary == null ? req : routedItinerary.resolveTransport(req);
    }

    private TripDraft route(TripDraft draft) {
        long started = System.nanoTime();
        try { return routedItinerary == null ? draft : routedItinerary.adjust(draft); }
        finally { log.info("일정 생성 단계=경로보정 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public TripDraft generate(GenerateRequest req) {
        req = resolveTransport(req);
        long started = System.nanoTime();
        try { return ExtraRequestRules.require(route(generateMeasured(req, new com.tripplanner.place.GenerationPlaces(places,ExtraRequestRules.mealSearches(req.request(),req.destination().trim(),req.days())), null))); }
        finally { log.info("일정 생성 단계=전체 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    }

    public TripDtos.DraftOptions generateOptions(GenerateRequest req) {
        req = resolveTransport(req);
        long started = System.nanoTime();
        try {
            Map<Integer,List<PlaceDraft>> candidates = new HashMap<>();
            TripDraft base = generateMeasured(req,new com.tripplanner.place.GenerationPlaces(places,ExtraRequestRules.mealSearches(req.request(),req.destination().trim(),req.days())),candidates);
            List<TripDraft> composed = DraftOptionComposer.compose(base,candidates);
            List<TripDraft> routed = routedItinerary == null ? composed : routedItinerary.adjustOptions(composed);
            String[] ids = {"compact","relaxed","varied"};
            String[] labels = {"이동 최소형","최소 방문형","다양한 방문형"};
            String[] descriptions = {"가까운 장소를 묶고 이동을 줄이는 구성", "방문 수를 줄여 여유를 두는 구성", "대체 후보를 활용해 다양한 방문을 우선하는 구성"};
            List<TripDtos.DraftOption> options = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (int i=0;i<routed.size();i++) {
                TripDraft draft = routed.get(i);
                if (!ExtraRequestRules.violations(draft).isEmpty()) continue;
                if (seen.add(DraftOptionComposer.fingerprint(draft))) options.add(new TripDtos.DraftOption(ids[i],labels[i],descriptions[i],draft));
            }
            if (options.isEmpty()) throw ApiException.badRequest("추가 요청을 충족하는 초안을 만들지 못했어요. 요청이나 여행 조건을 조정해 주세요.");
            List<String> warnings = options.size() < 3 ? List.of("확인된 장소와 여행 시간의 제약으로 서로 다른 초안을 "+options.size()+"개 준비했습니다.") : List.of();
            return new TripDtos.DraftOptions(options,warnings);
        } finally { log.info("일정 생성 단계=초안선택지전체 소요={}ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)); }
    }

    private TripDraft generateMeasured(GenerateRequest req, KakaoLocalClient lookups, Map<Integer,List<PlaceDraft>> candidatePool) {
        if (req.days() < 1 || req.days() > 7) throw ApiException.badRequest("여행 기간은 1~7일이어야 합니다.");
        if (req.styles() != null && !ItineraryRules.STYLES.containsAll(req.styles()))
            throw ApiException.badRequest("지원하지 않는 여행 스타일입니다.");
        ExtraRequestRules.validateInput(req.request(),req.days());
        TravelSchedule.validate(req.travelDetails(), req.days());
        if (!places.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_REQUIRED",
                "카카오 장소 검색을 사용할 수 없어 확인된 장소만으로 일정을 만들 수 없습니다. 서버 지도 키를 확인해 주세요.");
        String feedback = null;
        AiItinerary lastCandidate = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                TripDraft generated = generateOnce(req, feedback, lookups, candidatePool);
                var issues = ExtraRequestRules.violations(generated);
                if (!issues.isEmpty()) throw new InvalidItinerary(String.join("; ", issues));
                return generated;
            }
            catch (InvalidItinerary e) { feedback = e.getMessage(); if (e.candidate != null) lastCandidate = e.candidate; }
            catch (ApiException e) {
                if (!Set.of("AI_CONSTRAINTS", "AI_FAILED").contains(e.getCode())) throw e;
                feedback = e.getMessage(); break;
            }
            if (!openAi.enabled()) break;
        }
        long recoveryStarted = System.nanoTime();
        TripDraft recovered;
        try { recovered = new ConfirmedItineraryFallback(lookups, pool).generate(req, lastCandidate, openAi.enabled(), candidatePool); }
        finally { log.info("일정 생성 단계=대체후보구성 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStarted)); }
        return ExtraRequestRules.require(recovered);
    }

    private static class InvalidItinerary extends RuntimeException {
        final AiItinerary candidate;
        InvalidItinerary(String message) { this(message, null); }
        InvalidItinerary(String message, AiItinerary candidate) { super(message); this.candidate = candidate; }
    }

    private TripDraft generateOnce(GenerateRequest req, String feedback, KakaoLocalClient lookups, Map<Integer,List<PlaceDraft>> candidatePool) {
        List<String> warnings = new ArrayList<>();
        String destination = req.destination().trim();
        List<String> styles = req.styles() == null ? List.of() : req.styles();
        ItineraryRequest ir = new ItineraryRequest(destination, req.startDate(), req.days(), req.budget(), styles,
                req.request(), req.travelDetails());

        AiItinerary ai;
        String source;
        if (openAi.enabled()) {
            long aiStarted = System.nanoTime();
            AiItinerary raw;
            try { raw = openAi.generate(ir, feedback); }
            finally { log.info("일정 생성 단계=AI 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - aiStarted)); }
            try { ai = ItineraryScheduler.prepare(ExtraRequestRules.addMealCandidates(raw,ir), ir); }
            catch (ItineraryScheduler.SchedulingException e) { throw new InvalidItinerary(e.getMessage(), raw); }
            List<String> problems = ItineraryRules.validate(ai, ir);
            if (!problems.isEmpty()) throw new InvalidItinerary(String.join("; ", problems), ai);
            source = "AI";
        } else {
            try { ai = ItineraryScheduler.prepare(ExtraRequestRules.addMealCandidates(sample.generate(ir),ir), new ItineraryRequest(destination, req.startDate(), req.days(),
                    req.budget(), List.of(), req.request(), req.travelDetails())); }
            catch (ItineraryScheduler.SchedulingException e) { throw new InvalidItinerary(e.getMessage()); }
            source = "SAMPLE";
            warnings.add("서버에 OPENAI_API_KEY가 없어 샘플 일정을 만들었습니다.");
        }

        Map<String, List<PlaceHit>> hitsByQuery = Map.of();
        Area area = null;
        if (!places.enabled()) {
            warnings.add("서버에 KAKAO_REST_API_KEY가 없어 장소 확인과 지도 표시를 할 수 없습니다.");
        } else {
            long searchStarted = System.nanoTime();
            try {
                area = lookups.resolveArea(destination).orElse(null);
                if (area == null) warnings.add("목적지 위치를 찾지 못해 장소 확인 정확도가 낮을 수 있습니다.");
                hitsByQuery = searchAll(ai, destination, area, lookups);
            } catch (Exception e) {
                log.warn("장소 검증 단계 실패: {}", e.getMessage());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_UNAVAILABLE", "카카오 장소 검색에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            } finally {
                log.info("일정 생성 단계=장소검색 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStarted));
            }
        }

        var mealSearchResults=hitsByQuery;
        var reservedMeals=ExtraRequestRules.reserveMeals(req.request(),req.days(),m->mealSearchResults.getOrDefault(m.query(destination),List.of()));
        Set<String> used = new HashSet<>();
        Set<String> usedNames = new HashSet<>();
        List<AiDay> resolvedDays = new ArrayList<>();
        List<DayDraft> days = new ArrayList<>();
        List<AiDay> aiDays = new ArrayList<>(ai.days());
        aiDays.sort(Comparator.comparingInt(AiDay::dayNumber));
        for (int i = 0; i < aiDays.size(); i++) {
            AiDay d = aiDays.get(i);
            List<AiPlace> alternatives = new ArrayList<>(d.alternatives() == null ? List.of() : d.alternatives());
            List<PlaceDraft> out = new ArrayList<>();
            List<AiPlace> resolved = new ArrayList<>();
            for (AiPlace p : d.places()) {
                AiPlace chosen = p;
                PlaceDraft verified = pick(p, hitsByQuery, used, usedNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals);
                if (verified == null) {
                    for (Iterator<AiPlace> it = alternatives.iterator(); it.hasNext();) {
                        AiPlace candidate = ItineraryRules.atTime(it.next(), p.visitTime());
                        if (!Objects.equals(candidate.category(), p.category())) continue;
                        if (openAi.enabled() && !ItineraryRules.stylesFor(candidate).containsAll(ItineraryRules.stylesFor(p))) continue;
                        verified = pick(candidate, hitsByQuery, used, usedNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals);
                        if (verified != null) { chosen = candidate; it.remove(); break; }
                    }
                }
                if (verified == null) {
                    throw new InvalidItinerary(d.dayNumber() + "일차 '" + p.name() + "' 및 같은 스타일의 대체 후보를 카카오맵에서 확인하지 못했습니다. "
                            + "검색 가능한 다른 실제 장소와 대체 후보를 추천하세요.", ai);
                }
                out.add(verified);
                resolved.add(new AiPlace(verified.name(), chosen.searchQuery(), verified.visitTime(), chosen.category(),
                        chosen.reason(), chosen.note(), chosen.matchedStyles()));
            }
            if (candidatePool != null) {
                List<PlaceDraft> candidates = new ArrayList<>(out);
                Set<String> candidateIds = new HashSet<>(used), candidateNames = new HashSet<>(usedNames);
                for (AiPlace alternative : alternatives) {
                    PlaceDraft found = pick(alternative, hitsByQuery, candidateIds, candidateNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals);
                    if (found != null) candidates.add(found);
                }
                candidatePool.put(d.dayNumber(), List.copyOf(candidates));
            }
            resolvedDays.add(new AiDay(d.dayNumber(), resolved, List.of()));
            LocalDate date = req.startDate() == null ? null : req.startDate().plusDays(i);
            var events = TravelSchedule.events(req.travelDetails(), req.days());
            if (req.travelDetails() != null) {
                if (!openAi.enabled()) out.removeIf(p -> !TravelSchedule.allowed(d.dayNumber(), req.days(), TravelSchedule.minute(p.visitTime()), req.travelDetails(), events));
                final int n = d.dayNumber();
                events.stream().filter(e -> e.day() == n).map(TravelSchedule.Event::place).forEach(out::add);
                out.sort(Comparator.comparing(PlaceDraft::visitTime));
            }
            days.add(new DayDraft(i + 1, date, out));
        }

        if (openAi.enabled()) {
            List<String> problems = ItineraryRules.validate(new AiItinerary(ai.title(), resolvedDays), ir);
            if (!problems.isEmpty()) throw new InvalidItinerary(String.join("; ", problems), ai);
        }
        LocalDate end = req.startDate() == null ? null : req.startDate().plusDays(days.size() - 1L);
        String title = ai.title() == null || ai.title().isBlank() ? destination + " " + req.days() + "일 여행" : ai.title();
        if (title.length() > 100) title = title.substring(0, 100);
        if (req.travelDetails() != null) warnings.add("이동 시간은 예상 여유 시간 기준이며 실시간 교통을 조회하지 않습니다. 숙소·보관함 운영 조건을 확인해 주세요.");
        if (req.travelDetails() != null && req.days() > 1 && TravelSchedule.eveningStart(1, req.days(), req.travelDetails(),
                TravelSchedule.events(req.travelDetails(), req.days())) < 0)
            warnings.add("첫날 도착·체크인 시각이 늦어 고정 시간을 지키며 추가 저녁 활동을 배치할 수 없습니다.");
        var result = new TripDraft(title, destination, req.startDate(), end, req.budget(), styles, days, source,
                warnings.stream().distinct().toList(), req.request(), req.travelDetails());
        result = TripRouteOptimizer.optimize(result);
        try { TravelSchedule.validateDraft(result); } catch (ApiException e) { throw new InvalidItinerary(e.getMessage(), ai); }
        return result;
    }

    /** 모든 검색어를 병렬 조회 (중복 검색어는 1번만) */
    private Map<String, List<PlaceHit>> searchAll(AiItinerary ai, String destination, Area area, KakaoLocalClient lookups) {
        Set<String> queries = new LinkedHashSet<>();
        for (AiDay d : ai.days()) {
            d.places().forEach(p -> queries.add(query(p, destination)));
            if (d.alternatives() != null) d.alternatives().forEach(p -> queries.add(query(p, destination)));
        }
        Map<String, Future<List<PlaceHit>>> futures = new LinkedHashMap<>();
        for (String q : queries) futures.put(q, pool.submit(() -> lookups.search(q, area, 15)));
        Map<String, List<PlaceHit>> result = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        futures.forEach((q, f) -> {
            try {
                long remaining = deadline - System.nanoTime();
                if (!f.isDone() && remaining <= 0) throw new TimeoutException();
                result.put(q, f.get(Math.max(1, remaining), TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                f.cancel(true);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("장소 검색 응답을 제한 시간 내 받지 못했습니다.");
                result.put(q, List.of());
            }
        });
        return result;
    }

    private PlaceDraft pick(AiPlace p, Map<String, List<PlaceHit>> hitsByQuery, Set<String> used, Set<String> usedNames, String destination, String extraRequest, int day, int days, Map<String,Integer> reservedMeals) {
        String requestedName = ItineraryRules.nameKey(p.name(), destination);
        if (openAi.enabled() && usedNames.contains(requestedName)) return null;
        for (PlaceHit h : hitsByQuery.getOrDefault(query(p, destination), List.of())) {
            if (h.placeId() == null || h.placeId().isBlank() || h.name() == null || h.name().isBlank()
                    || !Double.isFinite(h.lat()) || !Double.isFinite(h.lng()) || Math.abs(h.lat()) > 90 || Math.abs(h.lng()) > 180) continue;
            if (reservedMeals.containsKey(h.placeId()) && reservedMeals.get(h.placeId())!=day) continue;
            if (!ExtraRequestRules.allows(extraRequest, h.name(), p.category())) continue;
            if (!ExtraRequestRules.allowsMealHit(extraRequest,day,days,p.name(),p.category(),p.reason()+" "+p.searchQuery(),h.name(),h.categoryName())) continue;
            String resolvedName = ItineraryRules.nameKey(h.name(), destination);
            if (used.contains(h.placeId()) || usedNames.contains(resolvedName)) continue;
            if (!com.tripplanner.place.PlaceCategories.matches(p.category(), h)) continue;
            if (h.distanceMeters() != null && h.distanceMeters() > MAX_DISTANCE_METERS) continue; // 목적지 밖
            used.add(h.placeId());
            if (openAi.enabled()) usedNames.add(requestedName);
            usedNames.add(resolvedName);
            return ExtraRequestRules.annotateMeal(new PlaceDraft(h.placeId(), h.name(), h.address(), h.lat(), h.lng(), p.visitTime(), p.reason(),
                    p.note(), p.category(), true, h.placeUrl(), h.phone(), null, h.categoryName()),extraRequest,day,days);
        }
        return null;
    }

    private static String query(AiPlace p, String destination) {
        String q = p.searchQuery();
        if (q == null || q.isBlank()) q = p.name() + " " + destination;
        return q.trim();
    }

    /** 테스트/디버그용: 두 장소 사이 직선거리 */
    static double distance(PlaceHit a, PlaceHit b) {
        return Geo.distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
    }
}
