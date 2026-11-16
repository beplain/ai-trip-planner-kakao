package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelDetailsConverterTests {
    @Test
    void roundTripsOutboundAndReturnTrainDates() {
        var outbound = new TrainOption("out", LocalDate.of(2026, 11, 16),
                "101", "KTX", "서울", "부산", "09:00", "11:30");
        var returning = new TrainOption("back", LocalDate.of(2026, 11, 22),
                "102", "KTX", "부산", "서울", "18:00", "20:30");
        var transport = new Transport(TransportMode.KTX, "부산역", "11:30", "부산역", "18:00",
                null, null, null, "09:00", true, outbound, null, returning);
        var details = new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(), transport);
        var converter = new TravelDetailsConverter();

        String stored = converter.convertToDatabaseColumn(details);

        assertTrue(stored.contains("\"date\":\"2026-11-16\""));
        assertTrue(stored.contains("\"date\":\"2026-11-22\""));
        assertEquals(details, converter.convertToEntityAttribute(stored));
    }
}
