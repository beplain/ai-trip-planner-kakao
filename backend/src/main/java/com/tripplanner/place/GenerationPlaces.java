package com.tripplanner.place;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Request-scoped lookup reuse across AI retries and verified-candidate recovery. No cross-user cache. */
public final class GenerationPlaces extends KakaoLocalClient {
    private final KakaoLocalClient delegate;
    private final String destination;
    private record QueryKey(String query, Area area) {}
    private record Bucket(int requested, List<PlaceHit> hits) {}
    private final Map<QueryKey, Bucket> searches;
    private final Map<String, Optional<Area>> areas;
    private final Map<String,List<String>> expansions;
    public GenerationPlaces(KakaoLocalClient delegate) { this(delegate,Map.of()); }
    public GenerationPlaces(KakaoLocalClient delegate,Map<String,List<String>> expansions) {
        this(delegate,expansions,null);
    }
    public GenerationPlaces(KakaoLocalClient delegate,Map<String,List<String>> expansions,String destination) {
        this(delegate,expansions,destination,new ConcurrentHashMap<>(),new ConcurrentHashMap<>());
    }
    private GenerationPlaces(KakaoLocalClient delegate,Map<String,List<String>> expansions,String destination,
                             Map<QueryKey,Bucket> searches,Map<String,Optional<Area>> areas) {
        super(null,null); this.destination=destination; this.delegate=delegate; this.expansions=Map.copyOf(expansions);
        this.searches=searches; this.areas=areas;
    }
    /** Same Kakao results for a later step of this request, with extra meal-query aliases. */
    public GenerationPlaces withExpansions(Map<String,List<String>> extra) {
        if(extra==null || extra.isEmpty()) return this;
        Map<String,List<String>> merged=new LinkedHashMap<>(expansions);
        extra.forEach(merged::putIfAbsent);
        if(merged.equals(expansions)) return this;
        return new GenerationPlaces(delegate,merged,destination,searches,areas);
    }
    @Override public boolean enabled() { return delegate.enabled(); }
    @Override public Optional<Area> resolveArea(String destination) {
        return areas.computeIfAbsent(destination, key -> DestinationRegions.providerQueries(key).stream().map(delegate::resolveArea).flatMap(Optional::stream).findFirst());
    }
    @Override public List<PlaceHit> search(String query, Area area, int size) {
        Map<String,PlaceHit> combined=new LinkedHashMap<>();
        for(String term:expansions.getOrDefault(query,List.of(query)))
            for(String providerQuery:DestinationRegions.providerQueries(term))
                for(PlaceHit hit:cached(providerQuery,area,size))
                    if(destination==null || DestinationRegions.contains(destination,hit.address())) combined.putIfAbsent(hit.placeId(),hit);
        return List.copyOf(combined.values());
    }
    private List<PlaceHit> cached(String query, Area area, int size) {
        int want=Math.max(1,Math.min(15,size));
        return searches.compute(new QueryKey(query,area), (ignored,existing) -> {
            if(existing!=null && (existing.hits.size()<existing.requested || existing.requested>=want)) return existing;
            int fetch=existing==null?want:Math.max(want,existing.requested);
            return new Bucket(fetch,List.copyOf(delegate.search(query,area,fetch)));
        }).hits;
    }
}
