package com.tripplanner.ai;

import java.time.LocalDate;
import java.util.List;

public record ItineraryRequest(String destination, LocalDate startDate, int days, String budget,
                               List<String> styles, String extraRequest, com.tripplanner.trip.TripDtos.TravelDetails travelDetails) {
    public ItineraryRequest(String destination, LocalDate startDate, int days, String budget,
                            List<String> styles, String extraRequest) {
        this(destination, startDate, days, budget, styles, extraRequest, null);
    }
}
