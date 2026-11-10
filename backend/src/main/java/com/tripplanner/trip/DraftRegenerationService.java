package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.springframework.stereotype.Service;

/** Remote generation runs outside a DB transaction. Only the final replacement acquires a lock. */
@Service
public class DraftRegenerationService {
    private final DraftRooms drafts;
    private final TripGenerationService generator;
    public DraftRegenerationService(DraftRooms drafts, TripGenerationService generator) {
        this.drafts = drafts; this.generator = generator;
    }
    public DraftOptions options(String id, RegenerateDraftRequest request, Long user) {
        drafts.checkRegeneration(id,request.version(),user);
        var options = generator.generateOptions(request.input());
        drafts.checkRegeneration(id,request.version(),user);
        return new DraftOptions(options.options().stream().map(option -> {
            var d = option.draft();
            return new DraftOption(option.id(),option.label(),option.description(),new TripDraft(request.title().trim(),d.destination(),d.startDate(),d.endDate(),
                    d.budget(),d.styles(),d.days(),d.source(),d.warnings(),d.request(),d.travelDetails()).withRequestMetadata(d.request(),d.interpretedRequest()));
        }).toList(),options.warnings());
    }

    public DraftRooms.RoomResponse regenerate(String id, RegenerateDraftRequest request, Long user) {
        drafts.checkRegeneration(id, request.version(), user);
        var generated = generator.generate(request.input());
        var replacement = new TripDraft(request.title().trim(), generated.destination(), generated.startDate(), generated.endDate(),
                generated.budget(), generated.styles(), generated.days(), generated.source(), generated.warnings(), request.input().request(), generated.travelDetails()).withRequestMetadata(request.input().request(),generated.interpretedRequest());
        // If someone edited/finalized/joined during generation, reject rather than overwrite their newer version.
        return drafts.replaceGenerated(id, request.version(), replacement, user);
    }
}
