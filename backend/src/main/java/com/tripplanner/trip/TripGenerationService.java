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

    private final OpenAiItineraryClient openAi;
    private final SampleItineraryGenerator sample;
    private final KakaoLocalClient places;
    private final RoutedItineraryService routedItinerary;
    private BusinessHoursService businessHours;
    private GoogleHoursScheduler googleHours;
    private boolean scheduleMeals;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, task -> {
        Thread thread = new Thread(task, "itinerary-generation");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService earlyLookups=Executors.newFixedThreadPool(4,task->{
        var thread=new Thread(task,"generation-prefetch");thread.setDaemon(true);return thread;
    });
    // Search orchestration waits for tasks in pool, so it must run on a separate executor.
    private final ExecutorService restaurantSearches = Executors.newFixedThreadPool(2);

    public TripGenerationService(OpenAiItineraryClient openAi, SampleItineraryGenerator sample, KakaoLocalClient places) {
        this(openAi, sample, places, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TripGenerationService(OpenAiItineraryClient a,SampleItineraryGenerator b,KakaoLocalClient c,RoutedItineraryService d,BusinessHoursService hours,GoogleHoursScheduler google) {
        this(a,b,c,d,hours);this.googleHours=google;this.scheduleMeals=true;
    }
    public TripGenerationService(OpenAiItineraryClient a,SampleItineraryGenerator b,KakaoLocalClient c,RoutedItineraryService d,BusinessHoursService hours) {
        this(a,b,c,d);this.businessHours=hours;
    }
    public TripGenerationService(OpenAiItineraryClient openAi, SampleItineraryGenerator sample, KakaoLocalClient places, RoutedItineraryService routedItinerary) {
        this.openAi = openAi;
        this.sample = sample;
        this.places = places;
        this.routedItinerary = routedItinerary;
    }

    private GenerateRequest resolveTransport(GenerateRequest req) {
        return routedItinerary == null ? req : routedItinerary.resolveTransport(req);
    }

    private TripDraft checkHours(TripDraft draft) { return businessHours==null?draft:businessHours.check(draft); }

    private TripDraft route(TripDraft draft) {
        long started = System.nanoTime();
        try { return routedItinerary == null ? draft : routedItinerary.adjust(draft); }
        finally { log.info("일정 생성 단계=경로보정 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    }

    @PreDestroy
    void shutdown() {
        earlyLookups.shutdownNow();
        restaurantSearches.shutdownNow();
        pool.shutdownNow();
    }

    public TripDraft generate(GenerateRequest req) {
        validateFallbackInput(req);
        var checkpoint = new java.util.concurrent.atomic.AtomicReference<TripDraft>();
        try { return FinalRecommendations.fill(List.of(generateStrict(req, checkpoint::set)),openAi).get(0); }
        catch (RuntimeException failure) {
            log.warn("일정 조건 완화: 단일 초안 반환", failure);
            return FinalRecommendations.fill(List.of(BestEffortDrafts.create(req, checkpoint.get(), failure)),openAi).get(0);
        }
    }

    private TripDraft generateStrict(GenerateRequest req, java.util.function.Consumer<TripDraft> checkpoint) {
        if(googleHours!=null)googleHours.requireConfigured();
        Prepared prepared = prepare(req);
        req = prepared.request();
        String originalRequest = prepared.originalRequest();
        long started = System.nanoTime();
        try {
            Map<Integer,List<PlaceDraft>> candidates=new HashMap<>();
            var generated = generateWithRestaurants(req, prepared.lookups(), candidates, prepared.restaurants());
            TripDraft base = generated.draft().withRequestMetadata(originalRequest, prepared.interpretedRequest());
            checkpoint.accept(base);
            TripDraft result;
            var restaurants = generated.restaurants();
            var hours=googleHours==null?null:googleHours.session();
            if(hours!=null)hours.requireRequestedPlacesOpen(base,candidates);
            if (scheduleMeals) {
                if (hours != null) hours.prefetchPlaces(DraftMealPlanner.likelyLookups(base, candidates, restaurants, 0));
                base = scheduleMeals(base, candidates, restaurants, hours, 0);
            }
            if(hours!=null) {
                hours.prefetch(List.of(base));
                var repaired = hours.repair(base, candidates);
                var routed = routedItinerary == null ? repaired : routedItinerary.adjustOptions(List.of(repaired), d -> (p, time) -> hours.fits(d, p, time)).get(0);
                result = hours.verify(routed);
            } else result=checkHours(route(base));
            checkpoint.accept(result);
            if (scheduleMeals) DraftMealPlanner.require(result);
            return ExtraRequestRules.require(result);
        }
        finally { log.info("일정 생성 단계=전체 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    }

    public TripDtos.DraftOptions generateOptions(GenerateRequest req) {
        long started=System.nanoTime();
        validateFallbackInput(req);
        var checkpoint = new java.util.concurrent.atomic.AtomicReference<TripDraft>();
        try { return describeOptions(generateOptionsMeasured(req, checkpoint::set)); }
        catch (RuntimeException failure) {
            log.warn("일정 조건 완화: 선택 가능한 초안 반환", failure);
            return describeOptions(BestEffortDrafts.options(req, checkpoint.get(), failure));
        }
        finally { logStage("요청전체",started); }
    }
    private TripDtos.DraftOptions describeOptions(TripDtos.DraftOptions result) {
        var filled=FinalRecommendations.fill(result.options().stream().map(TripDtos.DraftOption::draft).toList(),openAi);
        var options=new ArrayList<TripDtos.DraftOption>();
        for(int i=0;i<filled.size();i++) {
            var option=result.options().get(i);
            options.add(new TripDtos.DraftOption(option.id(),option.label(),option.description(),filled.get(i)));
        }
        return new TripDtos.DraftOptions(List.copyOf(options),result.warnings());
    }
    private static void logStage(String stage,long started) {
        log.info("일정 생성 단계={} 소요={}ms",stage,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    private TripDtos.DraftOptions generateOptionsMeasured(GenerateRequest req, java.util.function.Consumer<TripDraft> checkpoint) {
        if(googleHours!=null)googleHours.requireConfigured();
        Prepared prepared = prepare(req);
        req = prepared.request();
        String originalRequest = prepared.originalRequest();
        long started = System.nanoTime();
        GoogleHoursScheduler.Session googleSession=googleHours==null?null:googleHours.session();
        Future<?> warmHours=startEarlyHours(prepared,googleSession);
        Future<List<TripDraft>> restaurantDescriptions=startRestaurantDescriptions(prepared);
        Future<List<TripDraft>> descriptionWarmup=null;
        try {
            Map<Integer,List<PlaceDraft>> candidates = new HashMap<>();
            var generated = generateWithRestaurants(req, prepared.lookups(), candidates, prepared.restaurants());
            TripDraft base = generated.draft().withRequestMetadata(originalRequest, prepared.interpretedRequest());
            checkpoint.accept(base);
            long composeStarted=System.nanoTime();
            if(googleSession!=null)googleSession.beginScheduling();
            if(googleSession!=null)googleSession.requireRequestedPlacesOpen(base,candidates);
            List<TripDraft> composed;
            try {composed = composeOptions(base,candidates,req,prepared.lookups());}
            finally {logStage("유형구성",composeStarted);}
            List<PlaceDraft> restaurants = generated.restaurants();
            if (scheduleMeals) composed = scheduleOptionMeals(base, composed, candidates, restaurants, googleSession);
            var descriptionSeeds=List.copyOf(composed);
            descriptionWarmup=earlyLookups.submit(()->FinalRecommendations.fill(FinalRecommendations.reuseDescriptions(descriptionSeeds,restaurantDescriptions==null?List.of():join(restaurantDescriptions,"식당 추천 문구 선조회 중단")),openAi));
            if(googleSession!=null) {
                long hoursStarted=System.nanoTime();
                try {
                    googleSession.prefetch(composed);
                    List<Callable<TripDraft>> repairs=new ArrayList<>();
                    for(TripDraft draft:composed) repairs.add(()->{Thread.interrupted();return googleSession.repair(draft,candidates);});
                    List<TripDraft> repaired=new ArrayList<>();
                    for(var job:pool.invokeAll(repairs)) repaired.add(join(job,"운영시간 재배치가 중단됐습니다. 다시 시도해 주세요."));
                    composed=repaired;
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw ApiException.badRequest("운영시간 재배치가 중단됐습니다. 다시 시도해 주세요.");
                } finally {logStage("운영시간조회및재배치",hoursStarted);}
            }
            long routeStarted=System.nanoTime();
            List<TripDraft> routed;
            RouteTimeClient.Session routeSession=null;
            try {
                if(routedItinerary==null) routed=composed;
                else {
                    routeSession=routedItinerary.prefetch(composed);
                    routed=googleSession==null?routedItinerary.adjustOptions(composed,d->(p,time)->true,routeSession)
                        :routedItinerary.adjustOptions(composed,d->(p,time)->googleSession.fits(d,p,time),routeSession);
                }
            }
            finally {logStage("이동시간보정",routeStarted);}
            if (!routed.isEmpty()) checkpoint.accept(routed.get(0));
            if (scheduleMeals) {
                List<TripDraft> counted = new ArrayList<>();
                for (int profile = 0; profile < routed.size(); profile++) counted.add(fitProfile(routed.get(profile), candidates, googleSession, routeSession, profile));
                routed = counted;
            }
            String[] ids = {"compact","relaxed","varied"};
            String[] labels = {"이동 최소형","최소 방문형","다양한 방문형"};
            String[] descriptions = {"가까운 장소를 묶고 이동을 줄이는 구성", "방문 수를 줄여 여유를 두는 구성", "대체 후보를 활용해 다양한 방문을 우선하는 구성"};
            List<TripDtos.DraftOption> options = new ArrayList<>(); Set<String> seen = new HashSet<>();
            List<TripDraft> verified=new ArrayList<>();
            Map<Integer,String> failureReasons = new java.util.concurrent.ConcurrentHashMap<>();
            final var verificationRoutes=routeSession;
            try {
                List<Callable<TripDraft>> checks=new ArrayList<>();
                for (int index = 0; index < routed.size(); index++) {
                    int profile = index;
                    TripDraft candidate = routed.get(index);
                    checks.add(() -> { Thread.interrupted(); return verifiedOption(candidate, googleSession, profile, failureReasons,verificationRoutes); });
                }
                for(var job:pool.invokeAll(checks)) verified.add(join(job,"여행 유형 검증이 중단됐습니다. 다시 시도해 주세요."));
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.badRequest("여행 유형 검증이 중단됐습니다. 다시 시도해 주세요.");
            }
            for (int i=0;i<routed.size();i++) {
                TripDraft draft = verified.get(i);
                if(draft==null || seen.contains(scheduleMeals ? DraftVisitPlanner.fingerprint(draft) : DraftOptionComposer.fingerprint(draft))) {
                    TripDraft anchor=draft!=null?draft:routed.get(i);
                    log.info("여행 유형 재구성: 유형={} 사유={}",ids[i],draft==null?"최종검증 실패":"다른 유형과 장소·순서 중복");
                    long recoveryStarted=System.nanoTime();
                    final int profile=i;
                    String initialReason=failureReasons.get(i);
                    boolean duplicate=draft!=null;
                    draft=null;
                    try {
                        TripDraft candidate=recoveryCandidate(anchor,candidates,i,initialReason,duplicate);
                        if(candidate!=null) {
                            if(scheduleMeals) candidate=scheduleMeals(candidate,candidates,restaurants,googleSession,i+1,i);
                            var recoveryRoutes=routeSession==null?null:routeSession.share();
                            if(routedItinerary!=null) {
                                var one=List.of(candidate);
                                candidate=(googleSession==null?routedItinerary.adjustOptions(one,d->(p,time)->true,recoveryRoutes)
                                    :routedItinerary.adjustOptions(one,d->(p,time)->googleSession.fits(d,p,time),recoveryRoutes)).get(0);
                            }
                            if(recoveryRoutes!=null)routeSession=recoveryRoutes;
                            var fitted=scheduleMeals?fitProfile(candidate,candidates,googleSession,routeSession,i):candidate;
                            final var recoveryVerificationRoutes=routeSession;
                            var checked=verifyChangedOption(anchor,fitted,d->verifiedOption(d,googleSession,profile,failureReasons,recoveryVerificationRoutes));
                            if(checked!=null&&!seen.contains(scheduleMeals?DraftVisitPlanner.fingerprint(checked):DraftOptionComposer.fingerprint(checked)))draft=checked;
                            if(fitted.equals(anchor))log.info("여행 유형 보완 생략: 유형={} 사유=동일 일정 재검사 방지",ids[i]);
                        }
                    } catch(ApiException | IllegalArgumentException repairFailure) {
                        failureReasons.put(i,repairFailure.getMessage());
                    } finally {
                        log.info("여행 유형 보완 결과: 유형={} 결과={} 최초사유={} 최종사유={} 소요={}ms",ids[i],draft==null?"가능한 초안 반환":"성공",
                            initialReason,failureReasons.get(i),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-recoveryStarted));
                    }

                }
                if(draft==null) draft=BestEffortDrafts.create(req,routed.get(i),ApiException.badRequest(labels[i]+": "+failureReasons.getOrDefault(i,"유형별 차이를 확보하지 못했습니다.")));
                seen.add(scheduleMeals ? DraftVisitPlanner.fingerprint(draft) : DraftOptionComposer.fingerprint(draft));
                options.add(new TripDtos.DraftOption(ids[i],labels[i],descriptions[i],draft));
            }
            if(options.size()!=3)throw ApiException.badRequest("세 가지 여행 유형을 모두 준비하지 못했습니다. 다시 생성해 주세요.");
            long descriptionStarted=System.nanoTime();
            var described=FinalRecommendations.reuseDescriptions(options.stream().map(TripDtos.DraftOption::draft).toList(),join(descriptionWarmup,"추천 문구 준비가 중단됐습니다."));
            for(int i=0;i<options.size();i++) {
                var option=options.get(i);options.set(i,new TripDtos.DraftOption(option.id(),option.label(),option.description(),described.get(i)));
            }
            logStage("병렬추천문구잔여대기",descriptionStarted);
            return new TripDtos.DraftOptions(options,List.of());
        } finally { if(restaurantDescriptions!=null&&!restaurantDescriptions.isDone())restaurantDescriptions.cancel(true); if(descriptionWarmup!=null&&!descriptionWarmup.isDone())descriptionWarmup.cancel(true); if(warmHours!=null&&!warmHours.isDone())warmHours.cancel(true); log.info("일정 생성 단계=초안선택지전체 소요={}ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)); }
    }

    private List<TripDraft> composeOptions(TripDraft base, Map<Integer,List<PlaceDraft>> candidates, GenerateRequest request, KakaoLocalClient lookups) {
        if (scheduleMeals) {
            var area = lookups.resolveArea(request.destination()).orElse(null);
            var regions = new LinkedHashSet<String>();
            regions.add(request.destination());
            if (base.travelDetails() != null && base.travelDetails().stays() != null) base.travelDetails().stays().stream()
                    .map(stay -> DraftMealPlanner.mealSearchRegion(request.destination(), stay.address()))
                    .distinct().limit(2).forEach(regions::add);
            for (String category : List.of("자연", "카페", "문화", "체험", "쇼핑")) {
                String term = themeTerm(category);
                var terms = category.equals("체험") && base.styles() != null && base.styles().contains("체험")
                        ? List.of(term, "놀이공원", "스포츠 체험") : List.of(term);
                for (String searchTerm : terms) {
                if (!searchTerm.equals(term) && candidates.values().stream().flatMap(List::stream).anyMatch(p -> "체험".equals(p.category()) && p.verified())) break;
                for (String region : regions) for (var hit : lookups.search(region + " " + searchTerm, area, 15)) {
                    if (!com.tripplanner.place.PlaceCategories.matches(category, hit)
                            || !ExtraRequestRules.allows(request.request(), hit.name(), category,hit.placeId())) continue;
                    var place = new PlaceDraft(hit.placeId(), hit.name(), hit.address(), hit.lat(), hit.lng(), "14:00",
                            "", "", category, true, hit.placeUrl(), hit.phone(), null, hit.categoryName());
                    for (var day : base.days()) {
                        var merged = new LinkedHashMap<String,PlaceDraft>();
                        candidates.getOrDefault(day.dayNumber(), List.of()).forEach(p -> merged.put(p.placeId(),p));
                        merged.putIfAbsent(place.placeId(),place);
                        candidates.put(day.dayNumber(),List.copyOf(merged.values()));
                    }
                }
                }
            }
        }
        try {
            var initial=DraftOptionComposer.compose(base,candidates);
            if(initial.stream().map(DraftOptionComposer::fingerprint).distinct().count()==3)return initial;
        } catch(IllegalArgumentException | ApiException ignored) {}
        Map<Integer,List<PlaceDraft>> expanded=new HashMap<>();
        new ConfirmedItineraryFallback(lookups,pool).generate(request,null,openAi.enabled(),expanded);
        expanded.forEach((day,visits)->{
            Map<String,PlaceDraft> merged=new LinkedHashMap<>();
            candidates.getOrDefault(day,List.of()).forEach(p->merged.put(p.placeId(),p));
            visits.forEach(p->merged.putIfAbsent(p.placeId(),p));
            candidates.put(day,List.copyOf(merged.values()));
        });
        try {return DraftOptionComposer.compose(base,candidates);}
        catch(IllegalArgumentException | ApiException e){throw ApiException.badRequest("세 가지 여행 유형을 구성할 장소를 준비하지 못했습니다. 다시 생성해 주세요.");}
    }

    private record GeneratedDraft(TripDraft draft, List<PlaceDraft> restaurants) {}

    private GeneratedDraft generateWithRestaurants(GenerateRequest request, KakaoLocalClient lookups,
                                                    Map<Integer, List<PlaceDraft>> candidates, Future<List<PlaceDraft>> restaurants) {
        validateGenerationRequest(request);
        try {
            TripDraft draft = generateMeasured(request, lookups, candidates);
            if (!scheduleMeals) return new GeneratedDraft(draft, List.of());
            long waitStarted = System.nanoTime();
            try { return new GeneratedDraft(draft, join(restaurants, "식당 후보 조회가 중단됐습니다. 다시 시도해 주세요.")); }
            finally { logStage("식당후보대기", waitStarted); }
        } finally {
            if (!restaurants.isDone()) restaurants.cancel(true);
        }
    }

    private Prepared prepare(GenerateRequest req) {
        String original = req.request();
        String destination = req.destination().trim();
        Future<GenerateRequest> transported = restaurantSearches.submit(() -> {
            long stageStarted = System.nanoTime();
            try { return resolveTransport(req); }
            finally { logStage("교통편확인", stageStarted); }
        });
        var sharedPlaces = new com.tripplanner.place.GenerationPlaces(places, Map.of(), destination);
        Future<List<PlaceDraft>> restaurants = scheduleMeals
                ? restaurantSearches.submit(() -> {
                    long stageStarted = System.nanoTime();
                    try { return DraftMealPlanner.search(destination, req.days(), req.travelDetails(), sharedPlaces, pool); }
                    finally { logStage("식당후보검색", stageStarted); }
                })
                : CompletableFuture.completedFuture(List.of());
        try {
            long interpretationStarted = System.nanoTime();
            String interpreted;
            try { interpreted = openAi.interpretRequest(original, req.days()); }
            finally { logStage("추가요청해석", interpretationStarted); }
            GenerateRequest resolved = join(transported, "교통편 확인이 중단됐습니다. 다시 시도해 주세요.");
            GenerateRequest ready = new GenerateRequest(resolved.destination(), resolved.startDate(), resolved.days(),
                    resolved.budget(), resolved.styles(), interpreted, resolved.travelDetails());
            var lookups = sharedPlaces.withExpansions(ExtraRequestRules.mealSearches(ready.request(), destination, ready.days()));
            return new Prepared(ready, original, interpreted, lookups, restaurants);
        } catch (RuntimeException failure) {
            transported.cancel(true);
            restaurants.cancel(true);
            throw failure;
        }
    }

    private static <T> T join(Future<T> future, String interrupted) {
        try { return future.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badRequest(interrupted);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(interrupted, e.getCause());
        }
    }

    private Future<List<TripDraft>> startRestaurantDescriptions(Prepared prepared) {
        if(!scheduleMeals||!openAi.enabled())return null;
        return earlyLookups.submit(()->{
            var restaurants=join(prepared.restaurants(),"식당 문구 후보 준비 중단");
            var selected=DraftMealPlanner.earlyLookups(prepared.request(),restaurants);
            var req=prepared.request();
            var seed=new TripDraft(req.destination(),req.destination(),req.startDate(),req.startDate(),req.budget(),req.styles(),
                List.of(new DayDraft(1,req.startDate(),selected)),"DESCRIPTION_PREFETCH",List.of());
            return FinalRecommendations.fill(List.of(seed),openAi);
        });
    }

    private Future<?> startEarlyHours(Prepared prepared,GoogleHoursScheduler.Session hours) {
        if(!scheduleMeals||hours==null)return null;
        return earlyLookups.submit(()->{
            long started=System.nanoTime();
            try {
                var restaurants=join(prepared.restaurants(),"식당 후보 선조회가 중단됐습니다.");
                hours.prefetchPlaces(DraftMealPlanner.earlyLookups(prepared.request(),restaurants));
            } catch(RuntimeException failure) {log.info("영업시간 병렬 선조회 미완료: 유형={}",failure.getClass().getSimpleName());}
            finally {logStage("AI생성중영업시간선조회",started);}
        });
    }

    private record Prepared(GenerateRequest request, String originalRequest, String interpretedRequest,
                            KakaoLocalClient lookups, Future<List<PlaceDraft>> restaurants) {}

    private List<TripDraft> scheduleOptionMeals(TripDraft base, List<TripDraft> options,
                                                Map<Integer, List<PlaceDraft>> candidates, List<PlaceDraft> restaurants,
                                                GoogleHoursScheduler.Session hours) {
        long started = System.nanoTime();
        if (hours != null) {
            Map<String, PlaceDraft> warm = new LinkedHashMap<>();
            for (int i = 0; i < options.size() && warm.size() < 36; i++) {
                for (PlaceDraft place : DraftMealPlanner.likelyLookups(options.get(i), candidates, restaurants, i)) {
                    warm.putIfAbsent(place.placeId(), place);
                    if (warm.size() >= 36) break;
                }
            }
            long warmStarted = System.nanoTime();
            try { hours.prefetchPlaces(warm.values()); }
            finally { logStage("영업시간선조회", warmStarted); }
        }
        List<Callable<TripDraft>> jobs = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            int variant = i;
            TripDraft option = options.get(i);
            jobs.add(() -> {
                try {
                    return scheduleMeals(option, candidates, restaurants, hours, variant);
                } catch (ApiException e) {

                    log.info("여행 유형 식사 보완 보류: 유형={} 이유={}", variant, e.getMessage());
                    // Keep the seed; the final profile stage owns the single repair attempt.
                    return option;
                }
            });
        }
        try {
            List<TripDraft> prepared = new ArrayList<>();
            for (var job : pool.invokeAll(jobs)) prepared.add(job.get());
            return prepared;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badRequest("식사 배치가 중단됐습니다. 다시 시도해 주세요.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("식사 배치 실패", e.getCause());
        } finally {
            logStage("식사배치", started);
        }
    }

    private TripDraft scheduleMeals(TripDraft draft, Map<Integer, List<PlaceDraft>> candidates, List<PlaceDraft> restaurants,
                                    GoogleHoursScheduler.Session hours, int variant) {
        return scheduleMeals(draft, candidates, restaurants, hours, variant, Math.floorMod(variant, 3));
    }

    private TripDraft scheduleMeals(TripDraft draft, Map<Integer, List<PlaceDraft>> candidates, List<PlaceDraft> restaurants,
                                    GoogleHoursScheduler.Session hours, int variant, int profile) {
        TripDraft planned = DraftMealPlanner.plan(draft, candidates, restaurants,
                (day, place) -> hours == null || hours.canVisit(draft, day, place),
                places -> { if (hours != null) hours.prefetchMealCandidates(places); }, variant);
        return DraftVisitPlanner.durations(planned, profile);
    }

    // reachable adds the ten-minute arrival buffer separately.
    static int visitFallbackTravelMinutes(TripDtos.TravelDetails details) {
        return Math.max(0, TravelSchedule.gap(details) - 10);
    }

    private TripDraft fitProfile(TripDraft draft, Map<Integer, List<PlaceDraft>> candidates,
                                GoogleHoursScheduler.Session hours, RouteTimeClient.Session session, int profile) {
        return DraftVisitPlanner.fit(draft, candidates, profile,
                (day, place) -> hours == null || hours.canVisit(draft, day, place),
                (a, b) -> session == null ? visitFallbackTravelMinutes(draft.travelDetails())
                        : session.minutes(a, b).orElse(visitFallbackTravelMinutes(draft.travelDetails())));
    }

    // Only one repair pipeline runs per profile. Select it according to the failed constraint.
    static TripDraft recoveryCandidate(TripDraft anchor,Map<Integer,List<PlaceDraft>> candidates,int profile,String reason,boolean duplicate) {
        if(duplicate) {
            try {return DraftOptionComposer.fallback(anchor,profile==1);}
            catch(IllegalArgumentException | ApiException ignored){return null;}
        }
        if(reason!=null&&(reason.contains("스타일")||reason.contains("추가 요청")||reason.contains("필수 장소"))) {
            return DraftOptionComposer.recoveryCandidates(anchor,candidates).stream().filter(d->!d.equals(anchor)).findFirst().orElse(anchor);
        }
        // Meal slots, visit counts and travel conflicts are repaired on the failed schedule itself.
        return anchor;
    }

    static TripDraft verifyChangedOption(TripDraft original,TripDraft repaired,java.util.function.UnaryOperator<TripDraft> verify) {
        return original.equals(repaired)?null:verify.apply(repaired);
    }

    private TripDraft verifiedOption(TripDraft candidate,GoogleHoursScheduler.Session hours,int profile,Map<Integer,String> failureReasons,RouteTimeClient.Session routes) {
        TripDraft result=hours==null?checkHours(candidate):hours.verifyOption(candidate);
        if(result==null){failureReasons.put(profile,"일반 방문 장소를 확보하지 못했습니다.");log.info("여행 유형 검증 탈락: 일반 방문 없음");return null;}
        if(scheduleMeals) {
            final var seed=result;
            result=DraftVisitPlanner.trimExcess(result,profile,(day,p)->hours==null||hours.canVisit(seed,day,p),(a,b)->{
                if(routes==null)return visitFallbackTravelMinutes(seed.travelDetails());
                synchronized(routes){return routes.minutes(a,b).orElse(visitFallbackTravelMinutes(seed.travelDetails()));}
            });
            if(hours!=null&&!result.equals(seed))result=hours.verifyOption(result);
            if(result==null){failureReasons.put(profile,"일반 방문 장소를 확보하지 못했습니다.");return null;}
        }
        var issues=ExtraRequestRules.violations(result);
        if(!issues.isEmpty()){failureReasons.put(profile,String.join(" ",issues));log.info("여행 유형 검증 탈락: 추가 요청 미충족={}",issues);return null;}
        try {if (scheduleMeals) { DraftMealPlanner.require(result); DraftVisitPlanner.require(result, profile); }DraftOptionComposer.requireCoverage(result);TravelSchedule.validateDraft(result);failureReasons.remove(profile);return result;}
        catch(IllegalArgumentException | ApiException e){failureReasons.put(profile,e.getMessage().split(" \\[",2)[0]);log.info("여행 유형 검증 탈락: 이유={}",e.getMessage());return null;}
    }

    private static void validateFallbackInput(GenerateRequest req) {
        if(req==null || req.destination()==null || req.destination().isBlank() || req.days()==null || req.days()<1 || req.days()>7)
            throw ApiException.badRequest("목적지와 1~7일의 여행 기간을 입력해 주세요.");
        TravelSchedule.validate(req.travelDetails(),req.days());
    }

    private void validateGenerationRequest(GenerateRequest req) {
        if (req.days() < 1 || req.days() > 7) throw ApiException.badRequest("여행 기간은 1~7일이어야 합니다.");
        if (req.styles() != null && !ItineraryRules.STYLES.containsAll(req.styles()))
            throw ApiException.badRequest("지원하지 않는 여행 스타일입니다.");
        ExtraRequestRules.validateInput(req.request(),req.days());
        TravelSchedule.validate(req.travelDetails(), req.days());
        if (!places.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_REQUIRED",
                "카카오 장소 검색을 사용할 수 없어 확인된 장소만으로 일정을 만들 수 없습니다. 서버 지도 키를 확인해 주세요.");
    }

    private TripDraft generateMeasured(GenerateRequest req, KakaoLocalClient lookups, Map<Integer,List<PlaceDraft>> candidatePool) {
        AiItinerary lastCandidate=null;
        try {
            TripDraft generated=generateOnce(req,null,lookups,candidatePool);
            var issues=ExtraRequestRules.violations(generated);
            if(!issues.isEmpty())throw new InvalidItinerary(String.join("; ",issues));
            return generated;
        } catch(InvalidItinerary e) {
            lastCandidate=e.candidate;
            log.info("AI 결과 보완 필요: 시도=1 이유={}",e.getMessage());
        } catch(ApiException e) {
            if(!Set.of("AI_CONSTRAINTS","AI_FAILED").contains(e.getCode()))throw e;
            log.info("AI 결과 보완 필요: 시도=1 이유={}",e.getMessage());
        }
        long recoveryStarted=System.nanoTime();
        try {
            TripDraft recovered=new ConfirmedItineraryFallback(lookups,pool).generate(req,lastCandidate,openAi.enabled(),candidatePool);
            try {
                ExtraRequestRules.require(recovered);
                log.info("AI 초안 보완 결과: 결과=성공 AI재요청=없음");
                return recovered;
            } catch(ApiException | IllegalArgumentException failure) {
                log.info("AI 초안 보완 결과: 결과=가능한 초안 반환 AI재요청=없음 이유={}",failure.getMessage());
                return BestEffortDrafts.create(req,recovered,ApiException.badRequest(failure.getMessage()));
            }
        } finally {logStage("초안1회보완",recoveryStarted);}
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
                req.request(), req.travelDetails(), scheduleMeals);

        AiItinerary ai;
        String source;
        if (openAi.enabled()) {
            prefetchThemePlaces(req, lookups);
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
                hitsByQuery = searchAll(ai, destination, area, lookups, req.travelDetails());
            } catch (Exception e) {
                log.warn("장소 검증 단계 실패: {}", e.getMessage());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_UNAVAILABLE", "카카오 장소 검색에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            } finally {
                log.info("일정 생성 단계=장소검색 소요={}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStarted));
            }
        }

        var mealSearchResults=hitsByQuery;
        var reservedMeals=ExtraRequestRules.reserveMeals(req.request(),req.days(),m->mealSearchResults.getOrDefault(searchKey(m.day(),m.query(destination)),List.of()));
        Set<String> used = new HashSet<>();
        Set<String> usedNames = new HashSet<>();
        List<ExtraRequestRules.Visit> requestVisits = new ArrayList<>();
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
                PlaceDraft verified = pick(p, hitsByQuery, used, usedNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals, requestVisits);
                if (verified == null) {
                    for (Iterator<AiPlace> it = alternatives.iterator(); it.hasNext();) {
                        AiPlace candidate = ItineraryRules.atTime(it.next(), p.visitTime());
                        if (!Objects.equals(candidate.category(), p.category())) continue;
                        if (openAi.enabled() && !ItineraryRules.stylesFor(candidate).containsAll(ItineraryRules.stylesFor(p))) continue;
                        verified = pick(candidate, hitsByQuery, used, usedNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals, requestVisits);
                        if (verified != null) { chosen = candidate; it.remove(); break; }
                    }
                }
                if (verified == null) {
                    throw new InvalidItinerary(d.dayNumber() + "일차 '" + p.name() + "' 및 같은 스타일의 대체 후보를 카카오맵에서 확인하지 못했습니다. "
                            + "검색 가능한 다른 실제 장소와 대체 후보를 추천하세요.", ai);
                }
                out.add(verified);
                requestVisits.add(new ExtraRequestRules.Visit(d.dayNumber(),verified.name(),verified.category(),verified.categoryPath(),verified.placeId()));
                resolved.add(new AiPlace(verified.name(), chosen.searchQuery(), verified.visitTime(), chosen.category(),
                        chosen.reason(), chosen.note(), chosen.matchedStyles()));
            }
            if (candidatePool != null) {
                List<PlaceDraft> candidates = new ArrayList<>(out);
                Set<String> candidateIds = new HashSet<>(used), candidateNames = new HashSet<>(usedNames);
                for (AiPlace alternative : alternatives) {
                    PlaceDraft found = pick(alternative, hitsByQuery, candidateIds, candidateNames, destination, req.request(), d.dayNumber(), req.days(), reservedMeals, requestVisits);
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

    /** Style searches overlap the AI call and fill the same request cache used by later verification. */
    private void prefetchThemePlaces(GenerateRequest req, KakaoLocalClient lookups) {
        if (!places.enabled()) return;
        String destination = req.destination().trim();
        List<String> terms = new ArrayList<>();
        if (req.styles() != null) for (String style : req.styles()) terms.add(themeTerm(style));
        terms.add("맛집");
        terms.add("카페");
        terms.add("관광명소");
        for (String term : terms.stream().distinct().toList()) {
            pool.submit(() -> {
                try { lookups.search(destination + " " + term, lookups.resolveArea(destination).orElse(null), 15); }
                catch (RuntimeException ignored) { /* The later verified search reports a hard failure if maps are unavailable. */ }
            });
        }
    }

    private static String themeTerm(String style) {
        return switch (style) {
            case "자연", "힐링" -> "공원";
            case "문화" -> "박물관";
            case "체험" -> "체험";
            case "관광" -> "관광명소";
            default -> style;
        };
    }

    /** 모든 검색어를 병렬 조회 (중복 검색어는 1번만) */
    private Map<String, List<PlaceHit>> searchAll(AiItinerary ai, String destination, Area area, KakaoLocalClient lookups, TripDtos.TravelDetails details) {
        Set<String> queries = new LinkedHashSet<>();
        Map<String,Area> anchors = new HashMap<>();
        for (AiDay d : ai.days()) {
            Area anchor=area;
            if(details!=null && details.stays()!=null) for(var stay:details.stays())
                if(stay.checkInDay()<=d.dayNumber() && stay.checkOutDay()>=d.dayNumber() && stay.latitude()!=null && stay.longitude()!=null) {
                    anchor=new Area(stay.latitude(),stay.longitude(),stay.name()); break;
                }
            final Area center=anchor;
            d.places().forEach(p -> { String key=searchKey(d.dayNumber(),query(p,destination)); queries.add(key); anchors.put(key,center); });
            if (d.alternatives() != null) d.alternatives().forEach(p -> { String key=searchKey(d.dayNumber(),query(p,destination)); queries.add(key); anchors.put(key,center); });
        }
        Map<String, Future<List<PlaceHit>>> futures = new LinkedHashMap<>();
        for (String q : queries) futures.put(q, pool.submit(() -> {
            Area center=anchors.get(q);
            List<PlaceHit> hits=new ArrayList<>(lookups.search(q.substring(q.indexOf('\n')+1),center,15));
            if(center!=null) hits.sort(Comparator.comparingDouble(h -> Geo.distanceMeters(center.lat(),center.lng(),h.lat(),h.lng())));
            return hits;
        }));
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

    private PlaceDraft pick(AiPlace p, Map<String, List<PlaceHit>> hitsByQuery, Set<String> used, Set<String> usedNames, String destination, String extraRequest, int day, int days, Map<String,Integer> reservedMeals, List<ExtraRequestRules.Visit> requestVisits) {
        String requestedName = ItineraryRules.nameKey(p.name(), destination);
        if (openAi.enabled() && usedNames.contains(requestedName)) return null;
        for (PlaceHit h : hitsByQuery.getOrDefault(searchKey(day,query(p, destination)), List.of()).stream()
                .sorted(Comparator.comparingInt((PlaceHit hit)->ExtraRequestRules.requiredPriority(extraRequest,hit.name(),p.category(),hit.placeId())).reversed()).toList()) {
            if (h.placeId() == null || h.placeId().isBlank() || h.name() == null || h.name().isBlank()
                    || !Double.isFinite(h.lat()) || !Double.isFinite(h.lng()) || Math.abs(h.lat()) > 90 || Math.abs(h.lng()) > 180) continue;
            if (reservedMeals.containsKey(h.placeId()) && reservedMeals.get(h.placeId())!=day) continue;
            if (!ExtraRequestRules.allows(extraRequest, h.name(), p.category(),h.placeId()) || !ExtraRequestRules.allowsAdditional(extraRequest,new ExtraRequestRules.Visit(day,h.name(),p.category(),h.categoryName(),h.placeId()),days,requestVisits)) continue;
            if (!ExtraRequestRules.allowsMealHit(extraRequest,day,days,p.name(),p.category(),p.reason()+" "+p.searchQuery(),h.name(),h.categoryName())) continue;
            String resolvedName = ItineraryRules.nameKey(h.name(), destination);
            if (used.contains(h.placeId()) || usedNames.contains(resolvedName)) continue;
            if (!com.tripplanner.place.PlaceCategories.matches(p.category(), h)) continue;
            if (!com.tripplanner.place.DestinationRegions.contains(destination, h.address())) continue;
            used.add(h.placeId());
            if (openAi.enabled()) usedNames.add(requestedName);
            usedNames.add(resolvedName);
            return ExtraRequestRules.annotateMeal(new PlaceDraft(h.placeId(), h.name(), h.address(), h.lat(), h.lng(), p.visitTime(), p.reason(),
                    p.note(), p.category(), true, h.placeUrl(), h.phone(), null, h.categoryName()),extraRequest,day,days);
        }
        return null;
    }

    private static String searchKey(int day,String query) { return day+"\n"+query; }

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
