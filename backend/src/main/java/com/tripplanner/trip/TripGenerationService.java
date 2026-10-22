package com.tripplanner.trip;

import com.tripplanner.ai.AiItinerary;
import com.tripplanner.ai.AiItinerary.AiDay;
import com.tripplanner.ai.AiItinerary.AiPlace;
import com.tripplanner.ai.ItineraryRequest;
import com.tripplanner.ai.ItineraryRules;
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
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public TripGenerationService(OpenAiItineraryClient openAi, SampleItineraryGenerator sample, KakaoLocalClient places) {
        this.openAi = openAi;
        this.sample = sample;
        this.places = places;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public TripDraft generate(GenerateRequest req) {
        if (req.days() < 1 || req.days() > 7) throw ApiException.badRequest("여행 기간은 1~7일이어야 합니다.");
        if (req.styles() != null && !ItineraryRules.STYLES.containsAll(req.styles()))
            throw ApiException.badRequest("지원하지 않는 여행 스타일입니다.");
        String feedback = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return generateOnce(req, feedback); }
            catch (InvalidItinerary e) { feedback = e.getMessage(); }
            if (!openAi.enabled()) break;
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_CONSTRAINTS",
                "매일의 여행 스타일과 장소 중복 조건을 만족하는 일정을 만들지 못했습니다. 목적지나 스타일을 조정하고 다시 시도해 주세요.");
    }

    private static class InvalidItinerary extends RuntimeException {
        InvalidItinerary(String message) { super(message); }
    }

    private TripDraft generateOnce(GenerateRequest req, String feedback) {
        List<String> warnings = new ArrayList<>();
        String destination = req.destination().trim();
        List<String> styles = req.styles() == null ? List.of() : req.styles();
        ItineraryRequest ir = new ItineraryRequest(destination, req.startDate(), req.days(), req.budget(), styles,
                req.request());

        AiItinerary ai;
        String source;
        if (openAi.enabled()) {
            ai = openAi.generate(ir, feedback);
            List<String> problems = ItineraryRules.validate(ai, ir);
            if (!problems.isEmpty()) throw new InvalidItinerary(String.join("; ", problems));
            source = "AI";
        } else {
            ai = sample.generate(ir);
            source = "SAMPLE";
            warnings.add("서버에 OPENAI_API_KEY가 없어 샘플 일정을 만들었습니다.");
        }

        Map<String, List<PlaceHit>> hitsByQuery = Map.of();
        Area area = null;
        if (!places.enabled()) {
            warnings.add("서버에 KAKAO_REST_API_KEY가 없어 장소 확인과 지도 표시를 할 수 없습니다.");
        } else {
            try {
                area = places.resolveArea(destination).orElse(null);
                if (area == null) warnings.add("목적지 위치를 찾지 못해 장소 확인 정확도가 낮을 수 있습니다.");
                hitsByQuery = searchAll(ai, destination, area);
            } catch (Exception e) {
                log.warn("장소 검증 단계 실패: {}", e.getMessage());
                warnings.add("장소 검색 서비스 오류로 일부 장소를 확인하지 못했습니다.");
            }
        }

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
                PlaceDraft verified = pick(p, hitsByQuery, used, usedNames, destination);
                if (verified == null) {
                    for (Iterator<AiPlace> it = alternatives.iterator(); it.hasNext();) {
                        AiPlace candidate = ItineraryRules.atTime(it.next(), p.visitTime());
                        if (!Objects.equals(candidate.category(), p.category())) continue;
                        if (openAi.enabled() && !ItineraryRules.stylesFor(candidate).containsAll(ItineraryRules.stylesFor(p))) continue;
                        verified = pick(candidate, hitsByQuery, used, usedNames, destination);
                        if (verified != null) { chosen = candidate; it.remove(); break; }
                    }
                }
                if (verified == null) {
                    // A rejected duplicate must not come back as an unverified recommendation.
                    if (openAi.enabled() && (!hitsByQuery.getOrDefault(query(p, destination), List.of()).isEmpty()
                            || !usedNames.add(ItineraryRules.nameKey(p.name(), destination))))
                        throw new InvalidItinerary(d.dayNumber() + "일차 중복 또는 부적합 장소: " + p.name());
                    verified = unverified(p);
                    warnings.add("'" + p.name() + "'은(는) 지도에서 확인되지 않았습니다. 저장 전 확인해 주세요.");
                } else if (chosen != p) {
                    warnings.add("'" + p.name() + "'을(를) 같은 스타일의 '" + verified.name() + "'(으)로 대체했습니다.");
                }
                out.add(verified);
                resolved.add(new AiPlace(verified.name(), chosen.searchQuery(), verified.visitTime(), chosen.category(),
                        chosen.reason(), chosen.note(), chosen.matchedStyles()));
            }
            resolvedDays.add(new AiDay(d.dayNumber(), resolved, List.of()));
            LocalDate date = req.startDate() == null ? null : req.startDate().plusDays(i);
            days.add(new DayDraft(i + 1, date, out));
        }

        if (openAi.enabled()) {
            List<String> problems = ItineraryRules.validate(new AiItinerary(ai.title(), resolvedDays), ir);
            if (!problems.isEmpty()) throw new InvalidItinerary(String.join("; ", problems));
        }
        LocalDate end = req.startDate() == null ? null : req.startDate().plusDays(days.size() - 1L);
        String title = ai.title() == null || ai.title().isBlank() ? destination + " " + req.days() + "일 여행" : ai.title();
        if (title.length() > 100) title = title.substring(0, 100);
        return new TripDraft(title, destination, req.startDate(), end, req.budget(), styles, days, source,
                warnings.stream().distinct().toList(), req.request());
    }

    /** 모든 검색어를 병렬 조회 (중복 검색어는 1번만) */
    private Map<String, List<PlaceHit>> searchAll(AiItinerary ai, String destination, Area area) {
        Set<String> queries = new LinkedHashSet<>();
        for (AiDay d : ai.days()) {
            d.places().forEach(p -> queries.add(query(p, destination)));
            if (d.alternatives() != null) d.alternatives().forEach(p -> queries.add(query(p, destination)));
        }
        Map<String, Future<List<PlaceHit>>> futures = new LinkedHashMap<>();
        for (String q : queries) futures.put(q, pool.submit(() -> places.search(q, area, 15)));
        Map<String, List<PlaceHit>> result = new HashMap<>();
        futures.forEach((q, f) -> {
            try {
                result.put(q, f.get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("검색 실패 '{}': {}", q, e.getMessage());
                result.put(q, List.of());
            }
        });
        return result;
    }

    private PlaceDraft pick(AiPlace p, Map<String, List<PlaceHit>> hitsByQuery, Set<String> used, Set<String> usedNames, String destination) {
        String requestedName = ItineraryRules.nameKey(p.name(), destination);
        if (openAi.enabled() && usedNames.contains(requestedName)) return null;
        for (PlaceHit h : hitsByQuery.getOrDefault(query(p, destination), List.of())) {
            String resolvedName = ItineraryRules.nameKey(h.name(), destination);
            if (used.contains(h.placeId()) || usedNames.contains(resolvedName)) continue;
            String group = Objects.toString(h.categoryGroup(), "");
            if ("식당".equals(p.category()) && !group.isBlank() && !"음식점".equals(group)) continue;
            if ("카페".equals(p.category()) && !group.isBlank() && !"카페".equals(group)) continue;
            if (!Set.of("식당", "카페").contains(p.category()) && Set.of("음식점", "카페").contains(group)) continue;
            if (h.distanceMeters() != null && h.distanceMeters() > MAX_DISTANCE_METERS) continue; // 목적지 밖
            used.add(h.placeId());
            if (openAi.enabled()) usedNames.add(requestedName);
            usedNames.add(resolvedName);
            return new PlaceDraft(h.placeId(), h.name(), h.address(), h.lat(), h.lng(), p.visitTime(), p.reason(),
                    p.note(), p.category(), true, h.placeUrl(), h.phone());
        }
        return null;
    }

    private static PlaceDraft unverified(AiPlace p) {
        return new PlaceDraft(null, p.name(), null, null, null, p.visitTime(), p.reason(), p.note(), p.category(),
                false, null, null);
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
