package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.trip.TripDtos.Transport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportCompatibilityTests {
    @Test void savedTransportWithRemovedRouteFieldsStillLoadsWithoutRestoringRouteBehavior() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var transport=mapper.readValue("""
            {"mode":"BUS","arrivalPlace":"대구","arrivalTime":"10:00","departurePlace":"대구역","departureTime":"18:00",
             "originTime":"08:00","arrivalAuto":false,"returnArrivalTime":"20:00","returnArrivalAuto":false,
             "outboundMode":"PUBLIC_TRANSIT","outboundRoute":{"id":"old","steps":[{"type":"BUS","operatingHours":{"status":"CHECKED"}}]},
             "returnMode":"PUBLIC_TRANSIT","returnRoute":{"id":"old-return","train":{"departureTime":"18:30"}}}
            """,Transport.class);
        assertEquals("10:00",transport.arrivalTime()); assertEquals("18:00",transport.departureTime());
        assertEquals("20:00",transport.returnArrivalTime()); assertEquals(Boolean.FALSE,transport.returnArrivalAuto());
        String saved=mapper.writeValueAsString(transport);
        assertFalse(saved.contains("outboundRoute")); assertFalse(saved.contains("returnRoute"));
        assertFalse(saved.contains("operatingHours"));
    }
}
