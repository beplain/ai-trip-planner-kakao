package com.tripplanner.ai;

import java.time.LocalDate;
import java.util.List;

public record ItineraryRequest(String destination, LocalDate startDate, int days, String budget,
                               List<String> styles, String extraRequest, com.tripplanner.trip.TripDtos.TravelDetails travelDetails,
                               boolean serverSchedulesMeals) {
    public ItineraryRequest(String destination, LocalDate startDate, int days, String budget,
                            List<String> styles, String extraRequest, com.tripplanner.trip.TripDtos.TravelDetails travelDetails) {
        this(destination, startDate, days, budget, styles, extraRequest, travelDetails, false);
    }
    public ItineraryRequest(String destination, LocalDate startDate, int days, String budget,
                            List<String> styles, String extraRequest) {
        this(destination, startDate, days, budget, styles, extraRequest, null);
    }
}
