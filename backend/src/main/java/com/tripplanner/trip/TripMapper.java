package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;

import java.util.List;

final class TripMapper {
    private TripMapper() {}

    static PlaceResponse place(ItineraryPlace p) {
        return new PlaceResponse(p.getId(), p.getPlaceId(), p.getName(), p.getAddress(), p.getLatitude(),
                p.getLongitude(), p.getVisitTime(), p.getReason(), p.getNote(), p.getCategory(), p.isVerified(),
                p.getPlaceUrl(), p.getPhone(), p.getOrderIndex(), p.getFixedEventId(), p.getBusinessHours());
    }

    static TripResponse trip(Trip t, MemberRole myRole) {
        List<DayResponse> days = t.getDays().stream()
                .map(d -> new DayResponse(d.getId(), d.getDayNumber(), d.getDate(),
                        d.getPlaces().stream()
                                .sorted(java.util.Comparator.comparingInt(ItineraryPlace::getOrderIndex))
                                .map(TripMapper::place).toList()))
                .toList();
        List<MemberResponse> members = t.getMembers().stream()
                .map(m -> new MemberResponse(m.getUser().getId(), m.getUser().getName(), m.getUser().getEmail(),
                        m.getRole()))
                .toList();
        return new TripResponse(t.getId(), t.getTitle(), t.getDestination(), t.getStartDate(), t.getEndDate(),
                t.getDays().size(), t.getBudget(), List.copyOf(t.getStyles()), t.getVersion(), t.getUpdatedAt(),
                myRole, days, members, t.getTravelDetails());
    }

    static TripSummary summary(Trip t, MemberRole myRole) {
        int placeCount = t.getDays().stream().mapToInt(d -> d.getPlaces().size()).sum();
        return new TripSummary(t.getId(), t.getTitle(), t.getDestination(), t.getStartDate(), t.getEndDate(),
                t.getDays().size(), t.getBudget(), List.copyOf(t.getStyles()), myRole, placeCount,
                t.getMembers().size(), t.getUpdatedAt());
    }
}
