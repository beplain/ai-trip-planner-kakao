package com.tripplanner.place;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Request-scoped lookup reuse across AI retries and verified-candidate recovery. No cross-user cache. */
public final class GenerationPlaces extends KakaoLocalClient {
    private final KakaoLocalClient delegate;
    private final String destination;
    private record Search(String query, Area area, int size) {}
    private final Map<Search, List<PlaceHit>> searches = new ConcurrentHashMap<>();
    private final Map<String, Optional<Area>> areas = new ConcurrentHashMap<>();
    private final Map<String,List<String>> expansions;
    public GenerationPlaces(KakaoLocalClient delegate) { this(delegate,Map.of()); }
    public GenerationPlaces(KakaoLocalClient delegate,Map<String,List<String>> expansions) {
        this(delegate,expansions,null);
    }
    public GenerationPlaces(KakaoLocalClient delegate,Map<String,List<String>> expansions,String destination) {
        super(null,null); this.destination=destination; this.delegate=delegate; this.expansions=Map.copyOf(expansions);
    }
    @Override public boolean enabled() { return delegate.enabled(); }
    @Override public Optional<Area> resolveArea(String destination) {
        return areas.computeIfAbsent(destination, key -> DestinationRegions.providerQueries(key).stream().map(delegate::resolveArea).flatMap(Optional::stream).findFirst());
    }
    @Override public List<PlaceHit> search(String query, Area area, int size) {
        return searches.computeIfAbsent(new Search(query,area,size), key -> {
            Map<String,PlaceHit> combined=new LinkedHashMap<>();
            for(String term:expansions.getOrDefault(query,List.of(query)))
                for(String providerQuery:DestinationRegions.providerQueries(term))
                    for(PlaceHit hit:delegate.search(providerQuery,area,size))
                        if(destination==null || DestinationRegions.contains(destination,hit.address())) combined.putIfAbsent(hit.placeId(),hit);
            return List.copyOf(combined.values());
        });
    }
}
