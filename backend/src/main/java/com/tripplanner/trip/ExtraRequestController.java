package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/trips/requests")
public class ExtraRequestController {
    /** Recompute from current visits, not a cached success flag from the initial generation. */
    @PostMapping("/check")
    public List<ExtraRequestRules.Check> check(@Valid @RequestBody TripDraft draft) {
        return ExtraRequestRules.checks(draft);
    }
}
