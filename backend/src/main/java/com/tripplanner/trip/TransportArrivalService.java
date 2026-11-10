package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.springframework.stereotype.Service;

/** Resolve before prompting and building fixed events. Never guess an unavailable route. */
@Service
public class TransportArrivalService {
    private final RouteTimeClient routes;
    private final TrainTimetableClient trains;
    public TransportArrivalService(RouteTimeClient routes) { this(routes,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public TransportArrivalService(RouteTimeClient routes,TrainTimetableClient trains) { this.routes = routes; this.trains=trains; }
    public java.util.List<TrainOption> trainOptions(Transport t,java.time.LocalDate date) {
        if(trains==null) throw ApiException.badRequest("기차 시간표 조회를 사용할 수 없어요. 승차권 시각을 직접 선택해 주세요.");
        return trains.options(t,date);
    }

    public Transport calculate(Transport t) { return calculate(t,null); }
    public Transport calculate(Transport t,java.time.LocalDate date) {
        if (t.originLocation() == null || t.arrivalLocation() == null)
            throw ApiException.badRequest("카카오맵에서 가는 편의 출발·도착 장소를 선택해 주세요.");
        check(t.originLocation()); check(t.arrivalLocation());
        int start = TravelSchedule.minute(t.originTime());
        if(t.mode()==TransportMode.KTX) {
            var options=trainOptions(t,date);
            var requested=t.train()!=null && java.util.Objects.equals(t.train().date(),date)?t.train():null;
            var selected=requested==null?options.get(0):options.stream().filter(o->o.id().equals(requested.id())).findFirst()
                    .orElseThrow(()->ApiException.badRequest("선택한 열차의 운행 정보를 다시 확인해 주세요. 출발 희망 시각·날짜·역이 변경되었을 수 있어요."));
            return new Transport(t.mode(),t.arrivalPlace(),selected.arrivalTime(),t.departurePlace(),t.departureTime(),
                    t.arrivalLocation(),t.departureLocation(),t.originLocation(),t.originTime(),true,selected,t.returnArrivalLocation(),t.returnTrain(),t.returnArrivalTime(),t.returnArrivalAuto()).withStations(t.boardingStation(),t.returnBoardingStation()).withAlightingStations(t.alightingStation(),t.returnAlightingStation());
        }
        if (t.mode() != TransportMode.CAR && t.mode() != TransportMode.BUS)
            throw ApiException.badRequest("기차는 승차권의 도착 시각을 직접 선택해 주세요.");
        var session = routes.session(t.mode() == TransportMode.CAR ? MovementMode.CAR : MovementMode.PUBLIC_TRANSIT);
        var duration = session.minutes(place(t.originLocation()), place(t.arrivalLocation()));
        if (duration.isEmpty() || duration.getAsInt() <= 0)
            throw ApiException.badRequest("이 구간의 이동 시간을 확인하지 못했어요. 도착 시각을 직접 선택해 주세요.");
        int arrival = start + duration.getAsInt();
        if (arrival >= 1440) throw ApiException.badRequest("다음 날 도착하는 교통편은 아직 지원하지 않아요. 당일 도착하는 출발 시각을 선택해 주세요.");
        return new Transport(t.mode(), t.arrivalPlace(), TravelSchedule.clock(arrival), t.departurePlace(), t.departureTime(),
                t.arrivalLocation(), t.departureLocation(), t.originLocation(), t.originTime(), true,null,t.returnArrivalLocation(),null,t.returnArrivalTime(),t.returnArrivalAuto()).withStations(t.boardingStation(),t.returnBoardingStation()).withAlightingStations(t.alightingStation(),t.returnAlightingStation());
    }
    GenerateRequest resolve(GenerateRequest r) {
        TravelDetails d = r.travelDetails();
        if (d == null || d.transport() == null) return r;
        if(!d.transport().arrivalAuto() && d.transport().returnArrivalLocation()==null) return r;
        Transport resolved = d.transport().arrivalAuto() ? calculate(d.transport(),r.startDate()) : d.transport();
        if(resolved.mode()==TransportMode.KTX && resolved.returnArrivalLocation()!=null && !Boolean.FALSE.equals(resolved.returnArrivalAuto())) {
            if(r.startDate()==null) throw ApiException.badRequest("여행 날짜를 선택해 주세요.");
            var date=r.startDate().plusDays(r.days()-1);
            var query=new Transport(TransportMode.KTX,resolved.returnArrivalLocation().name(),"00:00",resolved.departurePlace(),resolved.departureTime(),resolved.returnArrivalLocation(),null,resolved.departureLocation(),resolved.departureTime(),true).withStations(resolved.returnBoardingStation(),null).withAlightingStations(resolved.returnAlightingStation(),null);
            var choices=trainOptions(query,date);
            var requested=resolved.returnTrain();
            if(requested==null || !date.equals(requested.date())) throw ApiException.badRequest("오는 편 열차 시간표를 다시 조회해 주세요.");
            var selected=choices.stream().filter(o->o.id().equals(requested.id())).findFirst().orElseThrow(()->ApiException.badRequest("선택한 오는 편 열차를 다시 확인해 주세요."));
            resolved=new Transport(resolved.mode(),resolved.arrivalPlace(),resolved.arrivalTime(),resolved.departurePlace(),resolved.departureTime(),resolved.arrivalLocation(),resolved.departureLocation(),resolved.originLocation(),resolved.originTime(),resolved.arrivalAuto(),resolved.train(),resolved.returnArrivalLocation(),selected,selected.arrivalTime(),resolved.returnArrivalAuto()).withStations(resolved.boardingStation(),resolved.returnBoardingStation()).withAlightingStations(resolved.alightingStation(),resolved.returnAlightingStation());
        }
        if((resolved.mode()==TransportMode.CAR || resolved.mode()==TransportMode.BUS) && resolved.returnArrivalLocation()!=null && !Boolean.FALSE.equals(resolved.returnArrivalAuto())) {
            var query=new Transport(resolved.mode(),resolved.returnArrivalLocation().name(),"00:00",resolved.departurePlace(),resolved.departureTime(),resolved.returnArrivalLocation(),null,resolved.departureLocation(),resolved.departureTime(),true).withStations(resolved.returnBoardingStation(),null).withAlightingStations(resolved.returnAlightingStation(),null);
            var home=calculate(query);
            resolved=new Transport(resolved.mode(),resolved.arrivalPlace(),resolved.arrivalTime(),resolved.departurePlace(),resolved.departureTime(),resolved.arrivalLocation(),resolved.departureLocation(),resolved.originLocation(),resolved.originTime(),resolved.arrivalAuto(),resolved.train(),resolved.returnArrivalLocation(),null,home.arrivalTime(),resolved.returnArrivalAuto()).withStations(resolved.boardingStation(),resolved.returnBoardingStation()).withAlightingStations(resolved.alightingStation(),resolved.returnAlightingStation());
        }
        return new GenerateRequest(r.destination(),r.startDate(),r.days(),r.budget(),r.styles(),r.request(),
                new TravelDetails(d.movementMode(),d.stays(),resolved));
    }
    private static void check(SelectedPlace p) {
        if (p.latitude() == null || p.longitude() == null || !Double.isFinite(p.latitude()) || !Double.isFinite(p.longitude())
                || Math.abs(p.latitude()) > 90 || Math.abs(p.longitude()) > 180 || p.placeId() == null || !p.validId())
            throw ApiException.badRequest("출발·도착 장소를 카카오맵에서 다시 선택해 주세요.");
    }
    private static PlaceDraft place(SelectedPlace p) {
        return new PlaceDraft(p.placeId(),p.name(),p.address(),p.latitude(),p.longitude(),null,null,null,null,true,p.placeUrl(),null);
    }
}
