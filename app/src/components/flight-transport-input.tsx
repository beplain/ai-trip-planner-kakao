import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { tripsApi, type Airport, type FlightOption, type TravelDetails } from '../../services/trips';
import { apiError } from '../../services/api';
import { s } from '@/lib/styles';
import { selectFlight, validFlightSelection } from '@/lib/flight-selection';
import { TimeWheelInput } from './time-wheel-input';
import { DestinationMapPicker } from './destination-map-picker';

type Transport = NonNullable<TravelDetails['transport']>;
export function FlightTransportInput({ transport, startDate, returnDate, onChange }: {
  transport: Transport; startDate: string; returnDate: string; onChange: (patch: Partial<Transport>) => void;
}) {
  const [airports, setAirports] = useState<Airport[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  async function load() {
    setLoading(true); setError('');
    try { const rows = await tripsApi.airports(); if (alive.current) { setAirports(rows); if (!rows.length) setError('조회되는 공항이 없어요. 다시 조회해 주세요.'); } }
    catch (e) { if (alive.current) setError(apiError(e)); }
    finally { if (alive.current) setLoading(false); }
  }
  useEffect(() => {
    let cancelled = false;
    tripsApi.airports().then(rows => { if (!cancelled) { setAirports(rows); if (!rows.length) setError('조회되는 공항이 없어요. 다시 조회해 주세요.'); } })
      .catch(e => { if (!cancelled) setError(apiError(e)); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);
  return <View style={{ gap: 12 }}>
    {loading && <Text style={s.mutedSmall}>공항 목록을 불러오는 중…</Text>}
    {!!error && <><Text style={s.deleteText}>{error}</Text><Pressable accessibilityRole="button" style={s.outlineButton} onPress={load}><Text style={s.outlineText}>공항 목록 다시 조회</Text></Pressable></>}
    <FlightLeg key={`out:${startDate}`} returning={false} date={startDate} {...{ transport, airports, onChange }} />
    <FlightLeg key={`back:${returnDate}`} returning date={returnDate} {...{ transport, airports, onChange }} />
    <Text style={s.mutedSmall}>시간표 조회는 항공권 예매·잔여 좌석 확인이 아닙니다. 항공사에서 예약한 편명과 시각을 확인해 주세요.</Text>
    <Text style={s.mutedSmall}>일정에는 도착 후 60분, 귀가 항공편 출발 전 120분의 기본 여유 시간을 확보합니다. 렌터카 인수·반납과 공항 이동에 필요한 시간도 확인해 주세요.</Text>
  </View>;
}
function FlightLeg({ returning, date, transport: t, airports, onChange }: {
  returning: boolean; date: string; transport: Transport; airports: Airport[]; onChange: (patch: Partial<Transport>) => void;
}) {
  const from = returning ? t.departureLocation : t.originLocation;
  const to = returning ? t.returnArrivalLocation : t.arrivalLocation;
  const fromId = airports.find(a => a.name === from?.name)?.id;
  const toId = airports.find(a => a.name === to?.name)?.id;
  const auto = returning ? t.returnArrivalAuto !== false : !!t.arrivalAuto;
  const time = (returning ? t.departureTime : t.originTime) || (returning ? '20:00' : '08:00');
  const selected = returning ? t.returnFlight : t.flight;
  const context = JSON.stringify([date, fromId, toId, auto, time]);
  const latest = useRef(context);
  useEffect(() => { latest.current = context; }, [context]);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [selecting, setSelecting] = useState<'from' | 'to'>();
  const [mapSelecting, setMapSelecting] = useState<'from' | 'to'>();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ key: string; rows?: FlightOption[]; text?: string }>();
  const [locationError, setLocationError] = useState('');
  const clear = returning ? { returnFlight: undefined } : { flight: undefined };
  async function choose(airport: Airport) {
    if (busy) return;
    const side = selecting;
    const requestContext = context;
    setBusy(true); setLocationError('');
    try {
      const location = await tripsApi.airportLocation(airport.id);
      if (!alive.current || latest.current !== requestContext) return;
      const patch: Partial<Transport> = returning
        ? side === 'from' ? { departureLocation: location, departurePlace: location.name } : { returnArrivalLocation: location }
        : side === 'from' ? { originLocation: location } : { arrivalLocation: location, arrivalPlace: location.name };
      // Preselect the reverse route once; later edits remain independent.
      if (!returning && side === 'from' && !t.returnArrivalLocation) patch.returnArrivalLocation = location;
      if (!returning && side === 'to' && !t.departureLocation) { patch.departureLocation = location; patch.departurePlace = location.name; }
      onChange({ ...clear, ...patch, ...(patch.returnArrivalLocation || patch.departureLocation ? { returnFlight: undefined } : {}) });
      setSelecting(undefined);
    } catch (e) { if (alive.current && latest.current === requestContext) setLocationError(apiError(e)); }
    finally { if (alive.current) setBusy(false); }
  }
  async function lookup() {
    if (!fromId || !toId || !date || busy) return;
    const key = context;
    setBusy(true); setResult(undefined);
    try {
      const all = await tripsApi.flightOptions(date, fromId, toId);
      if (!alive.current || latest.current !== key) return;
      const rows = all.filter(f => f.departureTime >= time);
      setResult({ key, rows, text: rows.length ? undefined : all.length ? '출발 희망 시각 이후 항공편이 없어요. 희망 시각을 앞당겨 조회해 주세요.' : '선택한 날짜와 구간에 조회되는 항공편이 없어요. 공항·날짜를 변경하거나 예약한 항공편 시각을 직접 선택해 주세요.' });
    } catch (e) { if (alive.current && latest.current === key) setResult({ key, text: apiError(e) }); }
    finally { if (alive.current) setBusy(false); }
  }
  return <View style={{ gap: 8 }}>
    {!!mapSelecting && <DestinationMapPicker visible placeKind="transport" pickerTitle="항공 출발·도착 공항 선택"
      initialDestination={(mapSelecting === 'from' ? from : to)?.name || '공항'} onSelect={() => undefined} onClose={() => setMapSelecting(undefined)}
      onPlaceSelect={location => {
        const patch: Partial<Transport> = returning
          ? mapSelecting === 'from' ? { departureLocation: location, departurePlace: location.name } : { returnArrivalLocation: location }
          : mapSelecting === 'from' ? { originLocation: location } : { arrivalLocation: location, arrivalPlace: location.name };
        if (!returning && mapSelecting === 'from' && !t.returnArrivalLocation) patch.returnArrivalLocation = location;
        if (!returning && mapSelecting === 'to' && !t.departureLocation) { patch.departureLocation = location; patch.departurePlace = location.name; }
        onChange({ ...clear, ...patch, ...(patch.returnArrivalLocation || patch.departureLocation ? { returnFlight: undefined } : {}) });
        setMapSelecting(undefined);
      }} />}
    <Text style={s.sectionTitleSmall}>{returning ? '오는 편 · 마지막 날' : '가는 편 · 첫날'} {date.replace(/-/g, '/')}</Text>
    <View style={{ flexDirection: 'row', gap: 8 }}>
      {(['from', 'to'] as const).map(side => <Pressable key={side} disabled={busy || (auto && !airports.length)} accessibilityRole="button"
        style={[s.outlineButton, { flex: 1, minWidth: 0 }, (busy || (auto && !airports.length)) && s.disabled]} onPress={() => { if (auto) setSelecting(selecting === side ? undefined : side); else setMapSelecting(side); setLocationError(''); }}>
        <Text style={s.mutedSmall}>{side === 'from' ? '출발 공항' : '도착 공항'}</Text>
        <Text style={s.outlineText}>{(side === 'from' ? from : to)?.name || '공항 선택'} ⌄</Text>
      </Pressable>)}
    </View>
    {selecting && <View style={{ gap: 6 }}><Text style={s.mutedSmall}>{selecting === 'from' ? '출발' : '도착'} 공항 선택</Text>
      <ScrollView nestedScrollEnabled style={{ maxHeight: 210 }}>{airports.map(a => <Pressable key={a.id} disabled={busy} accessibilityRole="button" style={s.outlineButton} onPress={() => choose(a)}><Text style={s.outlineText}>{a.name}</Text></Pressable>)}</ScrollView>
      <Pressable disabled={busy} accessibilityRole="button" onPress={() => setSelecting(undefined)}><Text style={s.linkText}>닫기</Text></Pressable></View>}
    {!!locationError && <Text style={s.deleteText}>{locationError}</Text>}
    <View style={s.chips}>{[true, false].map(value => <Pressable key={String(value)} disabled={busy} accessibilityRole="button" accessibilityState={{ selected: auto === value }}
      style={[s.chip, auto === value && s.chipActive]} onPress={() => { setSelecting(undefined); onChange({ ...clear, ...(returning ? { returnArrivalAuto: value, returnArrivalTime: t.returnArrivalTime || '21:00' } : { arrivalAuto: value }) }); }}>
      <Text style={[s.chipText, auto === value && s.chipTextActive]}>{value ? '시간표에서 선택' : '예약 시각 직접 선택'}</Text></Pressable>)}</View>
    <TimeWheelInput disabled={busy} minuteInterval={auto ? 10 : 1} label={auto ? '출발 희망 시각' : '출발 시각'} value={time} onChange={value => onChange({ ...clear, ...(returning ? { departureTime: value } : { originTime: value }) })} />
    {auto ? <>
      <Pressable disabled={busy || !date || !fromId || !toId || fromId === toId} accessibilityRole="button" onPress={lookup}
        style={[s.primary, (busy || !date || !fromId || !toId || fromId === toId) && s.disabled]}><Text style={s.primaryText}>{busy ? '조회 중…' : '항공편 조회'}</Text></Pressable>
      {result?.key === context && !!result.text && <Text style={s.mutedSmall}>{result.text}</Text>}
      {result?.key === context && !!result.rows?.length && <ScrollView nestedScrollEnabled style={{ maxHeight: 260 }}>{result.rows.map(f => <Pressable key={f.id} accessibilityRole="button"
        style={[s.outlineButton, selected?.id === f.id && { backgroundColor: '#edf2ff' }]} onPress={() => { onChange(selectFlight(f, returning)); setResult(undefined); }}>
        <Text style={s.outlineText}>{f.airline} · {f.flightNo}</Text><Text style={s.memberNames}>{f.departureTime} 출발 → {f.arrivalTime} 도착</Text></Pressable>)}</ScrollView>}
      {selected && validFlightSelection(t, date, returning) && <Text style={s.sectionTitleSmall}>선택: {selected.airline} {selected.flightNo} · {selected.departureTime} → {selected.arrivalTime}</Text>}
      {selected && !validFlightSelection(t, date, returning) && <Text style={s.deleteText}>날짜 또는 공항이 변경됐어요. 항공편을 다시 선택해 주세요.</Text>}
    </> : <TimeWheelInput disabled={busy} label="도착 시각" minuteInterval={1} value={returning ? t.returnArrivalTime || '21:00' : t.arrivalTime} onChange={value => onChange({ ...clear, ...(returning ? { returnArrivalTime: value } : { arrivalTime: value }) })} />}
    <Text style={s.mutedSmall}>여행지 안에서의 이동 방식은 앞에서 선택한 자동차·대중교통 설정을 사용합니다.</Text>
  </View>;
}
