package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trips")
public class TripController {
    private final TripService trips;
    private final TripGenerationService generator;
    private final InviteService invites;

    public TripController(TripService trips, TripGenerationService generator, InviteService invites) {
        this.trips = trips;
        this.generator = generator;
        this.invites = invites;
    }

    /** 여행 조건 → AI 초안 (저장하지 않음) */
    @PostMapping("/generate")
    public TripDraft generate(@Valid @RequestBody GenerateRequest req) {
        return generator.generate(req);
    }

    @PostMapping("/generate-options")
    public DraftOptions generateOptions(@Valid @RequestBody GenerateRequest req) {
        return generator.generateOptions(req);
    }

    /** 사용자가 확정한 일정 저장 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TripDraft draft) {
        Long userId = CurrentUser.id(jwt);
        return trips.create(draft, userId);
    }

    @GetMapping
    public List<TripSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return trips.list(CurrentUser.id(jwt));
    }

    @GetMapping("/{tripId}")
    public TripResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId) {
        return trips.get(tripId, CurrentUser.id(jwt));
    }

    @PutMapping("/{tripId}")
    public TripResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                               @Valid @RequestBody UpdateTripRequest req) {
        return trips.updateInfo(tripId, req, CurrentUser.id(jwt));
    }

    @DeleteMapping("/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId) {
        trips.delete(tripId, CurrentUser.id(jwt));
    }

    @PutMapping("/{tripId}/days/{dayId}/places/order")
    public TripResponse reorder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                                @PathVariable Long dayId, @Valid @RequestBody ReorderRequest req) {
        return trips.reorder(tripId, dayId, req, CurrentUser.id(jwt));
    }

    @PutMapping("/{tripId}/places/{placeId}")
    public TripResponse updatePlace(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                                    @PathVariable Long placeId, @Valid @RequestBody UpdatePlaceRequest req) {
        return trips.updatePlace(tripId, placeId, req, CurrentUser.id(jwt));
    }

    /** placeId = 일정 내 장소 id(ItineraryPlace.id). version은 충돌 감지용 */
    @DeleteMapping("/{tripId}/places/{placeId}")
    public TripResponse deletePlace(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                                    @PathVariable Long placeId, @RequestParam Long version) {
        return trips.deletePlace(tripId, placeId, version, CurrentUser.id(jwt));
    }

    /** 폴링: since=내가 가진 version */
    @GetMapping("/{tripId}/changes")
    public ChangesResponse changes(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                                   @RequestParam(required = false) Long since) {
        return trips.changes(tripId, CurrentUser.id(jwt), since);
    }

    @PostMapping("/{tripId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(@AuthenticationPrincipal Jwt jwt, @PathVariable Long tripId,
                                       @Valid @RequestBody CreateInviteRequest req) {
        return invites.create(tripId, req.role(), CurrentUser.id(jwt));
    }
}
