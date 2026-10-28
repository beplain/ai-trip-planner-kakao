import { useState } from 'react';
import { Keyboard, Pressable, Text, View } from 'react-native';
import { TimeWheelInput as TimeField } from '@/components/time-wheel-input';
import { DestinationMapPicker } from '@/components/destination-map-picker';
import type { LuggagePlan, Stay, TravelDetails } from '../../services/trips';
import { tripEndDate, shortDate } from '@/lib/format';
import { s } from '@/lib/styles';

export const DEFAULT_TRAVEL_DETAILS: TravelDetails = { movementMode: 'PUBLIC_TRANSIT', stays: [] };
const LUGGAGE: [LuggagePlan, string][] = [['CARRY', '직접 소지'], ['HOTEL', '숙소 보관 가능'], ['LOCKER', '보관함 이용']];
export function travelDetailsError(value: TravelDetails | undefined, days: number): string | undefined {
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
    if (!valid(t.arrivalTime) || !valid(t.departureTime)) return '교통편 시간은 10:00처럼 24시간 형식으로 입력해 주세요.';
    if (days === 1 && t.arrivalTime >= t.departureTime) return '당일 여행의 출발 시각은 도착 시각보다 늦어야 합니다.';
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
  const [transportSelecting, setTransportSelecting] = useState<'arrival' | 'departure' | null>(null);
  const [selecting, setSelecting] = useState<number | null>(null);
  const details = value ?? DEFAULT_TRAVEL_DETAILS;
  const stays = details.stays ?? [];
  const dates: [number, string][] = Array.from({ length: Math.max(0, Math.min(7, days)) }, (_, i) => [i + 1, `${i + 1}일차${startDate ? ` ${shortDate(tripEndDate(startDate, i + 1))}` : ''}`]);
  function update(index: number, patch: Partial<Stay>) {
    onChange({ ...details, stays: stays.map((stay, i) => i === index ? { ...stay, ...patch } : stay) });
  }
  return <View>
    {selecting !== null && stays[selecting] && <DestinationMapPicker visible initialDestination={stays[selecting].name || `${destination ?? ''} 숙소`.trim()}
      onClose={() => setSelecting(null)} onSelect={() => undefined} onPlaceSelect={(place) => { update(selecting, place); setSelecting(null); }} />}
    {transportSelecting !== null && details.transport && <DestinationMapPicker visible placeKind="transport"
      initialDestination={(transportSelecting === 'arrival' ? details.transport.arrivalPlace : details.transport.departurePlace) || `${destination ?? ''} ${details.transport.mode === 'PLANE' ? '공항' : details.transport.mode === 'BUS' ? '터미널' : details.transport.mode === 'CAR' ? '' : '역'}`.trim()}
      onClose={() => setTransportSelecting(null)} onSelect={() => undefined} onPlaceSelect={(place) => {
        onChange({ ...details, transport: { ...details.transport!, ...(transportSelecting === 'arrival'
          ? { arrivalPlace: place.name, arrivalLocation: place } : { departurePlace: place.name, departureLocation: place }) } });
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
        onPress={() => { Keyboard.dismiss(); setSelecting(index); }}><Text style={s.outlineText}>{stay.name ? '숙소 변경 (카카오맵)' : '숙소 선택 (카카오맵)'}</Text></Pressable>
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
    <Text style={s.label}>여행지 도착·귀가 교통편</Text>
    <Pressable accessibilityRole="button" onPress={() => onChange({ ...details, transport: details.transport ? undefined : {
      mode: 'KTX', arrivalPlace: '', arrivalTime: '10:00', departurePlace: '', departureTime: '18:00',
    } })} style={s.outlineButton}><Text style={s.outlineText}>{details.transport ? '교통편 입력 해제' : '＋ 도착·출발 시각 입력'}</Text></Pressable>
    {details.transport && <View style={{ gap: 12 }}>
      <Choices value={details.transport.mode} options={[[ 'KTX', '기차' ], [ 'BUS', '버스·지하철' ], [ 'CAR', '자동차' ]]}
        onChange={(mode) => onChange({ ...details, transport: { ...details.transport!, mode } })} />
      <Text style={s.mutedSmall}>첫날 여행지 도착 장소</Text>
      <Pressable accessibilityRole="button" onPress={() => { Keyboard.dismiss(); setTransportSelecting('arrival'); }} style={[s.input, { justifyContent: 'center' }]}>
        <Text>{details.transport.arrivalPlace || '카카오맵에서 도착 장소 선택'} ⌄</Text>
      </Pressable>
      {!!details.transport.arrivalLocation && <Text style={s.mutedSmall}>{details.transport.arrivalLocation.address}</Text>}
      <TimeField label="첫날 여행지 도착 시각" value={details.transport.arrivalTime} onChange={(arrivalTime) => onChange({ ...details, transport: { ...details.transport!, arrivalTime } })} />
      <Text style={s.mutedSmall}>마지막 날 귀가 출발 장소</Text>
      <Pressable accessibilityRole="button" onPress={() => { Keyboard.dismiss(); setTransportSelecting('departure'); }} style={[s.input, { justifyContent: 'center' }]}>
        <Text>{details.transport.departurePlace || '카카오맵에서 귀가 출발 장소 선택'} ⌄</Text>
      </Pressable>
      {!!details.transport.departureLocation && <Text style={s.mutedSmall}>{details.transport.departureLocation.address}</Text>}
      <TimeField label="마지막 날 귀가 출발 시각" value={details.transport.departureTime} onChange={(departureTime) => onChange({ ...details, transport: { ...details.transport!, departureTime } })} />
    </View>}
    <Text style={[s.mutedSmall, { marginTop: 10 }]}>시간은 1시간·10분 간격으로 스크롤해 선택하세요. 이동 시간은 예상 여유 시간 기준이며 실시간 교통 정보와 다를 수 있어요.</Text>
  </View>;
}

export function TravelDetailsSummary({ value }: { value?: TravelDetails }) {
  if (!value) return null;
  return <View style={s.memberBox}>
    <Text style={s.sectionTitleSmall}>숙소·교통</Text>
    <Text style={s.memberNames}>{value.movementMode === 'CAR' ? '자동차 이동' : '대중교통 (버스·지하철) 이동'}</Text>
    {value.stays?.map((stay, index) => <Text key={index} style={s.memberNames}>{stay.name} · {stay.checkInDay}일차 {stay.checkInTime} 입실 ~ {stay.checkOutDay}일차 {stay.checkOutTime} 퇴실{'\n'}{stay.address}{'\n'}입실 전 짐: {LUGGAGE.find(([key]) => key === stay.beforeCheckIn)?.[1]} · 퇴실 후 짐: {LUGGAGE.find(([key]) => key === stay.afterCheckOut)?.[1]}</Text>)}
    {value.transport && <Text style={s.memberNames}>교통편: {({ KTX: '기차', PLANE: '비행기', BUS: '버스·지하철', CAR: '자동차' })[value.transport.mode]}{'\n'}첫날 {value.transport.arrivalTime} {value.transport.arrivalPlace} 도착{'\n'}마지막 날 {value.transport.departureTime} {value.transport.departurePlace} 출발</Text>}
  </View>;
}
