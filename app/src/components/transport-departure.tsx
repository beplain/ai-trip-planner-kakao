import { ItineraryDisclosure } from './itinerary-disclosure';
import { Text, View } from 'react-native';
import type { TravelDetails } from '../../services/trips';
import { C, s } from '@/lib/styles';

export function TransportDeparture({ value, dayNumber, color = C.primary }: {
  value?: TravelDetails; dayNumber: number; color?: string;
}) {
  const transport = value?.transport;
  if (dayNumber !== 1 || !transport?.originLocation || !transport.originTime) return null;
  const train = transport.mode === 'KTX' ? transport.train : undefined;
  const boarding = train && transport.arrivalAuto ? transport.boardingStation ?? transport.originLocation : transport.originLocation;
  const time = train?.departureTime ?? transport.originTime;
  const mode = { KTX: '기차', BUS: '버스·지하철', CAR: '자동차', PLANE: '비행기' }[transport.mode];
  return <View style={s.placeCard}>
    <View style={s.timeline}>
      <View style={[s.dot, { backgroundColor: color }]} />
      <View style={s.line} />
    </View>
    <ItineraryDisclosure name={boarding.name} summary={<>
      <Text style={[s.placeTime, { color }]}>{time} · {train ? '열차 출발' : '여행 출발'}</Text>
      <Text style={s.placeName}>{boarding.name}</Text>
      <Text style={s.placeAddr}>{boarding.address}</Text>
    </>}>
      <Text style={s.placeNote}>{train ? `${train.trainType} ${train.trainNo}` : mode} · {transport.arrivalPlace} 방면</Text>
      {train && boarding.placeId !== transport.originLocation.placeId && <Text style={s.placeNote}>출발 장소: {transport.originLocation.name} · 역까지 이동 시간 별도 확인</Text>}
    </ItineraryDisclosure>
  </View>;
}

export function TransportReturnArrival({ value, dayNumber, dayCount, color = C.primary }: {
  value?: TravelDetails; dayNumber: number; dayCount: number; color?: string;
}) {
  const transport = value?.transport;
  const place = transport?.mode === 'KTX' && transport.returnTrain && transport.returnArrivalAuto !== false ? transport.returnAlightingStation ?? transport.returnArrivalLocation : transport?.returnArrivalLocation;
  if (dayNumber !== dayCount || !transport || !place) return null;
  const train = transport.mode === 'KTX' && transport.returnArrivalAuto !== false ? transport.returnTrain : undefined;
  const time = train?.arrivalTime ?? transport.returnArrivalTime;
  const mode = { KTX: '기차', BUS: '버스·지하철', CAR: '자동차', PLANE: '비행기' }[transport.mode];
  return <View style={s.placeCard}>
    <View style={s.timeline}><View style={[s.dot, { backgroundColor: color }]} /></View>
    <ItineraryDisclosure name={place.name} summary={<>
      <Text style={[s.placeTime, { color }]}>{time ? `${time} · ` : ''}귀가 도착{time && !train && transport.returnArrivalAuto !== false ? ' (예상)' : ''}</Text>
      <Text style={s.placeName}>{place.name}</Text>
      <Text style={s.placeAddr}>{place.address}</Text>
    </>}>
      {train && transport.returnArrivalLocation && place.placeId !== transport.returnArrivalLocation.placeId && <Text style={s.placeNote}>도착 장소: {transport.returnArrivalLocation.name} · 역에서 이동 시간 별도 확인</Text>}
      <Text style={s.placeNote}>{train ? `${train.trainType} ${train.trainNo}` : mode}{!time ? ' · 도착 시각 조회 필요' : ''}</Text>
    </ItineraryDisclosure>
  </View>;
}
