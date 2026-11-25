import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { tripsApi, type SelectedPlace } from '../../services/trips';
import { apiError } from '../../services/api';
import { s } from '@/lib/styles';

type Props = { kind?: '출발역' | '도착역'; origin?: SelectedPlace; destination?: SelectedPlace; value?: SelectedPlace; disabled?: boolean; onChange: (station: SelectedPlace | undefined) => void };
export function TrainStationChoices({ kind = '출발역', origin, destination, value, disabled, onChange }: Props) {
  const latest = useRef({ value, disabled, onChange });
  useEffect(() => { latest.current = { value, disabled, onChange }; }, [value, disabled, onChange]);
  const queryKey = JSON.stringify({ origin, destination });
  const [manualId, setManualId] = useState<string>();
  const [retry, setRetry] = useState(0);
  const [listKey, setListKey] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<string[]>();
  const [catalogError, setCatalogError] = useState('');
  const [choosing, setChoosing] = useState(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (listKey !== queryKey) return;
    let active = true;
    tripsApi.stationCatalog().then(rows => { if (active) setCatalog(rows); }).catch(e => { if (active) setCatalogError(apiError(e)); });
    return () => { active = false; };
  }, [listKey, queryKey]);
  const currentQuery = useRef(queryKey);
  useEffect(() => { currentQuery.current = queryKey; }, [queryKey]);
  async function choose(name: string) {
    if (disabled || choosing) return;
    const key = queryKey;
    setChoosing(true); setCatalogError('');
    try { const station = await tripsApi.stationLocation(name); if (alive.current && currentQuery.current === key && !latest.current.disabled) { setManualId(station.placeId); latest.current.value = station; latest.current.onChange(station); setListKey(null); } }
    catch(e) { if (alive.current && currentQuery.current === key) setCatalogError(apiError(e)); }
    finally { if (alive.current) setChoosing(false); }
  }
  const requestKey = JSON.stringify([queryKey, retry]);
  const [result, setResult] = useState<{ key: string; stations?: SelectedPlace[]; error?: string }>();
  useEffect(() => {
    let active = true;
    const query = JSON.parse(queryKey) as { origin?: SelectedPlace; destination?: SelectedPlace };
    if (!query.origin) return;
    tripsApi.stationCandidates(query.origin, query.destination).then(stations => {
      if (!active) return;
      setResult({ key: requestKey, stations });
      if (!latest.current.value && !latest.current.disabled && stations[0]) latest.current.onChange(stations[0]);
    }).catch(error => { if (active) setResult({ key: requestKey, error: apiError(error) }); });
    return () => { active = false; };
  }, [queryKey, requestKey]);
  const current = result?.key === requestKey ? result : undefined;
  const manualSelected = !!value && (manualId === value.placeId || (!!current?.stations && !current.stations.some(station => station.placeId === value.placeId)));
  return <View style={{ gap: 7 }}>
    {!origin ? <Text style={s.mutedSmall}>장소를 선택하면 가까운 KTX 정차역이 표시돼요.</Text>
      : current?.error ? <><Text style={s.mutedSmall}>{current.error}</Text><Pressable accessibilityRole="button" style={s.smallButton} onPress={() => setRetry(n => n + 1)}><Text style={s.smallButtonText}>역 후보 다시 조회</Text></Pressable></>
        : !current?.stations ? <View style={{ flexDirection: 'row', gap: 6 }}><ActivityIndicator size="small" /><Text style={s.mutedSmall}>고속열차 정차역을 찾고 있어요…</Text></View>
          : !current.stations.length ? <Text style={s.mutedSmall}>KTX·고속철도 정차역 후보를 찾지 못했어요. 전체 목록에서 직접 선택할 수 있어요.</Text> : null}
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexDirection: 'row', gap: 8, paddingVertical: 3 }}>
      {(current?.stations ?? []).map(station => {
        const selected = value?.placeId === station.placeId;
        return <Pressable key={station.placeId} accessibilityRole="radio" accessibilityState={{ checked: selected, disabled: !!disabled }} disabled={disabled}
          onPress={() => { setManualId(undefined); if (!selected) onChange(station); }} style={[s.chip, selected && s.chipActive, disabled && s.disabled]}>
          <Text style={[s.chipText, selected && s.chipTextActive]}>{station.name}</Text>
        </Pressable>;
      })}
      <Pressable accessibilityRole="button" accessibilityLabel={`목록에서 ${kind} 직접 선택`} disabled={disabled || choosing}
        onPress={() => { setCatalogError(''); setListKey(listKey === queryKey ? null : queryKey); }} style={[s.chip, manualSelected && s.chipActive, (disabled || choosing) && s.disabled]}>
        <Text style={[s.chipText, manualSelected && s.chipTextActive]}>{manualSelected ? value.name : '직접 선택'}</Text>
      </Pressable>
    </ScrollView>
    {listKey === queryKey && <View style={{ gap: 6 }}>
      <Text style={s.mutedSmall}>전체 KTX역 · 가나다순{choosing ? ' · 위치 확인 중…' : ''}</Text>
      {!!catalogError && <Text style={s.warnText}>{catalogError}</Text>}
      {!catalog && !catalogError && <ActivityIndicator size="small" />}
      <ScrollView nestedScrollEnabled style={{ maxHeight: 240 }}>{catalog?.map(name => <Pressable key={name} accessibilityRole="button" disabled={disabled || choosing}
        style={[s.outlineButton, { marginTop: 5, minHeight: 42 }]} onPress={() => choose(name)}><Text style={s.outlineText}>{name}역</Text></Pressable>)}</ScrollView>
    </View>}
    {manualSelected && <Text style={s.mutedSmall}>선택한 {kind}: {value.name}</Text>}
  </View>;
}
