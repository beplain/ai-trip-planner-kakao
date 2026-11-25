package com.tripplanner.trip;
import com.tripplanner.trip.TripDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trips/transport")
public class FlightController {
    private final FlightTimetableClient flights;
    public FlightController(FlightTimetableClient flights) { this.flights=flights; }
    @GetMapping("/airports") public List<Airport> airports() { return flights.airports(); }
    @GetMapping("/airports/{id}/location") public SelectedPlace location(@PathVariable String id) { return flights.location(id); }
    @PostMapping("/flights") public List<FlightOption> options(@Valid @RequestBody FlightLookupRequest q) { return flights.options(q); }
}
