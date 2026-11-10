import { TrainStationChoices } from '@/components/train-station-choices';
import { useEffect, useRef, useState } from 'react';
import { Keyboard, Pressable, ScrollView, Text, View } from 'react-native';
import { TimeWheelInput as TimeField } from '@/components/time-wheel-input';
import { DestinationMapPicker } from '@/components/destination-map-picker';
import type { LuggagePlan, Stay, TravelDetails, TrainOption } from '../../services/trips';
import { tripEndDate, shortDate } from '@/lib/format';
import { s } from '@/lib/styles';
import { tripsApi } from '../../services/trips';
import { apiError } from '../../services/api';
import { patchTransport, transportContext, validTrainSelection, returnQuery, trainThreshold } from '@/lib/train-selection';

export const DEFAULT_TRAVEL_DETAILS: TravelDetails = { movementMode: 'CAR', stays: [] };
const LUGGAGE: [LuggagePlan, string][] = [['CARRY', '직접 소지'], ['HOTEL', '숙소 보관 가능'], ['LOCKER', '보관함 이용']];
export function travelDetailsError(value: TravelDetails | undefined, days: number, startDate = ''): string | undefined {
  if (!value) return;
  const valid = (t: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(t);
  const nights = new Set<number>();
  for (const stay of value.stays ?? []) {
    if (!stay.name.trim() || !stay.address.trim()) return '카카오맵에서 숙소를 선택해 주세요.';
    if (stay.checkInDay < 1 || stay.checkOutDay > days || stay.checkInDay >= stay.checkOutDay) return '숙소 입실·퇴실 날짜를 여행 기간 안에서 1박 이상으로 선택해 주세요.';
    if (!valid(stay.checkInTime) || !valid(stay.checkOutTime)) return '숙소 시간은 15:00처럼 24시간 형식으로 입력해 주세요.';
    for (let n = stay.checkInDay; n < stay.checkOutDay; n++) {
      if (nights.has(n)) return '같은 날짜에 숙소를 중복해서 예약할 수 없습니다. 입실·퇴실 날짜를 확인해 주세요.';
      nights.add(n);
    }
  }
  if (value.transport) {
    const t = value.transport;
    if (!t.arrivalPlace.trim() || !t.departurePlace.trim()) return '카카오맵에서 도착·출발 장소를 선택해 주세요.';
    if (!t.originLocation || !valid(t.originTime ?? '')) return '가는 편의 출발 장소와 시각을 선택해 주세요.';
    if ((!t.arrivalAuto && !valid(t.arrivalTime)) || !valid(t.departureTime)) return '교통편 시간은 10:00처럼 24시간 형식으로 입력해 주세요.';
    if (t.mode === 'KTX' && t.arrivalAuto && !validTrainSelection(t, startDate)) return '열차 시간표를 조회해 이용할 열차를 확인해 주세요.';
    if (t.mode === 'KTX' && t.returnArrivalAuto !== false && t.returnArrivalLocation && !validTrainSelection(returnQuery(t), startDate ? tripEndDate(startDate, days) : '')) return '오는 편 열차 시간표를 조회해 이용할 열차를 확인해 주세요.';
    if (t.returnArrivalAuto === false && (!t.returnArrivalLocation || !valid(t.returnArrivalTime ?? '') || t.returnArrivalTime! <= t.departureTime)) return '오는 편 도착 장소와 출발 시각보다 늦은 도착 시각을 선택해 주세요.';
    if (!t.arrivalAuto && t.originTime! >= t.arrivalTime) return '도착 시각은 가는 편 출발 시각보다 늦어야 합니다.';
    if (!t.arrivalAuto && days === 1 && t.arrivalTime >= t.departureTime) return '당일 여행의 출발 시각은 도착 시각보다 늦어야 합니다.';
  }
}

function Choices<T extends string | number>({ value, options, onChange }: {
  value: T; options: [T, string][]; onChange: (v: T) => void;
}) {
  return <View style={s.chips}>{options.map(([v, label]) => <Pressable key={v} accessibilityRole="button" accessibilityState={{ selected: value === v }}
    onPress={() => onChange(v)} style={[s.chip, value === v && s.chipActive]}>
    <Text style={[s.chipText, value === v && s.chipTextActive]}>{label}</Text>
  </Pressable>)}</View>;
}
export function TravelDetailsInput({ value, onChange, startDate, days, destination }: {
  value?: TravelDetails; onChange: (value: TravelDetails) => void; startDate: string; days: number; destination?: string;
}) {
  const [transportSelecting, setTransportSelecting] = useState<'origin' | 'arrival' | 'departure' | 'returnArrival' | null>(null);
  const [selecting, setSelecting] = useState<number | null>(null);
  const details = value ?? DEFAULT_TRAVEL_DETAILS;
  const latest = useRef(details);
  useEffect(() => { latest.current = details; }, [details]);
  const latestDate = useRef(startDate);
  useEffect(() => { latestDate.current = startDate; }, [startDate]);
  const returnDate = startDate ? tripEndDate(startDate, days) : '';
  const latestReturnDate = useRef(returnDate);
  useEffect(() => { latestReturnDate.current = returnDate; }, [returnDate]);
  const [returnChecking, setReturnChecking] = useState(false);
  const [returnResult, setReturnResult] = useState<{ key: string; text?: string; arrivalTime?: string; trains?: TrainOption[]; candidate?: TrainOption; closed?: boolean }>();
  const returnKey = details.transport ? transportContext(returnQuery(details.transport), returnDate) : '';
  const [checking, setChecking] = useState(false);
  const [routeResult, setRouteResult] = useState<{ key: string; text: string; arrivalTime?: string; trains?: TrainOption[]; candidate?: TrainOption; closed?: boolean }>();
  const routeKey = transportContext(details.transport, startDate);
  const outboundOpen = details.transport?.mode === 'KTX' && routeResult?.key === routeKey && !!routeResult.trains?.length && !routeResult.closed;
  const returnOpen = details.transport?.returnArrivalAuto !== false && returnResult?.key === returnKey && !!returnResult.trains?.length && !returnResult.closed;
  function confirmTrain(returning: boolean) {
    const result = returning ? returnResult : routeResult;
    const now = latest.current;
    if (!now.transport || !result?.candidate) return;
    const next = { ...now, transport: { ...now.transport, ...(returning ? { returnTrain: result.candidate, returnArrivalTime: result.candidate.arrivalTime } : { train: result.candidate, arrivalTime: result.candidate.arrivalTime }) } };
    latest.current = next; onChange(next);
    if (returning) setReturnResult({ ...result, closed: true });
    else setRouteResult({ ...routeResult!, closed: true });
  }
  function changeTransport(patch: Partial<NonNullable<TravelDetails['transport']>>) {
    if (details.transport) onChange({ ...details, transport: patchTransport(details.transport, patch) });
  }
  async function checkArrival() {
    const current = details.transport;
    if (!current) return;
    setChecking(true);
    try {
      if (current.mode === 'KTX') {
        const result = await tripsApi.recommendTrainStation(current, startDate);
        const now = latest.current;
        if (now.transport && transportContext(now.transport, latestDate.current) === routeKey) {
          const next = { ...now, transport: { ...now.transport, boardingStation: result.station } };
          latest.current = next; onChange(next);
          setRouteResult({ key: transportContext(next.transport, startDate), text: '', trains: result.trains, candidate: result.trains.find(t => t.id === now.transport?.train?.id) ?? result.trains[0] });
        }
        return;
      }
      const result = await tripsApi.transportArrival(current);
      const now = latest.current;
      setRouteResult({ key: routeKey, text: `예상 도착 ${result.arrivalTime}`, arrivalTime: result.arrivalTime });
      if (transportContext(now.transport, latestDate.current) === routeKey) {
        const next = { ...now, transport: { ...now.transport!, arrivalTime: result.arrivalTime, train: undefined } };
        latest.current = next; onChange(next);
      }
    } catch (error) { setRouteResult({ key: routeKey, text: apiError(error) }); }
    finally { setChecking(false); }
  }
  async function checkReturn() {
    const current = details.transport;
    if (!current) return;
    setReturnChecking(true);
    try {
      const recommendation = current.mode === 'KTX' ? await tripsApi.recommendTrainStation(returnQuery(current), returnDate) : undefined;
      const trains = recommendation?.trains;
      const road = trains ? undefined : await tripsApi.transportArrival(returnQuery(current));
      const now = latest.current;
      if (now.transport && transportContext(returnQuery(now.transport), latestReturnDate.current) === returnKey) {
        if (trains && recommendation) {
          const next = { ...now, transport: { ...now.transport, returnBoardingStation: recommendation.station } };
          latest.current = next; onChange(next);
          setReturnResult({ key: transportContext(returnQuery(next.transport), returnDate), trains, candidate: trains.find(t => t.id === now.transport?.returnTrain?.id) ?? trains[0] });
        }
        else if (road) {
          setReturnResult({ key: returnKey, text: `예상 도착 ${road.arrivalTime}`, arrivalTime: road.arrivalTime });
          const next = { ...now, transport: { ...now.transport, returnArrivalTime: road.arrivalTime } };
          latest.current = next; onChange(next);
        }
      }
    } catch (error) { setReturnResult({ key: returnKey, text: apiError(error) }); }
    finally { setReturnChecking(false); }
  }
  const stays = details.stays ?? [];
  const dates: [number, string][] = Array.from({ length: Math.max(0, Math.min(7, days)) }, (_, i) => [i + 1, `${i + 1}일차${startDate ? ` ${shortDate(tripEndDate(startDate, i + 1))}` : ''}`]);
  function update(index: number, patch: Partial<Stay>) {
    onChange({ ...details, stays: stays.map((stay, i) => i === index ? { ...stay, ...patch } : stay) });
  }
  return <View>
    {selecting !== null && stays[selecting] && <DestinationMapPicker visible initialDestination={stays[selecting].name || `${destination ?? ''} 숙소`.trim()}
      onClose={() => setSelecting(null)} onSelect={() => undefined} onPlaceSelect={(place) => { update(selecting, place); setSelecting(null); }} />}
    {transportSelecting !== null && details.transport && <DestinationMapPicker visible placeKind="transport"
      initialDestination={(transportSelecting === 'origin' ? details.transport.originLocation?.name : transportSelecting === 'arrival' ? details.transport.arrivalPlace : transportSelecting === 'returnArrival' ? details.transport.returnArrivalLocation?.name : details.transport.departurePlace) || `${destination ?? ''} ${details.transport.mode === 'PLANE' ? '공항' : details.transport.mode === 'BUS' ? '터미널' : details.transport.mode === 'CAR' ? '' : '역'}`.trim()}
      onClose={() => setTransportSelecting(null)} onSelect={() => undefined} onPlaceSelect={(place) => {
        changeTransport({ ...(transportSelecting === 'origin' ? { originLocation: place } : transportSelecting === 'arrival'
          ? { arrivalPlace: place.name, arrivalLocation: place } : transportSelecting === 'returnArrival' ? { returnArrivalLocation: place } : { departurePlace: place.name, departureLocation: place }) });
        setTransportSelecting(null);
      }} />}
    <Text style={s.label}>여행 중 이동 방식</Text>
    <Choices value={details.movementMode} options={[[ 'CAR', '자동차' ], [ 'PUBLIC_TRANSIT', '대중교통 (버스·지하철)' ]]}
      onChange={(movementMode) => onChange({ ...details, movementMode })} />
    <Text style={s.label}>숙소</Text>
    <Text style={s.mutedSmall}>카카오맵에서 숙소를 검색하고 선택하세요. 숙소가 바뀌면 추가해 날짜를 나눌 수 있어요.</Text>
    {stays.map((stay, index) => <View key={index} style={{ marginTop: 14, padding: 14, gap: 10, borderWidth: 1, borderColor: '#dce2ee', borderRadius: 16, backgroundColor: '#fff' }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}><Text style={s.sectionTitleSmall}>숙소 {index + 1}</Text>
        <Pressable accessibilityRole="button" onPress={() => onChange({ ...details, stays: stays.filter((_, i) => i !== index) })} style={s.smallButton}><Text style={s.deleteText}>제거</Text></Pressable>
      </View>
      {!!stay.name && <View><Text style={s.sectionTitleSmall}>{stay.name}</Text><Text style={s.mutedSmall}>{stay.address}</Text></View>}
      <Pressable accessibilityRole="button" accessibilityLabel={`숙소 ${index + 1} 카카오맵에서 선택`} style={s.outlineButton}
        onPress={() => { Keyboard.dismiss(); setSelecting(index); }}><Text style={s.outlineText}>{stay.name ? '숙소 변경' : '숙소 선택'}</Text></Pressable>
      <Text style={s.mutedSmall}>입실 날짜</Text><Choices value={stay.checkInDay} options={dates} onChange={(checkInDay) => update(index, { checkInDay })} />
      <Text style={s.mutedSmall}>퇴실 날짜</Text><Choices value={stay.checkOutDay} options={dates} onChange={(checkOutDay) => update(index, { checkOutDay })} />
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <TimeField label="체크인 예정 시각" value={stay.checkInTime} onChange={(checkInTime) => update(index, { checkInTime })} />
        <TimeField label="체크아웃 시각" value={stay.checkOutTime} onChange={(checkOutTime) => update(index, { checkOutTime })} />
      </View>
      <Text style={s.mutedSmall}>체크인 전 짐</Text><Choices value={stay.beforeCheckIn} options={LUGGAGE} onChange={(beforeCheckIn) => update(index, { beforeCheckIn })} />
      <Text style={s.mutedSmall}>체크아웃 후 짐</Text><Choices value={stay.afterCheckOut} options={LUGGAGE} onChange={(afterCheckOut) => update(index, { afterCheckOut })} />
      <Text style={s.mutedSmall}>숙소 보관 가능 여부는 예약처에 확인해 주세요. 보관함은 실제 위치·운영 시간·빈자리를 확인해야 해요.</Text>
    </View>)}
    <Pressable accessibilityRole="button" disabled={days < 2 || stays.length >= 6} style={[s.outlineButton, (days < 2 || stays.length >= 6) && s.disabled]}
      onPress={() => onChange({ ...details, stays: [...stays, { name: '', address: '', checkInDay: 1, checkOutDay: days,
        checkInTime: '15:00', checkOutTime: '11:00', beforeCheckIn: 'CARRY', afterCheckOut: 'CARRY' }] })}>
      <Text style={s.outlineText}>＋ 숙소 추가</Text>
    </Pressable>
    {days < 2 && <Text style={s.mutedSmall}>2일 이상의 여행 기간을 선택하면 숙소를 추가할 수 있어요.</Text>}
    <Text style={s.label}>교통편</Text>
    <Pressable accessibilityRole="button" onPress={() => onChange({ ...details, transport: details.transport ? undefined : {
      mode: 'KTX', originTime: '08:00', arrivalAuto: true, returnArrivalAuto: true, arrivalPlace: '', arrivalTime: '10:00', departurePlace: '', departureTime: '18:00',
    } })} style={s.outlineButton}><Text style={s.outlineText}>{details.transport ? '교통편 입력 해제' : '＋ 교통편 추가'}</Text></Pressable>
    {details.transport && <View style={{ gap: 10, padding: 12, borderRadius: 16, backgroundColor: '#fff' }}>
      <Choices value={details.transport.mode} options={[[ 'KTX', '기차' ], [ 'BUS', '버스·지하철' ], [ 'CAR', '자동차' ]]}
        onChange={(mode) => changeTransport({ mode, arrivalAuto: true, returnArrivalAuto: true })} />
      <Text style={s.sectionTitleSmall}>가는 편 · 첫날</Text>
      <View style={{ flexDirection: 'row', gap: 10, alignItems: 'flex-end' }}>
        <View style={{ flex: 2, minWidth: 0, gap: 6 }}><Text style={s.mutedSmall}>출발 장소</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="가는 편 출발 장소 선택" onPress={() => { Keyboard.dismiss(); setTransportSelecting('origin'); }} style={[s.input, { justifyContent: 'center' }]}>
            <Text numberOfLines={1}>{details.transport.originLocation?.name || '지도에서 선택'} ⌄</Text>
            {!!details.transport.originLocation?.address && <Text numberOfLines={1} style={s.mutedSmall}>{details.transport.originLocation.address}</Text>}
          </Pressable></View>
        <TimeField label={details.transport.mode === 'KTX' && details.transport.arrivalAuto ? '열차 출발 희망 시각' : '출발 시각'} value={details.transport.originTime ?? '08:00'} onChange={(originTime) => changeTransport({ originTime })} />
      </View>
      {details.transport.mode === 'KTX' && <TrainStationChoices origin={details.transport.originLocation} destination={details.transport.arrivalLocation}
        value={details.transport.boardingStation} disabled={checking} onChange={boardingStation => changeTransport({ boardingStation })} />}
      <View style={{ flexDirection: 'row', gap: 10, alignItems: 'flex-end' }}>
        <View style={{ flex: 2, minWidth: 0, gap: 6 }}><Text style={s.mutedSmall}>도착 장소</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="가는 편 도착 장소 선택" onPress={() => { Keyboard.dismiss(); setTransportSelecting('arrival'); }} style={[s.input, { justifyContent: 'center' }]}>
            <Text numberOfLines={1}>{details.transport.arrivalPlace || '지도에서 선택'} ⌄</Text>
            {!!details.transport.arrivalLocation?.address && <Text numberOfLines={1} style={s.mutedSmall}>{details.transport.arrivalLocation.address}</Text>}
          </Pressable></View>
        {!details.transport.arrivalAuto && <TimeField label="도착 시각" value={details.transport.arrivalTime} onChange={(arrivalTime) => onChange({ ...details, transport: { ...details.transport!, arrivalTime } })} />}
      </View>
      {details.transport.mode === 'KTX' && <TrainStationChoices kind="도착역" origin={details.transport.arrivalLocation} destination={details.transport.boardingStation}
        value={details.transport.alightingStation} disabled={checking} onChange={alightingStation => changeTransport({ alightingStation })} />}
      {details.transport.mode !== 'PLANE' && <Choices value={details.transport.arrivalAuto ? 'auto' : 'manual'} options={[[ 'auto', details.transport.mode === 'KTX' ? '시간표로 자동 확인' : '도착 시각 자동 계산' ], [ 'manual', '직접 선택' ]]}
        onChange={(method) => changeTransport({ arrivalAuto: method === 'auto' })} />}
      {details.transport.arrivalAuto ? <View style={{ gap: 6 }}>
        <Pressable accessibilityRole="button" disabled={checking || !details.transport.originLocation || !details.transport.arrivalLocation || (details.transport.mode === 'KTX' && (!startDate || !details.transport.boardingStation || (!details.transport.alightingStation && !details.transport.arrivalLocation?.name.split(/\s+/)[0].endsWith('역'))))}
          style={[s.primary, { marginBottom: 0, marginTop: 4 }, (checking || !details.transport.originLocation || !details.transport.arrivalLocation || (details.transport.mode === 'KTX' && (!startDate || !details.transport.boardingStation || (!details.transport.alightingStation && !details.transport.arrivalLocation?.name.split(/\s+/)[0].endsWith('역'))))) && s.disabled]} onPress={outboundOpen ? () => confirmTrain(false) : checkArrival}>
          <Text style={s.primaryText}>{checking ? '조회 중…' : details.transport.mode === 'KTX' ? outboundOpen ? '선택' : '열차 시간표 조회' : '예상 도착 시각 확인'}</Text>
        </Pressable>
        {routeResult?.key === routeKey && !routeResult.trains && <Text style={routeResult.arrivalTime ? s.sectionTitleSmall : s.mutedSmall}>{routeResult.text}</Text>}
        {outboundOpen && routeResult?.trains && <ScrollView nestedScrollEnabled style={{ maxHeight: 220 }}>
          {routeResult.trains.map((train) => <Pressable key={train.id} accessibilityRole="button" disabled={checking} accessibilityState={{ selected: routeResult.candidate?.id === train.id }}
            onPress={() => setRouteResult({ ...routeResult, candidate: train })}
            style={[s.outlineButton, { marginTop: 6, paddingVertical: 10 }, routeResult.candidate?.id === train.id && { backgroundColor: '#edf2ff' }]}>
            <Text style={s.outlineText}>{train.trainType} {train.trainNo} · {train.departureTime} → {train.arrivalTime}</Text>
          </Pressable>)}
        </ScrollView>}
        {details.transport.mode === 'KTX' && details.transport.train && validTrainSelection(details.transport, startDate) && <Text style={s.sectionTitleSmall}>
          {details.transport.train.trainType} {details.transport.train.trainNo} · {details.transport.train.departureTime} 출발 → {details.transport.train.arrivalTime} 도착
        </Text>}
        <Text style={s.mutedSmall}>{details.transport.mode === 'KTX' ? '열차를 고른 뒤 위의 선택 버튼으로 확정해 주세요. 시간표 조회는 예매·좌석 확인이 아닙니다.' : details.transport.mode === 'CAR' ? '현재 시간 기준 예상치이며 실제 출발일과 다를 수 있어요.' : '조회한 이동 시간에 출발 시각을 더해 첫날 일정을 시작해요. 실제 운행 시간은 달라질 수 있어요.'}</Text>
      </View> : <Text style={s.mutedSmall}>{details.transport.mode === 'KTX' ? '승차권의 도착 시각을 선택해 주세요.' : '운행 정보에서 확인한 도착 시각을 선택해 주세요.'}</Text>}
      <Text style={[s.sectionTitleSmall, { marginTop: 4 }]}>오는 편 · 마지막 날</Text>
      <View style={{ flexDirection: 'row', gap: 10, alignItems: 'flex-end' }}>
        <View style={{ flex: 2, minWidth: 0, gap: 6 }}><Text style={s.mutedSmall}>귀가 출발 장소</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="오는 편 출발 장소 선택" onPress={() => { Keyboard.dismiss(); setTransportSelecting('departure'); }} style={[s.input, { justifyContent: 'center' }]}>
            <Text numberOfLines={1}>{details.transport.departurePlace || '지도에서 선택'} ⌄</Text>
            {!!details.transport.departureLocation?.address && <Text numberOfLines={1} style={s.mutedSmall}>{details.transport.departureLocation.address}</Text>}
          </Pressable></View>
        <TimeField label={details.transport.mode === 'KTX' && details.transport.returnArrivalAuto !== false ? '열차 출발 희망 시각' : '출발 시각'} value={details.transport.departureTime} onChange={(departureTime) => changeTransport({ departureTime })} />
      </View>
      {details.transport.mode === 'KTX' && <TrainStationChoices origin={details.transport.departureLocation} destination={details.transport.returnArrivalLocation}
        value={details.transport.returnBoardingStation} disabled={returnChecking} onChange={returnBoardingStation => changeTransport({ returnBoardingStation })} />}
      {details.transport.mode !== 'PLANE' && <View style={{ gap: 6 }}>
        <View style={{ flexDirection: 'row', gap: 10, alignItems: 'flex-end' }}>
          <View style={{ flex: 2, minWidth: 0, gap: 6 }}><Text style={s.mutedSmall}>도착 장소</Text>
            <Pressable accessibilityRole="button" accessibilityLabel="오는 편 도착 장소 선택" onPress={() => { Keyboard.dismiss(); setTransportSelecting('returnArrival'); }} style={[s.input, { justifyContent: 'center' }]}>
              <Text numberOfLines={1}>{details.transport.returnArrivalLocation?.name || '지도에서 선택'} ⌄</Text>
            {!!details.transport.returnArrivalLocation?.address && <Text numberOfLines={1} style={s.mutedSmall}>{details.transport.returnArrivalLocation.address}</Text>}
            </Pressable>
          </View>
          {details.transport.returnArrivalAuto === false && <TimeField label="도착 시각" value={details.transport.returnArrivalTime ?? '20:00'} onChange={(returnArrivalTime) => changeTransport({ returnArrivalTime })} />}
        </View>
        {details.transport.mode === 'KTX' && <TrainStationChoices kind="도착역" origin={details.transport.returnArrivalLocation} destination={details.transport.returnBoardingStation}
          value={details.transport.returnAlightingStation} disabled={returnChecking} onChange={returnAlightingStation => changeTransport({ returnAlightingStation })} />}
        <Choices value={details.transport.returnArrivalAuto !== false ? 'auto' : 'manual'} options={[[ 'auto', details.transport.mode === 'KTX' ? '시간표로 자동 확인' : '도착 시각 자동 계산' ], [ 'manual', '직접 선택' ]]}
          onChange={(method) => changeTransport({ returnArrivalAuto: method === 'auto', ...(method === 'manual' ? { returnArrivalTime: details.transport?.returnArrivalTime ?? '20:00' } : {}) })} />
        {details.transport.returnArrivalAuto !== false ? <View style={{ gap: 6 }}>
        <Pressable accessibilityRole="button" disabled={returnChecking || (details.transport.mode === 'KTX' && (!returnDate || !details.transport.returnBoardingStation || (!details.transport.returnAlightingStation && !details.transport.returnArrivalLocation?.name.split(/\s+/)[0].endsWith('역')))) || !details.transport.departureLocation || !details.transport.returnArrivalLocation}
          style={[s.primary, { marginBottom: 0, marginTop: 4 }, (returnChecking || (details.transport.mode === 'KTX' && (!returnDate || !details.transport.returnBoardingStation || (!details.transport.returnAlightingStation && !details.transport.returnArrivalLocation?.name.split(/\s+/)[0].endsWith('역')))) || !details.transport.departureLocation || !details.transport.returnArrivalLocation) && s.disabled]} onPress={returnOpen ? () => confirmTrain(true) : checkReturn}>
          <Text style={s.primaryText}>{returnChecking ? '조회 중…' : details.transport.mode === 'KTX' ? returnOpen ? '선택' : '열차 시간표 조회' : '예상 도착 시각 확인'}</Text>
        </Pressable>
        {details.transport.mode === 'KTX' && <Text style={s.mutedSmall}>여행 마지막 날 {returnDate} · {trainThreshold(details.transport.departureTime)}부터 조회해요. 선택한 열차의 실제 출발 시각에 맞춰 마지막 날 일정을 마무리해요.</Text>}
        {returnResult?.key === returnKey && returnResult.text && <Text style={returnResult.arrivalTime ? s.sectionTitleSmall : s.mutedSmall}>{returnResult.text}</Text>}
        {details.transport.mode !== 'KTX' && details.transport.returnArrivalTime && returnResult?.key !== returnKey && <Text style={s.sectionTitleSmall}>예상 도착 {details.transport.returnArrivalTime}</Text>}
        {details.transport.mode !== 'KTX' && <Text style={s.mutedSmall}>출발 시각에 조회한 이동 시간을 더한 예상 시각이에요. 여행 날짜별 운행 시간표를 확인한 결과는 아닙니다.</Text>}
        {returnOpen && returnResult?.trains && <ScrollView nestedScrollEnabled style={{ maxHeight: 220 }}>
          {returnResult.trains.map((train) => <Pressable key={train.id} accessibilityRole="button" disabled={returnChecking} accessibilityState={{ selected: returnResult.candidate?.id === train.id }}
            onPress={() => setReturnResult({ ...returnResult, candidate: train })} style={[s.outlineButton, { marginTop: 6, paddingVertical: 10 }, returnResult.candidate?.id === train.id && { backgroundColor: '#edf2ff' }]}>
            <Text style={s.outlineText}>{train.trainType} {train.trainNo} · {train.departureTime} → {train.arrivalTime}</Text>
          </Pressable>)}
        </ScrollView>}
        {details.transport.mode === 'KTX' && details.transport.returnTrain && validTrainSelection(returnQuery(details.transport), returnDate) && <Text style={s.sectionTitleSmall}>
          {details.transport.returnTrain.trainType} {details.transport.returnTrain.trainNo} · {details.transport.returnTrain.departureTime} 출발 → {details.transport.returnTrain.arrivalTime} 도착
        </Text>}
        </View> : <Text style={s.mutedSmall}>{details.transport.mode === 'KTX' ? '승차권의 도착 시각을 선택해 주세요.' : '운행 정보에서 확인한 도착 시각을 선택해 주세요.'}</Text>}
      </View>}
    </View>}
    <Text style={[s.mutedSmall, { marginTop: 10 }]}>시간은 1시간·10분 간격으로 스크롤해 선택하세요. 이동 시간은 예상 여유 시간 기준이며 실시간 교통 정보와 다를 수 있어요.</Text>
  </View>;
}

export function TravelDetailsSummary({ value }: { value?: TravelDetails }) {
  if (!value) return null;
  const t = value.transport;
  const boarding = t?.mode === 'KTX' && t.train && t.arrivalAuto ? t.boardingStation ?? t.originLocation : t?.originLocation;
  const returnBoarding = t?.mode === 'KTX' && t.returnTrain && t.returnArrivalAuto !== false ? t.returnBoardingStation ?? t.departureLocation : t?.departureLocation;
  return <View style={s.memberBox}>
    <Text style={s.sectionTitleSmall}>숙소·교통</Text>
    <Text style={s.memberNames}>{value.movementMode === 'CAR' ? '자동차 이동' : value.movementMode === 'WALK' ? '도보 이동' : '대중교통 (버스·지하철) 이동'}</Text>
    {value.stays?.map((stay, index) => <Text key={index} style={s.memberNames}>{stay.name} · {stay.checkInDay}일차 {stay.checkInTime} 입실 ~ {stay.checkOutDay}일차 {stay.checkOutTime} 퇴실{'\n'}{stay.address}{'\n'}입실 전 짐: {LUGGAGE.find(([key]) => key === stay.beforeCheckIn)?.[1]} · 퇴실 후 짐: {LUGGAGE.find(([key]) => key === stay.afterCheckOut)?.[1]}</Text>)}
    {value.transport && <Text style={s.memberNames}>교통편: {value.transport.train ? `${value.transport.train.trainType} ${value.transport.train.trainNo}` : ({ KTX: '기차', PLANE: '비행기', BUS: '버스·지하철', CAR: '자동차' })[value.transport.mode]}{'\n'}{value.transport.originLocation && <>첫날 {value.transport.train?.departureTime ?? value.transport.originTime} {boarding?.name} 출발{'\n'}</>}첫날 {value.transport.arrivalTime} {value.transport.mode === 'KTX' && value.transport.train ? value.transport.alightingStation?.name ?? value.transport.arrivalPlace : value.transport.arrivalPlace} 도착{'\n'}마지막 날 {value.transport.mode === 'KTX' ? value.transport.returnTrain?.departureTime ?? value.transport.departureTime : value.transport.departureTime} {returnBoarding?.name ?? value.transport.departurePlace} 출발{value.transport.mode === 'KTX' && value.transport.returnTrain && ` → ${value.transport.returnTrain.arrivalTime} ${value.transport.returnTrain.arrivalStation} 도착 (${value.transport.returnTrain.trainType} ${value.transport.returnTrain.trainNo})`}{(!value.transport.returnTrain || value.transport.mode !== 'KTX') && value.transport.returnArrivalLocation && value.transport.returnArrivalTime && ` → ${value.transport.returnArrivalTime} ${value.transport.returnArrivalLocation.name} ${value.transport.returnArrivalAuto === false ? '도착' : '예상 도착'}`}</Text>}
    {t?.alightingStation && t.arrivalLocation && t.alightingStation.placeId !== t.arrivalLocation.placeId && <Text style={s.mutedSmall}>가는 편 도착 장소: {t.arrivalLocation.name} · 역에서 이동 시간 별도 확인</Text>}
    {t?.returnAlightingStation && t.returnArrivalLocation && t.returnAlightingStation.placeId !== t.returnArrivalLocation.placeId && <Text style={s.mutedSmall}>오는 편 도착 장소: {t.returnArrivalLocation.name} · 역에서 이동 시간 별도 확인</Text>}
    {t?.boardingStation && t.originLocation && t.boardingStation.placeId !== t.originLocation.placeId && <Text style={s.mutedSmall}>가는 편 출발 장소: {t.originLocation.name} · 역까지 이동 시간 별도 확인</Text>}
    {t?.returnBoardingStation && t.departureLocation && t.returnBoardingStation.placeId !== t.departureLocation.placeId && <Text style={s.mutedSmall}>오는 편 출발 장소: {t.departureLocation.name} · 역까지 이동 시간 별도 확인</Text>}
  </View>;
}
