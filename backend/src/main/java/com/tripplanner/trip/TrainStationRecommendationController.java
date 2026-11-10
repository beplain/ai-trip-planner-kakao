package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trips/transport")
public class TrainStationRecommendationController {
    private final TrainStationRecommendationService service;
    public TrainStationRecommendationController(TrainStationRecommendationService service) { this.service=service; }
    @PostMapping("/station-recommendation")
    public StationRecommendation recommend(@Valid @RequestBody TrainLookupRequest request) { return service.recommend(request.transport(),request.date()); }
    @PostMapping("/station-candidates")
    public java.util.List<SelectedPlace> candidates(@Valid @RequestBody StationCandidatesRequest request) { return service.candidates(request.origin(),request.destination()); }
}
