package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trips/transport")
public class TransportArrivalController {
    private final TransportArrivalService arrival;
    public TransportArrivalController(TransportArrivalService arrival) { this.arrival = arrival; }
    @PostMapping("/arrival")
    public Transport calculate(@RequestBody Transport transport,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) { return arrival.calculate(transport,date); }
    @PostMapping("/trains")
    public List<TrainOption> trains(@Valid @RequestBody TrainLookupRequest request) {
        return arrival.trainOptions(request.transport(),request.date());
    }
}
