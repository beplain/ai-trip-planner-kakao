import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { tripsApi, type Airport, type FlightOption, type SelectedPlace, type TravelDetails } from '../../services/trips';
import { apiError } from '../../services/api';
import { s } from '@/lib/styles';
import { selectFlight, validFlightSelection } from '@/lib/flight-selection';
import { TimeWheelInput } from './time-wheel-input';
import { SingleLineText } from './single-line-text';
import { DestinationMapPicker } from './destination-map-picker';
type Transport = NonNullable<TravelDetails['transport']>;

export function FlightTransportInput({ transport, startDate, returnDate, destination, onChange }: {
  transport: Transport; startDate: string; returnDate: string; destination?: string; onChange: (patch: Partial<Transport>) => void;
}) {
  const [airports, setAirports] = useState<Airport[]>([]);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    tripsApi.airports().then(rows => { if (active) { setAirports(rows); setError(rows.length ? '' : '조회되는 공항이 없어요. 다시 조회해 주세요.'); } })
      .catch(e => { if (active) setError(apiError(e)); });
    return () => { active = false; };
  }, [retry]);
  return <View style={{ gap: 12 }}>
    {!airports.length && !error && <Text style={s.mutedSmall}>공항 목록을 불러오는 중…</Text>}
    {!!error && <><Text style={s.warnText}>{error}</Text><Pressable accessibilityRole="button" style={s.outlineButton} onPress={() => { setError(''); setRetry(n => n + 1); }}><Text style={s.outlineText}>공항 목록 다시 조회</Text></Pressable></>}
    <FlightLeg key={`out:${startDate}`} returning={false} date={startDate} {...{ transport, airports, destination, onChange }} />
    <FlightLeg key={`back:${returnDate}`} returning date={returnDate} {...{ transport, airports, destination, onChange }} />
    <Text style={s.mutedSmall}>가까운 공항은 선택한 출발·도착 장소와의 직선거리 기준입니다. 장소와 공항 사이의 이동 시간은 별도로 확인해 주세요.</Text>
    <Text style={s.mutedSmall}>시간표 조회는 항공권 예매·잔여 좌석 확인이 아닙니다. 도착 후 60분, 귀가 출발 전 120분의 기본 여유 시간을 확보합니다.</Text>
  </View>;
}
function AirportChoices({ origin, airports, value, nearby = false, placeLabel = '출발 장소', disabled, onChange }: {
  origin?: SelectedPlace; airports: Airport[]; value?: SelectedPlace; nearby?: boolean; placeLabel?: string; disabled?: boolean;
  onChange: (airport: Airport, location: SelectedPlace) => void;
}) {
  const key = JSON.stringify(origin);
  const latest = useRef({ key, value, disabled, onChange });
  useEffect(() => { latest.current = { key, value, disabled, onChange }; }, [key, value, disabled, onChange]);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{ key?: string; rows?: Airport[]; error?: string }>();
  const [manualId, setManualId] = useState<string>();
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    let active = true;
    const place = key ? JSON.parse(key) as SelectedPlace : undefined;
    if (!nearby || !place) return;
    tripsApi.airportCandidates(place).then(rows => {
      if (!active) return;
      setResult({ key, rows });
      const first = rows[0];
      if (!latest.current.value && !latest.current.disabled && first?.location) latest.current.onChange(first, first.location);
    }).catch(e => { if (active) setResult({ key, error: apiError(e) }); });
    return () => { active = false; };
  }, [key, nearby, retry]);
  async function choose(a: Airport, manual = false) {
    if (busy || disabled) return;
    const requested = key;
    setBusy(true); setError('');
    try {
      const location = a.location ?? await tripsApi.airportLocation(a.id);
      if (alive.current && latest.current.key === requested && !latest.current.disabled) { setManualId(manual ? location.placeId : undefined); latest.current.value = location; latest.current.onChange(a, location); setExpanded(false); }
    } catch(e) { if (alive.current && latest.current.key === requested) setError(apiError(e)); }
    finally { if (alive.current) setBusy(false); }
  }
  const current = result?.key === key ? result : undefined;
  const manualSelected = !!value && (manualId === value.placeId || (!!current?.rows && !current.rows.some(a => a.name === value.name)));
  return <View style={{ gap: 6 }}>
    {nearby && !origin && <Text style={s.mutedSmall}>지도에서 {placeLabel}를 선택하면 가까운 공항 2곳을 표시해요.</Text>}
    {nearby && origin && !current && <View style={{ flexDirection: 'row', gap: 6 }}><ActivityIndicator size="small" /><Text style={s.mutedSmall}>가까운 공항을 찾고 있어요…</Text></View>}
    {!!current?.error && <><Text style={s.warnText}>{current.error}</Text><Pressable accessibilityRole="button" onPress={() => setRetry(n => n + 1)}><Text style={s.linkText}>공항 후보 다시 조회</Text></Pressable></>}
    <View style={{ flexDirection: 'row', gap: 6 }}>
      {current?.rows?.map(a => <Pressable key={a.id} accessibilityRole="radio" accessibilityState={{ checked: value?.name === a.name }} disabled={disabled || busy}
        style={[s.chip, { flex: 1, minWidth: 0, paddingHorizontal: 5 }, value?.name === a.name && s.chipActive]} onPress={() => choose(a)}>
        <SingleLineText fontSize={13} style={[s.chipText, { width: '100%', textAlign: 'center' }, value?.name === a.name && s.chipTextActive]}>{a.name}</SingleLineText></Pressable>)}
      <Pressable accessibilityRole="button" accessibilityLabel="공항 직접 선택" disabled={disabled || busy || !airports.length} style={[s.chip, { flex: 1, minWidth: 0, paddingHorizontal: 5 }, manualSelected && s.chipActive]} onPress={() => { setExpanded(v => !v); setError(''); }}><SingleLineText fontSize={13} style={[s.chipText, { width: '100%', textAlign: 'center' }, manualSelected && s.chipTextActive]}>{manualSelected ? value.name : '직접 선택'}</SingleLineText></Pressable>
    </View>
    {!!value && <Text style={s.mutedSmall}>선택한 공항: {value.name}</Text>}
    {busy && <Text style={s.mutedSmall}>공항 위치 확인 중…</Text>}
    {!!error && <Text style={s.warnText}>{error}</Text>}
    {expanded && <ScrollView nestedScrollEnabled style={{ maxHeight: 230 }}>{airports.map(a => <Pressable key={a.id} accessibilityRole="button" disabled={disabled || busy}
      style={[s.outlineButton, { marginTop: 5, minHeight: 42 }]} onPress={() => choose(a, true)}><Text style={s.outlineText}>{a.name}</Text></Pressable>)}</ScrollView>}
  </View>;
}
function FlightLeg({ returning, date, transport: t, airports, destination, onChange }: {
  returning: boolean; date: string; transport: Transport; airports: Airport[]; destination?: string; onChange: (patch: Partial<Transport>) => void;
}) {
  const origin = returning ? t.departureLocation : t.originLocation;
  const airport = (returning ? t.returnBoardingStation : t.boardingStation) ?? (airports.some(a => a.name === origin?.name) ? origin : undefined);
  const to = returning ? t.returnArrivalLocation : t.arrivalLocation;
  const fromId = airports.find(a => a.name === airport?.name)?.id;
  const toId = airports.find(a => a.name === to?.name)?.id;
  const returnDepartureCenter = returning ? [airport, t.arrivalLocation, origin].find(place => airports.some(a => a.name === place?.name)) : undefined;
  const auto = returning ? t.returnArrivalAuto !== false : !!t.arrivalAuto;
  const time = (returning ? t.departureTime : t.originTime) || (returning ? '20:00' : '08:00');
  const selected = returning ? t.returnFlight : t.flight;
  const context = JSON.stringify([date, origin?.placeId, fromId, toId, auto, time]);
  const latest = useRef(context);
  useEffect(() => { latest.current = context; }, [context]);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [mapOpen, setMapOpen] = useState<'origin' | 'arrival' | null>(null);
  const [arrivalReference, setArrivalReference] = useState<SelectedPlace | undefined>();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ key: string; rows?: FlightOption[]; candidate?: FlightOption; text?: string }>();
  const clear = returning ? { returnFlight: undefined } : { flight: undefined };
  const open = result?.key === context && !!result.rows?.length;
  async function lookup() {
    if (!fromId || !toId || !date || busy) return;
    const key = context;
    setBusy(true); setResult(undefined);
    try {
      const all = await tripsApi.flightOptions(date, fromId, toId);
      if (!alive.current || latest.current !== key) return;
      const rows = all.filter(f => f.departureTime >= time);
      setResult({ key, rows, candidate: rows.find(f => f.id === selected?.id) ?? rows[0], text: rows.length ? undefined : all.length ? '출발 희망 시각 이후 항공편이 없어요. 희망 시각을 앞당겨 조회해 주세요.' : '선택한 날짜와 구간에 조회되는 항공편이 없어요. 공항·날짜를 변경하거나 예약한 시각을 직접 선택해 주세요.' });
    } catch(e) { if (alive.current && latest.current === key) setResult({ key, text: apiError(e) }); }
    finally { if (alive.current) setBusy(false); }
  }
  function chooseArrivalAirport(location: SelectedPlace) {
    onChange({ ...clear, ...(returning ? { returnArrivalLocation: location } : { arrivalLocation: location, arrivalPlace: location.name,
      ...(!t.departureLocation || t.departureLocation.placeId === t.arrivalLocation?.placeId ? { departureLocation: location, departurePlace: location.name, returnBoardingStation: location, returnFlight: undefined } : {}) }) });
  }
  const mapAtUser = returning ? mapOpen === 'arrival' : mapOpen === 'origin';
  function confirm() { if (result?.key === context && result.candidate) { onChange(selectFlight(result.candidate, returning)); setResult(undefined); } }
  return <View style={{ gap: 8 }}>
    {mapOpen && <DestinationMapPicker visible placeKind="transport" pickerTitle={mapOpen === 'origin' ? '항공편 출발 장소 선택' : '항공편 도착 장소 선택'}
      transportFocus={mapAtUser ? undefined : 'airport'} centerOnUser={mapAtUser}
      initialCenter={mapAtUser ? undefined : mapOpen === 'arrival' ? arrivalReference ?? (toId ? to : undefined) : returnDepartureCenter}
      initialRegion={mapAtUser ? undefined : destination}
      initialDestination={(mapOpen === 'origin' ? origin?.name : (arrivalReference ?? to)?.name) || ''}
      onSelect={() => undefined} onClose={() => setMapOpen(null)} onPlaceSelect={location => {
        if (mapOpen === 'arrival') {
          setArrivalReference(location);
          onChange({ ...clear, ...(returning ? { returnArrivalLocation: undefined } : { arrivalLocation: undefined, arrivalPlace: '',
            ...(!t.departureLocation || t.departureLocation.placeId === t.arrivalLocation?.placeId ? { departureLocation: undefined, departurePlace: '', returnBoardingStation: undefined, returnFlight: undefined } : {}) }) });
          setMapOpen(null); return;
        }
        onChange({ ...clear, ...(returning ? { departureLocation: location, departurePlace: location.name, returnBoardingStation: undefined } : { originLocation: location, boardingStation: undefined }) });
        setMapOpen(null);
      }} />}
    <Text style={s.sectionTitleSmall}>{returning ? '오는 편 · 마지막 날' : '가는 편 · 첫날'} {date.replace(/-/g, '/')}</Text>
    <Text style={s.mutedSmall}>출발 장소</Text>
    <Pressable accessibilityRole="button" disabled={busy} style={s.outlineButton} onPress={() => setMapOpen('origin')}><Text style={s.outlineText}>{origin?.name || '지도에서 출발 장소 선택'} ⌄</Text></Pressable>
    <Text style={s.mutedSmall}>출발 공항</Text>
    <AirportChoices origin={origin} nearby airports={airports} value={airport} disabled={busy} onChange={(_a, location) => {
      onChange({ ...clear, ...(returning ? { returnBoardingStation: location } : { boardingStation: location, ...(!t.returnArrivalLocation || t.returnArrivalLocation.placeId === airport?.placeId ? { returnArrivalLocation: location, returnFlight: undefined } : {}) }) });
    }} />
    <Text style={s.mutedSmall}>도착 장소</Text>
    <Pressable accessibilityRole="button" accessibilityLabel="지도에서 도착 장소 선택" disabled={busy} style={s.outlineButton} onPress={() => setMapOpen('arrival')}><Text style={s.outlineText}>{arrivalReference?.name || to?.name || '지도에서 도착 장소 선택'} ⌄</Text></Pressable>
    <Text style={s.mutedSmall}>도착 공항</Text>
    <AirportChoices origin={arrivalReference ?? to} nearby placeLabel="도착 장소" airports={airports} value={to} disabled={busy}
      onChange={(_a, location) => chooseArrivalAirport(location)} />
    <View style={s.chips}>{[true, false].map(value => <Pressable key={String(value)} disabled={busy} accessibilityRole="button" accessibilityState={{ selected: auto === value }}
      style={[s.chip, auto === value && s.chipActive]} onPress={() => onChange({ ...clear, ...(returning ? { returnArrivalAuto: value, returnArrivalTime: t.returnArrivalTime || '21:00' } : { arrivalAuto: value }) })}>
      <Text style={[s.chipText, auto === value && s.chipTextActive]}>{value ? '시간표에서 선택' : '예약 시각 직접 선택'}</Text></Pressable>)}</View>
    <TimeWheelInput disabled={busy} minuteInterval={auto ? 10 : 1} label={auto ? '출발 희망 시각' : '출발 시각'} value={time} onChange={value => onChange({ ...clear, ...(returning ? { departureTime: value } : { originTime: value }) })} />
    {auto ? <>
      <Pressable disabled={busy || !date || !fromId || !toId || fromId === toId} accessibilityRole="button" onPress={open ? confirm : lookup}
        style={[s.primary, (busy || !date || !fromId || !toId || fromId === toId) && s.disabled]}><Text style={s.primaryText}>{busy ? '조회 중…' : open ? '선택' : '항공편 조회'}</Text></Pressable>
      {result?.key === context && !!result.text && <Text style={s.mutedSmall}>{result.text}</Text>}
      {open && result?.rows && <ScrollView nestedScrollEnabled style={{ maxHeight: 260 }}>{result.rows.map(f => <Pressable key={f.id} accessibilityRole="radio" accessibilityState={{ checked: result.candidate?.id === f.id }}
        style={[s.outlineButton, result.candidate?.id === f.id && { backgroundColor: '#edf2ff' }]} onPress={() => setResult({ ...result, candidate: f })}>
        <Text style={s.outlineText}>{f.airline} · {f.flightNo}</Text><Text style={s.memberNames}>{f.departureTime} 출발 → {f.arrivalTime} 도착</Text></Pressable>)}</ScrollView>}
      {selected && validFlightSelection(t, date, returning) && <Text style={s.sectionTitleSmall}>선택: {selected.airline} {selected.flightNo} · {selected.departureTime} → {selected.arrivalTime}</Text>}
      {selected && !validFlightSelection(t, date, returning) && <Text style={s.warnText}>날짜 또는 공항이 변경됐어요. 항공편을 다시 선택해 주세요.</Text>}
    </> : <TimeWheelInput disabled={busy} label="도착 시각" minuteInterval={1} value={returning ? t.returnArrivalTime || '21:00' : t.arrivalTime} onChange={value => onChange({ ...clear, ...(returning ? { returnArrivalTime: value } : { arrivalTime: value }) })} />}
  </View>;
}
