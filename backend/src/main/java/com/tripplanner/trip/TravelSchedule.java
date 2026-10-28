package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import java.time.LocalTime;
import java.util.*;

/** Fixed user commitments are built by the server, never invented by the language model. */
public final class TravelSchedule {
    private TravelSchedule() {}
    public record Event(int day, String id, String name, String location, int minute, String note, SelectedPlace selectedPlace) {
        public Event(int day, String id, String name, String location, int minute, String note) {
            this(day, id, name, location, minute, note, (SelectedPlace) null);
        }
        public Event(int day, String id, String name, String location, int minute, String note, Stay stay) {
            this(day, id, name, location, minute, note, stay != null && stay.placeId() != null
                    ? new SelectedPlace(stay.placeId(), stay.name(), stay.address(), stay.latitude(), stay.longitude(), stay.placeUrl()) : null);
        }
        public PlaceDraft place() {
            boolean located = selectedPlace != null && selectedPlace.placeId() != null
                    && selectedPlace.latitude() != null && selectedPlace.longitude() != null;
            return new PlaceDraft(located ? selectedPlace.placeId() : null, name, location,
                    located ? selectedPlace.latitude() : null, located ? selectedPlace.longitude() : null,
                    clock(minute), "사용자가 입력한 숙소·교통 기준의 고정 일정",
                    note, "고정 일정", located, located ? selectedPlace.placeUrl() : null, null, id);
        }
    }
    public static int minute(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) throw ApiException.badRequest("시간은 09:00처럼 HH:mm 형식으로 입력해 주세요.");
        try { LocalTime t = LocalTime.parse(value); return t.getHour() * 60 + t.getMinute(); }
        catch (Exception e) { throw ApiException.badRequest("시간은 00:00~23:59 사이로 입력해 주세요."); }
    }
    public static String clock(int value) { return String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60); }
    private static List<Stay> stays(TravelDetails d) { return d == null || d.stays() == null ? List.of() : d.stays(); }
    public static int gap(TravelDetails d) { return d != null && d.movementMode() == MovementMode.CAR ? 30 : 45; }
    private static int departureBuffer(Transport t) { return t.mode() == TransportMode.PLANE ? 120 : t.mode() == TransportMode.CAR ? 15 : 30; }
    public static int start(int day, TravelDetails d) {
        return d != null && d.transport() != null && day == 1
                ? minute(d.transport().arrivalTime()) + (d.transport().mode() == TransportMode.PLANE ? 60 : 30) : 8 * 60;
    }
    public static int end(int day, int days, TravelDetails d) {
        return d != null && d.transport() != null && day == days
                ? minute(d.transport().departureTime()) - departureBuffer(d.transport()) : 22 * 60;
    }
    public static void validate(TravelDetails d, int days) {
        if (d == null) return; // Existing trips retain their old editing behavior.
        if (d.movementMode() == null) throw ApiException.badRequest("자동차 또는 대중교통을 선택해 주세요.");
        if (stays(d).size() > 6) throw ApiException.badRequest("숙소는 최대 6곳까지 입력할 수 있습니다.");
        if (d.transport() != null) {
            Transport t = d.transport();
            if (t.mode() == null || blank(t.arrivalPlace()) || blank(t.departurePlace())
                    || t.arrivalPlace().length() > 100 || t.departurePlace().length() > 100)
                throw ApiException.badRequest("교통편의 도착·출발 장소를 입력해 주세요.");
            minute(t.arrivalTime()); minute(t.departureTime());
            validateSelected(t.arrivalLocation()); validateSelected(t.departureLocation());
        }
        Set<Integer> nights = new HashSet<>();
        for (Stay s : stays(d)) {
            if (s == null || blank(s.name()) || blank(s.address()) || s.name().length() > 100 || s.address().length() > 200)
                throw ApiException.badRequest("숙소 이름과 주소를 입력해 주세요.");
            if (s.checkInDay() < 1 || s.checkOutDay() > days || s.checkOutDay() <= s.checkInDay())
                throw ApiException.badRequest("숙소 입실·퇴실 날짜는 여행 기간 안에서 1박 이상으로 선택해 주세요.");
            minute(s.checkInTime()); minute(s.checkOutTime());
            boolean metadata = s.placeId() != null || s.latitude() != null || s.longitude() != null;
            if (metadata && (s.placeId() == null || !s.placeId().matches("\\d{1,30}")
                    || s.latitude() == null || s.longitude() == null || !Double.isFinite(s.latitude()) || !Double.isFinite(s.longitude())
                    || Math.abs(s.latitude()) > 90 || Math.abs(s.longitude()) > 180))
                throw ApiException.badRequest("숙소의 지도 정보가 올바르지 않습니다. 카카오맵에서 다시 선택해 주세요.");
            if (s.beforeCheckIn() == null || s.afterCheckOut() == null) throw ApiException.badRequest("짐 보관 방법을 선택해 주세요.");
            for (int day = s.checkInDay(); day < s.checkOutDay(); day++)
                if (!nights.add(day)) throw ApiException.badRequest("같은 날짜의 숙박이 중복됩니다.");
        }
        // Arrival/departure constraints also bound fixed accommodation commitments.
        List<Event> events = eventsUnchecked(d, days);
        for (int day = 1; day <= days; day++) {
            if (start(day, d) >= end(day, days, d)) throw ApiException.badRequest("도착·출발 시각 사이에 일정과 이동을 위한 시간이 부족합니다.");
            final int n = day;
            List<Event> ordered = events.stream().filter(e -> e.day() == n).sorted(Comparator.comparingInt(Event::minute)).toList();
            int previous = -1;
            for (Event e : ordered) {
                if (e.minute() < 0 || e.minute() > 1439 || e.minute() <= previous)
                    throw ApiException.badRequest("숙소·교통 고정 일정의 시간이 겹칩니다. 시간을 조정해 주세요.");
                previous = e.minute();
                if (!e.id().startsWith("transport:") && (e.minute() < start(day, d) || e.minute() + 15 > end(day, days, d)))
                    throw ApiException.badRequest("숙소 또는 짐 보관 일정이 도착·출발 시간과 맞지 않습니다.");
            }
        }
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static void validateSelected(SelectedPlace p) {
        if (p == null) return; // Existing trips may still have manually entered locations.
        if (blank(p.placeId()) || !p.placeId().matches("\\d{1,30}") || blank(p.name()) || p.name().length() > 100
                || blank(p.address()) || p.address().length() > 200 || p.latitude() == null || p.longitude() == null
                || !Double.isFinite(p.latitude()) || !Double.isFinite(p.longitude())
                || Math.abs(p.latitude()) > 90 || Math.abs(p.longitude()) > 180)
            throw ApiException.badRequest("교통편 장소 정보가 올바르지 않습니다. 카카오맵에서 다시 선택해 주세요.");
    }
    public static List<Event> events(TravelDetails d, int days) {
        validate(d, days); return eventsUnchecked(d, days);
    }
    private static List<Event> eventsUnchecked(TravelDetails d, int days) {
        List<Event> out = new ArrayList<>();
        if (d == null) return out;
        Transport t = d.transport();
        if (t != null) {
            String kind = t.mode() == TransportMode.PLANE ? "공항" : t.mode() == TransportMode.CAR ? "목적지" : "역·터미널";
            out.add(new Event(1, "transport:arrival", kind + " 도착 · " + t.arrivalPlace(), t.arrivalLocation() == null ? t.arrivalPlace() : t.arrivalLocation().address(), minute(t.arrivalTime()), "도착 후 이동 준비 시간을 확보합니다.", t.arrivalLocation()));
            out.add(new Event(days, "transport:boarding", "귀가 " + kind + " 도착 · " + t.departurePlace(), t.departureLocation() == null ? t.departurePlace() : t.departureLocation().address(), minute(t.departureTime()) - departureBuffer(t), "출발 전 여유 시간입니다. 실제 탑승 수속·운행 정보를 확인해 주세요.", t.departureLocation()));
            out.add(new Event(days, "transport:departure", "귀가 출발 · " + t.departurePlace(), t.departureLocation() == null ? t.departurePlace() : t.departureLocation().address(), minute(t.departureTime()), "사용자가 입력한 출발 시각", t.departureLocation()));
        }
        int index = 0;
        for (Stay s : stays(d)) {
            String prefix = "stay:" + index++ + ":";
            int in = minute(s.checkInTime()), checkout = minute(s.checkOutTime());
            out.add(new Event(s.checkInDay(), prefix + "in", "숙소 체크인 · " + s.name(), s.address(), in, "입력한 체크인 예정 시각. 숙소별 입실 규정을 확인해 주세요.", s));
            out.add(new Event(s.checkOutDay(), prefix + "out", "숙소 체크아웃 · " + s.name(), s.address(), checkout, "입력한 체크아웃 시각까지 퇴실합니다.", s));
            int drop = Math.max(start(s.checkInDay(), d) + gap(d), 9 * 60);
            // When changing hotels, collect previous luggage before dropping it at the next hotel.
            int previousCheckout = stays(d).stream().filter(p -> p.checkOutDay() == s.checkInDay())
                    .mapToInt(p -> minute(p.checkOutTime()) + (p.afterCheckOut() == LuggagePlan.LOCKER ? gap(d) + 60
                            : p.afterCheckOut() == LuggagePlan.HOTEL ? 45 : 15) + gap(d)).max().orElse(0);
            drop = Math.max(drop, previousCheckout);
            if (s.beforeCheckIn() != LuggagePlan.CARRY && drop + 30 < in) {
                boolean hotel = s.beforeCheckIn() == LuggagePlan.HOTEL;
                out.add(new Event(s.checkInDay(), prefix + "drop", hotel ? "짐 맡기기 · " + s.name() : "보관함에 짐 맡기기",
                        hotel ? s.address() : storageLocation(t, s.checkInDay(), days, true), drop,
                        hotel ? "사용자가 숙소 보관 가능 여부를 확인한 계획입니다." : "역·터미널 보관함의 위치·운영 시간·빈자리는 직접 확인해야 합니다.", hotel ? s : null));
                if (!hotel) out.add(new Event(s.checkInDay(), prefix + "before-pickup", "체크인 전 짐 찾기", storageLocation(t, s.checkInDay(), days, true), in - gap(d) - 15, "보관함에서 짐을 찾고 숙소로 이동합니다."));
            }
            if (s.afterCheckOut() != LuggagePlan.CARRY) {
                boolean hotel = s.afterCheckOut() == LuggagePlan.HOTEL;
                int nextCheckIn = stays(d).stream().anyMatch(p -> p.checkInDay() == s.checkOutDay())
                        ? checkout + (hotel ? 45 : gap(d) + 60) : 18 * 60;
                int pickup = Math.min(nextCheckIn, end(s.checkOutDay(), days, d) - gap(d) - 30);
                int afterDrop = checkout + 15 + (hotel ? 0 : gap(d));
                if (pickup < afterDrop + 30) throw ApiException.badRequest("체크아웃 후 짐 보관·회수 시간이 부족합니다. 직접 소지하거나 숙소·교통 시간을 조정해 주세요.");
                out.add(new Event(s.checkOutDay(), prefix + "after-drop", "체크아웃 후 짐 맡기기" + (hotel ? " · " + s.name() : " · 보관함"),
                        hotel ? s.address() : storageLocation(t, s.checkOutDay(), days, false), afterDrop,
                        hotel ? "사용자가 확인한 숙소 보관 계획. 짐 회수 시간을 확인해 주세요." : "실제 보관함 위치·운영·이용 가능 여부 확인 필요", hotel ? s : null));
                out.add(new Event(s.checkOutDay(), prefix + "pickup", "맡긴 짐 찾기" + (hotel ? " · " + s.name() : " · 보관함"),
                        hotel ? s.address() : storageLocation(t, s.checkOutDay(), days, false), pickup, "다음 숙소 또는 귀가 장소로 이동하기 전에 짐을 회수합니다.", hotel ? s : null));
            }
        }
        return out.stream().sorted(Comparator.comparingInt(Event::day).thenComparingInt(Event::minute)).toList();
    }
    private static String storageLocation(Transport t, int day, int days, boolean before) {
        if (t != null && before && day == 1) return t.arrivalPlace() + " 주변 보관함 (위치 확인 필요)";
        if (t != null && !before && day == days) return t.departurePlace() + " 주변 보관함 (위치 확인 필요)";
        return "숙소 인근 역·터미널 보관함 (위치 확인 필요)";
    }
    public static boolean allowed(int day, int days, int visit, TravelDetails d, List<Event> events) {
        if (d == null) return true;
        if (visit < start(day, d) || visit + 60 + gap(d) > end(day, days, d)) return false;
        // Reserve a one-hour visit plus movement allowance around each fixed commitment.
        return events.stream().filter(e -> e.day() == day && !e.id().equals("transport:arrival") && !e.id().equals("transport:departure"))
                .noneMatch(e -> visit < e.minute() + 15 + gap(d) && visit + 60 + gap(d) > e.minute());
    }
    /** First-day evening activity must run through 20:00, after check-in, if the fixed commitments permit it. */
    public static int eveningStart(int day, int days, TravelDetails details, List<Event> events) {
        if (details == null || day != 1 || days < 2) return -1;
        int afterCheckIn = stays(details).stream().filter(s -> s.checkInDay() == 1)
                .mapToInt(s -> minute(s.checkInTime()) + 15 + gap(details)).max().orElse(0);
        int first = Math.max(19 * 60, Math.max(afterCheckIn, start(1, details)));
        for (int t = ((first + 14) / 15) * 15; t <= 22 * 60; t += 15) if (allowed(1, days, t, details, events)) return t;
        return -1; // Do not violate a late arrival/check-in to force an impossible evening visit.
    }
    public static String prompt(TravelDetails d, int days) {
        if (d == null) return "";
        StringBuilder s = new StringBuilder("\n숙소·교통 필수 조건:\n여행 중 이동: ")
                .append(d.movementMode() == MovementMode.CAR ? "자동차 (주차와 이동 고려)" : "대중교통 (버스·지하철·도보·환승 고려)").append('\n');
        for (Stay stay : stays(d)) s.append("숙소: ").append(stay.name()).append(" / ").append(stay.address())
                .append(" / ").append(stay.checkInDay()).append("일차 ").append(stay.checkInTime()).append(" 체크인 ~ ")
                .append(stay.checkOutDay()).append("일차 ").append(stay.checkOutTime()).append(" 체크아웃 / 입실 전 짐: ")
                .append(stay.beforeCheckIn()).append(" / 퇴실 후 짐: ").append(stay.afterCheckOut()).append('\n');
        List<Event> events = events(d, days);
        int evening = eveningStart(1, days, d, events);
        if (evening >= 0) s.append("첫날 체크인 뒤에도 저녁 식사·관광 등 일반 활동을 이어가며 최소 20:00까지 방문이 이어져야 합니다. ")
                .append(clock(evening)).append(" 이후 시작 가능한 저녁 장소와 대체 후보를 반드시 준비하세요.\n");
        for (int day = 1; day <= days; day++) s.append(day).append("일차 관광 가능 범위: ").append(clock(start(day, d))).append(" ~ ").append(clock(end(day, days, d))).append('\n');
        for (Event e : events) s.append(e.day()).append("일차 ").append(clock(e.minute())).append(" 고정: ").append(e.name()).append(" / ").append(e.location()).append('\n');
        return s.append("예약 숙소 이름, 역/공항 자체, 택시 이동, 체크인/아웃, 짐 보관 항목은 places와 alternatives에 절대 생성하지 마세요. 시간 배치는 서버가 보정하며, 매일의 필수 스타일을 충족하는 실제 관광·식사 장소와 대체 후보를 우선 추천하세요. 고정 시각은 절대 바꾸지 마세요. 일반 방문 60분, 이동 여유 ")
                .append(gap(d)).append("분을 확보하고 고정 항목 앞뒤와 겹치지 않게 하세요. 매일 해당 날짜 숙소 주변으로 묶고 첫날은 도착 장소부터, 마지막 날은 출발 장소 인근에서 마무리하세요. ")
                .append("HOTEL은 사용자가 보관 가능을 확인한 계획, LOCKER는 이용 가능 여부 확인이 필요한 계획, CARRY는 짐을 소지한 이동입니다. CARRY일 때 짐이 부담되는 활동은 피하세요. 실시간 노선·이동시간·보관함 이용 가능을 확인했다고 단정하지 마세요.\n").toString();
    }
    public static void validateDraft(TripDraft draft) {
        TravelDetails details = draft.travelDetails();
        if (details == null) {
            if (draft.days().stream().flatMap(d -> d.places().stream()).anyMatch(p -> p.fixedEventId() != null))
                throw ApiException.badRequest("고정 일정에는 숙소·교통 정보가 필요합니다.");
            return;
        }
        List<Event> expected = events(details, draft.days().size());
        Set<String> seen = new HashSet<>();
        Set<Integer> dayNumbers = new HashSet<>();
        for (DayDraft day : draft.days()) {
            if (day.dayNumber() < 1 || day.dayNumber() > draft.days().size() || !dayNumbers.add(day.dayNumber()))
                throw ApiException.badRequest("일차 번호가 올바르지 않습니다.");
            int previous = -1;
            int previousVisit = -1;
            for (PlaceDraft p : day.places()) {
                int time = minute(p.visitTime());
                if (time <= previous) throw ApiException.badRequest("숙소·교통을 반영한 일정은 방문 시각 순서로 배치해 주세요.");
                previous = time;
                if (p.fixedEventId() == null) {
                    if (previousVisit >= 0 && time < previousVisit + 60 + gap(details))
                        throw ApiException.badRequest("장소 사이에 방문 60분과 이동 여유 시간을 확보해 주세요.");
                    previousVisit = time;
                    if (!allowed(day.dayNumber(), draft.days().size(), time, details, expected)) throw ApiException.badRequest("일반 장소가 고정 일정 또는 도착·출발 시간과 겹칩니다.");
                } else {
                    Event event = expected.stream().filter(e -> e.id().equals(p.fixedEventId())).findFirst()
                            .orElseThrow(() -> ApiException.badRequest("알 수 없는 고정 일정입니다."));
                    if (!seen.add(event.id()) || day.dayNumber() != event.day() || !p.equals(event.place()))
                        throw ApiException.badRequest("고정 일정은 여행 정보 수정에서 변경해 주세요.");
                }
            }
        }
        if (seen.size() != expected.size()) throw ApiException.badRequest("숙소·교통 고정 일정을 삭제할 수 없습니다.");
    }
}
