package com.tripplanner.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TripDtos {
    private TripDtos() {}

    // ───────── 요청 ─────────

    /** POST /api/trips/generate */
    public record GenerateRequest(
            @NotBlank @Size(max = 50) String destination,
            LocalDate startDate,
            @NotNull @Min(1) @Max(7) Integer days,
            @Size(max = 30) String budget,
            @Size(max = 8) List<@NotBlank @Size(max = 20) String> styles,
            @Size(max = 200) String request
    ) {}

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
            @Size(max = 50) String phone
    ) {}

    public record DayDraft(
            @Min(1) int dayNumber,
            LocalDate date,
            @NotNull @Size(max = 12) List<@Valid PlaceDraft> places
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
            @Size(max = 200) String request
    ) {
        public TripDraft(String title, String destination, LocalDate startDate, LocalDate endDate, String budget,
                         List<String> styles, List<DayDraft> days, String source, List<String> warnings) {
            this(title, destination, startDate, endDate, budget, styles, days, source, warnings, null);
        }
    }

    public record RegenerateDraftRequest(@NotNull Long version, @NotBlank @Size(max = 100) String title,
                                         @NotNull @Valid GenerateRequest input) {}

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
            String placeUrl, String phone, int orderIndex
    ) {}

    public record DayResponse(Long id, int dayNumber, LocalDate date, List<PlaceResponse> places) {}

    public record MemberResponse(Long userId, String name, String email, MemberRole role) {}

    public record TripResponse(
            Long id, String title, String destination, LocalDate startDate, LocalDate endDate, int dayCount,
            String budget, List<String> styles, Long version, Instant updatedAt, MemberRole myRole,
            List<DayResponse> days, List<MemberResponse> members, CalendarResult calendar
    ) {
        public TripResponse withCalendar(CalendarResult result) {
            return new TripResponse(id, title, destination, startDate, endDate, dayCount, budget, styles, version,
                    updatedAt, myRole, days, members, result);
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
