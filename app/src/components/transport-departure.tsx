import { Text, View } from 'react-native';
import type { TravelDetails } from '../../services/trips';
import { C, s } from '@/lib/styles';

export function TransportDeparture({ value, dayNumber, color = C.primary }: {
  value?: TravelDetails; dayNumber: number; color?: string;
}) {
  const transport = value?.transport;
  if (dayNumber !== 1 || !transport?.originLocation || !transport.originTime) return null;
  const train = transport.mode === 'KTX' ? transport.train : undefined;
  const time = train?.departureTime ?? transport.originTime;
  const mode = { KTX: '기차', BUS: '버스·지하철', CAR: '자동차', PLANE: '비행기' }[transport.mode];
  return <View style={s.placeCard}>
    <View style={s.timeline}>
      <View style={[s.dot, { backgroundColor: color }]} />
      <View style={s.line} />
    </View>
    <View style={s.placeBody}>
      <Text style={[s.placeTime, { color }]}>{time} · 여행 출발</Text>
      <Text style={s.placeName}>{transport.originLocation.name}</Text>
      <Text style={s.placeAddr}>{transport.originLocation.address}</Text>
      <Text style={s.placeNote}>{train ? `${train.trainType} ${train.trainNo}` : mode} · {transport.arrivalPlace} 방면</Text>
    </View>
  </View>;
}

export function TransportReturnArrival({ value, dayNumber, dayCount, color = C.primary }: {
  value?: TravelDetails; dayNumber: number; dayCount: number; color?: string;
}) {
  const transport = value?.transport;
  const place = transport?.returnArrivalLocation;
  if (dayNumber !== dayCount || !transport || !place) return null;
  const train = transport.mode === 'KTX' ? transport.returnTrain : undefined;
  const time = train?.arrivalTime ?? transport.returnArrivalTime;
  const mode = { KTX: '기차', BUS: '버스·지하철', CAR: '자동차', PLANE: '비행기' }[transport.mode];
  return <View style={s.placeCard}>
    <View style={s.timeline}><View style={[s.dot, { backgroundColor: color }]} /></View>
    <View style={s.placeBody}>
      <Text style={[s.placeTime, { color }]}>{time ? `${time} · ` : ''}귀가 도착{time && !train ? ' (예상)' : ''}</Text>
      <Text style={s.placeName}>{place.name}</Text>
      <Text style={s.placeAddr}>{place.address}</Text>
      <Text style={s.placeNote}>{train ? `${train.trainType} ${train.trainNo}` : mode}{!time ? ' · 도착 시각 조회 필요' : ''}</Text>
    </View>
  </View>;
}
