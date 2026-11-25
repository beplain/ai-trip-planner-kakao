package com.tripplanner.trip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import java.time.*;
import java.util.*;

/** Server-owned deletion history. Clients never supply the archived place to restore. */
final class RecoveryArchive {
    static final Duration RETENTION=Duration.ofDays(30);
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    record DeletedPlace(String id, Instant deletedAt, int dayNumber, String optionId, String context, PlaceDraft place) {}
    record TrashItem(String id,String title,String destination,Instant deletedAt,Instant expiresAt) {}
    record RestorePlace(Long version,String visitTime) {}
    static List<DeletedPlace> read(String value) {
        if(value==null || value.isBlank())return List.of();
        try { List<DeletedPlace> entries=JSON.readValue(value,new TypeReference<>(){});
            return entries.stream().filter(e->!expired(e.deletedAt())).toList();
        }catch(Exception e){throw new IllegalStateException("삭제 기록 읽기 실패",e);}
    }
    static String encode(List<DeletedPlace> values) {
        if(values.isEmpty())return null;
        try{return JSON.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException("삭제 기록 저장 실패",e);}
    }
    static String add(String value,int day,String option,String context,PlaceDraft place) {
        var rows=new ArrayList<>(read(value));
        rows.add(0,new DeletedPlace(UUID.randomUUID().toString(),Instant.now(),day,option,context,place));
        return encode(rows);
    }
    static DeletedPlace find(String value,String id) {
        return read(value).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->ApiException.notFound("삭제한 장소"));
    }
    static String remove(String value,String id) {return encode(read(value).stream().filter(e->!e.id().equals(id)).toList());}
    static boolean expired(Instant deletedAt){return deletedAt!=null && !deletedAt.plus(RETENTION).isAfter(Instant.now());}
    static String context(TripDraft draft) {
        try{return JSON.writeValueAsString(Arrays.asList(draft.destination(),draft.startDate(),draft.endDate(),draft.budget(),draft.styles(),draft.request(),draft.travelDetails()));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static PlaceDraft at(DeletedPlace row,String time) {
        if(time==null || time.isBlank())return row.place();
        TravelSchedule.minute(time);
        return TripRouteOptimizer.at(row.place(),time);
    }
    static void checkSpace(List<PlaceDraft> places,PlaceDraft restored,TravelDetails details) {
        int minute=TravelSchedule.minute(restored.visitTime());
        int gap=TravelSchedule.gap(details);
        for(var p:places) {
            if(Objects.equals(p.placeId(),restored.placeId()) && Objects.equals(p.name(),restored.name()))
                throw ApiException.badRequest("이미 일정에 포함된 장소입니다.");
            if(p.fixedEventId()!=null)continue; // Full schedule validation protects fixed events.
            int other=TravelSchedule.minute(p.visitTime());
            if(minute<other+p.stayMinutes()+gap && other<minute+restored.stayMinutes()+gap)
                throw ApiException.badRequest("복원할 시간이 다른 일정과 겹칩니다. 복원 시각을 조정해 주세요.");
        }
    }
    static PlaceDraft snapshot(ItineraryPlace p) {
        return new PlaceDraft(p.getPlaceId(),p.getName(),p.getAddress(),p.getLatitude(),p.getLongitude(),p.getVisitTime(),
                p.getReason(),p.getNote(),p.getCategory(),p.isVerified(),p.getPlaceUrl(),p.getPhone(),p.getFixedEventId())
                .withBusinessHours(p.getBusinessHours()).withDurationMinutes(p.getDurationMinutes());
    }
}
