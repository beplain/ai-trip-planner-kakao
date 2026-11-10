package com.tripplanner.trip;

import com.tripplanner.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {
    private final DraftRooms drafts;
    private final DraftRegenerationService regeneration;
    public DraftController(DraftRooms drafts, DraftRegenerationService regeneration) {
        this.drafts = drafts; this.regeneration = regeneration;
    }
    @PostMapping("/{id}/regenerate")
    public DraftRooms.RoomResponse regenerate(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                              @Valid @RequestBody TripDtos.RegenerateDraftRequest request) {
        return regeneration.regenerate(id, request, CurrentUser.id(jwt));
    }
    @PostMapping("/{id}/regenerate-options")
    public TripDtos.DraftOptions regenerateOptions(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                   @Valid @RequestBody TripDtos.RegenerateDraftRequest request) {
        return regeneration.options(id,request,CurrentUser.id(jwt));
    }
    @PostMapping("/{id}/regenerate-selection")
    public DraftRooms.RoomResponse selectRegenerated(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                     @Valid @RequestBody TripDtos.SelectRegeneratedRequest request) {
        return drafts.replaceGenerated(id,request.version(),request.draft(),CurrentUser.id(jwt));
    }
    @PostMapping public DraftRooms.RoomResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TripDtos.TripDraft draft) {
        return drafts.create(draft, CurrentUser.id(jwt));
    }
    @GetMapping public List<DraftRooms.RoomResponse> list(@AuthenticationPrincipal Jwt jwt) { return drafts.list(CurrentUser.id(jwt)); }
    @GetMapping("/summaries")
    public List<DraftRooms.RoomSummary> summaries(@AuthenticationPrincipal Jwt jwt) {
        return drafts.summaries(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}") public DraftRooms.RoomResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return drafts.get(id, CurrentUser.id(jwt)); }
    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam Long version) {
        drafts.delete(id, version, CurrentUser.id(jwt));
    }
    @PutMapping("/{id}/option")
    public DraftRooms.RoomResponse switchOption(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                               @Valid @RequestBody TripDtos.SwitchDraftOptionRequest request) {
        return drafts.switchOption(id,request,CurrentUser.id(jwt));
    }
    @PutMapping("/{id}/title") public DraftRooms.RoomResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody TripDtos.RenameDraftRequest request) {
        return drafts.rename(id, request, CurrentUser.id(jwt));
    }
    @PutMapping("/{id}") public DraftRooms.RoomResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DraftRooms.Update update) {
        return drafts.update(id, update, CurrentUser.id(jwt));
    }
    @PostMapping("/{id}/save") public TripDtos.TripResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DraftRooms.Save save) {
        return drafts.save(id, save.version(), CurrentUser.id(jwt));
    }
    @PostMapping("/{id}/invites") public TripDtos.InviteResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody TripDtos.CreateInviteRequest request) {
        return drafts.invite(id, request.role(), CurrentUser.id(jwt));
    }
}
