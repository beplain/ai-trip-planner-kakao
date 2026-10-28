package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class TravelDetailsConverter implements AttributeConverter<TripDtos.TravelDetails, String> {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public String convertToDatabaseColumn(TripDtos.TravelDetails value) {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("숙소·교통 정보 저장 실패", e); }
    }
    @Override public TripDtos.TravelDetails convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.readValue(value, TripDtos.TravelDetails.class); }
        catch (Exception e) { throw new IllegalArgumentException("숙소·교통 정보 읽기 실패", e); }
    }
}
