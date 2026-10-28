package com.tripplanner.place;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Request-scoped lookup reuse across AI retries and verified-candidate recovery. No cross-user cache. */
public final class GenerationPlaces extends KakaoLocalClient {
    private final KakaoLocalClient delegate;
    private record Search(String query, Area area, int size) {}
    private final Map<Search, List<PlaceHit>> searches = new ConcurrentHashMap<>();
    private final Map<String, Optional<Area>> areas = new ConcurrentHashMap<>();
    public GenerationPlaces(KakaoLocalClient delegate) { super(null, null); this.delegate = delegate; }
    @Override public boolean enabled() { return delegate.enabled(); }
    @Override public Optional<Area> resolveArea(String destination) {
        return areas.computeIfAbsent(destination, delegate::resolveArea);
    }
    @Override public List<PlaceHit> search(String query, Area area, int size) {
        return searches.computeIfAbsent(new Search(query,area,size), key -> List.copyOf(delegate.search(key.query(),key.area(),key.size())));
    }
}
