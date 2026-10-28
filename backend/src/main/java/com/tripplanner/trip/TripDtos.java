package com.tripplanner.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TripDtos {
    private TripDtos() {}

    // ───────── 요청 ─────────

    public enum MovementMode { CAR, PUBLIC_TRANSIT }
    public enum TransportMode { KTX, PLANE, BUS, CAR }
    public enum LuggagePlan { HOTEL, LOCKER, CARRY }
    public record SelectedPlace(@NotBlank @Pattern(regexp = "\\d{1,30}") String placeId,
                                @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 200) String address,
                                @NotNull Double latitude, @NotNull Double longitude, String placeUrl) {
        public SelectedPlace {
            name = name == null ? null : name.trim(); address = address == null ? null : address.trim();
            placeUrl = placeId == null ? null : "https://place.map.kakao.com/" + placeId;
        }
    }
    /** Day numbers are relative to the trip (1-based); stays may span multiple nights. */
    public record Stay(@NotBlank @Size(max = 100) String name,
                       @NotBlank @Size(max = 200) String address,
                       @Min(1) @Max(7) int checkInDay, @Min(1) @Max(7) int checkOutDay,
                       @NotBlank String checkInTime, @NotBlank String checkOutTime,
                       @NotNull LuggagePlan beforeCheckIn, @NotNull LuggagePlan afterCheckOut,
                       @Size(max = 30) String placeId, Double latitude, Double longitude, @Size(max = 300) String placeUrl) {
        public Stay(String name, String address, int checkInDay, int checkOutDay, String checkInTime,
                    String checkOutTime, LuggagePlan beforeCheckIn, LuggagePlan afterCheckOut) {
            this(name, address, checkInDay, checkOutDay, checkInTime, checkOutTime, beforeCheckIn, afterCheckOut, null, null, null, null);
        }
        public Stay {
            name = name == null ? null : name.trim(); address = address == null ? null : address.trim();
            checkInTime = checkInTime == null ? null : checkInTime.trim();
            checkOutTime = checkOutTime == null ? null : checkOutTime.trim();
            placeId = placeId == null || placeId.isBlank() ? null : placeId.trim();
            // Only link to the selected Kakao place, never an arbitrary client-supplied URL.
            placeUrl = placeId == null ? null : "https://place.map.kakao.com/" + placeId;
        }
    }
    public record Transport(@NotNull TransportMode mode,
                            @NotBlank @Size(max = 100) String arrivalPlace, @NotBlank String arrivalTime,
                            @NotBlank @Size(max = 100) String departurePlace, @NotBlank String departureTime,
                            @Valid SelectedPlace arrivalLocation, @Valid SelectedPlace departureLocation) {
        public Transport(TransportMode mode, String arrivalPlace, String arrivalTime, String departurePlace, String departureTime) {
            this(mode, arrivalPlace, arrivalTime, departurePlace, departureTime, null, null);
        }
        public Transport {
            arrivalPlace = arrivalPlace == null ? null : arrivalPlace.trim();
            departurePlace = departurePlace == null ? null : departurePlace.trim();
            arrivalTime = arrivalTime == null ? null : arrivalTime.trim();
            departureTime = departureTime == null ? null : departureTime.trim();
            if (arrivalLocation != null) arrivalPlace = arrivalLocation.name();
            if (departureLocation != null) departurePlace = departureLocation.name();
        }
    }
    public record TravelDetails(@NotNull MovementMode movementMode,
                                @Size(max = 6) List<@Valid Stay> stays, @Valid Transport transport) {}

    /** POST /api/trips/generate */
    public record GenerateRequest(
            @NotBlank @Size(max = 50) String destination,
            LocalDate startDate,
            @NotNull @Min(1) @Max(7) Integer days,
            @Size(max = 30) String budget,
            @Size(max = 8) List<@NotBlank @Size(max = 20) String> styles,
            @Size(max = 200) String request,
            @Valid TravelDetails travelDetails
    ) {
        public GenerateRequest(String destination, LocalDate startDate, Integer days, String budget,
                               List<String> styles, String request) {
            this(destination, startDate, days, budget, styles, request, null);
        }
    }

    /** AI 초안의 장소 / 저장 요청의 장소 (같은 형태) */
    public record PlaceDraft(
            @Size(max = 300) String placeId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 300) String address,
            Double latitude,
            Double longitude,
            @Size(max = 10) String visitTime,
            @Size(max = 500) String reason,
            @Size(max = 500) String note,
            @Size(max = 30) String category,
            boolean verified,
            @Size(max = 300) String placeUrl,
            @Size(max = 50) String phone,
            @Size(max = 100) String fixedEventId
    ) {
        public PlaceDraft(String placeId, String name, String address, Double latitude, Double longitude,
                          String visitTime, String reason, String note, String category, boolean verified,
                          String placeUrl, String phone) {
            this(placeId, name, address, latitude, longitude, visitTime, reason, note, category, verified, placeUrl, phone, null);
        }
    }

    public record DayDraft(
            @Min(1) int dayNumber,
            LocalDate date,
            @NotNull @Size(max = 30) List<@Valid PlaceDraft> places
    ) {}

    /**
     * POST /api/trips/generate 응답 = POST /api/trips 요청 본문.
     * 사용자가 초안을 검토·수정한 뒤 그대로 저장 요청에 보낸다. (source, warnings는 저장 시 무시)
     */
    public record TripDraft(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 100) String destination,
            LocalDate startDate,
            LocalDate endDate,
            @Size(max = 50) String budget,
            @Size(max = 8) List<@Size(max = 20) String> styles,
            @NotEmpty @Size(max = 7) List<@Valid DayDraft> days,
            String source,
            List<String> warnings,
            @Size(max = 200) String request,
            @Valid TravelDetails travelDetails
    ) {
        public TripDraft(String title, String destination, LocalDate startDate, LocalDate endDate, String budget,
                         List<String> styles, List<DayDraft> days, String source, List<String> warnings, String request) {
            this(title, destination, startDate, endDate, budget, styles, days, source, warnings, request, null);
        }
        public TripDraft(String title, String destination, LocalDate startDate, LocalDate endDate, String budget,
                         List<String> styles, List<DayDraft> days, String source, List<String> warnings) {
            this(title, destination, startDate, endDate, budget, styles, days, source, warnings, null);
        }
    }

    public record RegenerateDraftRequest(@NotNull Long version, @NotBlank @Size(max = 100) String title,
                                         @NotNull @Valid GenerateRequest input) {}

    public record RenameDraftRequest(@NotNull Long version, @NotBlank @Size(max = 100) String title) {}

    /** PUT /api/trips/{id} */
    public record UpdateTripRequest(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 100) String destination,
            LocalDate startDate,
            @NotNull @Min(1) @Max(7) Integer days,
            @Size(max = 50) String budget,
            @Size(max = 8) List<@Size(max = 20) String> styles,
            @NotNull Long version
    ) {}

    /** PUT /api/trips/{id}/days/{dayId}/places/order */
    public record ReorderRequest(@NotNull List<@NotNull Long> placeIds, @NotNull Long version) {}

    /** PUT /api/trips/{id}/places/{placeId} — 방문 시각/메모 수정 */
    public record UpdatePlaceRequest(@Size(max = 10) String visitTime, @Size(max = 500) String note,
                                     @NotNull Long version) {}

    /** POST /api/trips/{id}/invites */
    public record CreateInviteRequest(@NotNull MemberRole role) {}

    // ───────── 응답 ─────────

    public record PlaceResponse(
            Long id, String placeId, String name, String address, Double latitude, Double longitude,
            String visitTime, String reason, String note, String category, boolean verified,
            String placeUrl, String phone, int orderIndex, String fixedEventId
    ) {}

    public record DayResponse(Long id, int dayNumber, LocalDate date, List<PlaceResponse> places) {}

    public record MemberResponse(Long userId, String name, String email, MemberRole role) {}

    public record TripResponse(
            Long id, String title, String destination, LocalDate startDate, LocalDate endDate, int dayCount,
            String budget, List<String> styles, Long version, Instant updatedAt, MemberRole myRole,
            List<DayResponse> days, List<MemberResponse> members, CalendarResult calendar, TravelDetails travelDetails
    ) {
        public TripResponse(Long id, String title, String destination, LocalDate startDate, LocalDate endDate, int dayCount,
                            String budget, List<String> styles, Long version, Instant updatedAt, MemberRole myRole,
                            List<DayResponse> days, List<MemberResponse> members, CalendarResult calendar) {
            this(id, title, destination, startDate, endDate, dayCount, budget, styles, version, updatedAt,
                    myRole, days, members, calendar, null);
        }
        public TripResponse withCalendar(CalendarResult result) {
            return new TripResponse(id, title, destination, startDate, endDate, dayCount, budget, styles, version,
                    updatedAt, myRole, days, members, result, travelDetails);
        }
    }

    public record CalendarResult(String status, String message, String eventId) {}

    public record TripSummary(
            Long id, String title, String destination, LocalDate startDate, LocalDate endDate, int dayCount,
            String budget, List<String> styles, MemberRole myRole, int placeCount, int memberCount,
            Instant updatedAt
    ) {}

    /** GET /api/trips/{id}/changes?since={version} */
    public record ChangesResponse(boolean changed, Long version, Instant updatedAt, TripResponse trip) {}

    public record InviteResponse(String token, MemberRole role, String link, Instant expiresAt) {}

    public record InvitePreview(Long tripId, String tripTitle, String destination, MemberRole role,
                                String invitedBy, boolean expired, boolean alreadyMember, String draftId) {
        public InvitePreview(Long tripId, String tripTitle, String destination, MemberRole role,
                             String invitedBy, boolean expired, boolean alreadyMember) {
            this(tripId, tripTitle, destination, role, invitedBy, expired, alreadyMember, null);
        }
    }
}
