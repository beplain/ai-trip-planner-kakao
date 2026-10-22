package com.tripplanner.trip;

import com.tripplanner.auth.KakaoTokenVault;
import com.tripplanner.trip.TripDtos.CalendarResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class TalkCalendarService {
    private final TripService trips;
    private final KakaoTokenVault tokens;
    private final CalendarSyncStore store;
    private final TalkCalendarClient client;
    public TalkCalendarService(TripService trips, KakaoTokenVault tokens, CalendarSyncStore store, TalkCalendarClient client) {
        this.trips = trips; this.tokens = tokens; this.store = store; this.client = client;
    }
    public CalendarResult status(Long tripId, Long userId) {
        var trip = trips.get(tripId, userId); // Membership check before returning any calendar data.
        var existing = store.find(tripId, userId);
        if (existing.isPresent()) return existing.get();
        if (trip.startDate() == null || trip.endDate() == null) return new CalendarResult("NO_DATE", "여행 기간을 지정하면 톡캘린더에 등록할 수 있어요.", null);
        if (!tokens.connected(userId)) return new CalendarResult("NOT_CONNECTED", "톡캘린더에 연결하고 일정 등록에 동의해 주세요.", null);
        return new CalendarResult("NOT_REGISTERED", "이 여행은 아직 톡캘린더에 등록되지 않았어요.", null);
    }
    public CalendarResult sync(Long tripId, Long userId) {
        var trip = trips.get(tripId, userId);
        var existing = store.find(tripId, userId);
        if (existing.isPresent() && !existing.get().status().equals("FAILED")) return existing.get();
        if (trip.startDate() == null || trip.endDate() == null) return new CalendarResult("NO_DATE", "여행 기간을 먼저 지정해 주세요.", null);
        if (!tokens.connected(userId)) return new CalendarResult("NOT_CONNECTED", "여행은 저장됐어요. 톡캘린더 연결 후 등록해 주세요.", null);
        String token;
        try { token = tokens.accessToken(userId); }
        catch (Exception e) { return new CalendarResult("NOT_CONNECTED", "카카오 인증이 만료됐거나 갱신되지 않았어요. 톡캘린더에 다시 연결해 주세요.", null); }
        var claim = store.claim(tripId, userId);
        if (!claim.acquired()) return claim.result();
        try {
            String eventId = client.create(token, trip);
            return store.finish(tripId, userId, "CREATED", eventId, "내 톡캘린더에 여행 일정 1개를 등록했어요.");
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code >= 500) return unknown(tripId, userId);
            String message = code == 401 || code == 403 ? "카카오 인증 또는 톡캘린더 권한을 확인하고 다시 연결해 주세요."
                    : code == 429 ? "카카오 요청 한도를 초과했어요. 잠시 후 다시 시도해 주세요."
                    : "카카오가 등록 요청을 거절했어요. 톡캘린더 사용 권한·동의항목·여행 날짜를 확인해 주세요.";
            return store.finish(tripId, userId, "FAILED", null, message);
        } catch (Exception e) {
            // An interrupted response may have already created the remote event. Do not blindly retry.
            return unknown(tripId, userId);
        }
    }
    private CalendarResult unknown(Long tripId, Long userId) {
        return store.finish(tripId, userId, "UNKNOWN", null, "등록 응답을 확인하지 못했어요. 톡캘린더를 직접 확인해 주세요. 중복 방지를 위해 재전송하지 않습니다.");
    }
    public CalendarResult syncAfterSave(Long tripId, Long userId) {
        try { return sync(tripId, userId); }
        catch (Exception e) { return new CalendarResult("FAILED", "여행은 저장됐지만 톡캘린더 등록 상태를 확인하지 못했어요. 일정 화면에서 상태를 확인해 주세요.", null); }
    }
}
