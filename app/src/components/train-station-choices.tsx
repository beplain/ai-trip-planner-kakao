import { useEffect, useState } from 'react';
import { ActivityIndicator, Keyboard, Pressable, ScrollView, Text, View } from 'react-native';
import { DestinationMapPicker } from '@/components/destination-map-picker';
import { tripsApi, type SelectedPlace } from '../../services/trips';
import { apiError } from '../../services/api';
import { s } from '@/lib/styles';

type Props = { kind?: '출발역' | '도착역'; origin?: SelectedPlace; destination?: SelectedPlace; value?: SelectedPlace; disabled?: boolean; onChange: (station: SelectedPlace | undefined) => void };
export function TrainStationChoices({ kind = '출발역', origin, destination, value, disabled, onChange }: Props) {
  const queryKey = JSON.stringify({ origin, destination });
  const [retry, setRetry] = useState(0);
  const [mapKey, setMapKey] = useState<string | null>(null);
  const requestKey = JSON.stringify([queryKey, retry]);
  const [result, setResult] = useState<{ key: string; stations?: SelectedPlace[]; error?: string }>();
  useEffect(() => {
    let active = true;
    const query = JSON.parse(queryKey) as { origin?: SelectedPlace; destination?: SelectedPlace };
    if (!query.origin) return;
    tripsApi.stationCandidates(query.origin, query.destination).then(stations => {
      if (!active) return;
      setResult({ key: requestKey, stations });
    }).catch(error => { if (active) setResult({ key: requestKey, error: apiError(error) }); });
    return () => { active = false; };
  }, [queryKey, requestKey]);
  const current = result?.key === requestKey ? result : undefined;
  const manualSelected = !!value && !!current?.stations && !current.stations.some(station => station.placeId === value.placeId);
  return <View style={{ gap: 7 }}>
    {mapKey === queryKey && origin && !disabled && <DestinationMapPicker visible placeKind="transport" initialDestination=""
      onClose={() => setMapKey(null)} onSelect={() => undefined} onPlaceSelect={station => {
        onChange(station);
        setMapKey(null);
      }} />}
    {!origin ? <Text style={s.mutedSmall}>장소를 선택하면 가까운 KTX 정차역이 표시돼요.</Text>
      : current?.error ? <><Text style={s.mutedSmall}>{current.error}</Text><Pressable accessibilityRole="button" style={s.smallButton} onPress={() => setRetry(n => n + 1)}><Text style={s.smallButtonText}>역 후보 다시 조회</Text></Pressable></>
        : !current?.stations ? <View style={{ flexDirection: 'row', gap: 6 }}><ActivityIndicator size="small" /><Text style={s.mutedSmall}>고속열차 정차역을 찾고 있어요…</Text></View>
          : !current.stations.length ? <Text style={s.mutedSmall}>KTX·고속철도 정차역 후보를 찾지 못했어요. 지도에서 직접 선택할 수 있어요.</Text> : null}
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexDirection: 'row', gap: 8, paddingVertical: 3 }}>
      {(current?.stations ?? []).map(station => {
        const selected = value?.placeId === station.placeId;
        return <Pressable key={station.placeId} accessibilityRole="radio" accessibilityState={{ checked: selected, disabled: !!disabled }} disabled={disabled}
          onPress={() => { if (!selected) onChange(station); }} style={[s.chip, selected && s.chipActive, disabled && s.disabled]}>
          <Text style={[s.chipText, selected && s.chipTextActive]}>{station.name}</Text>
        </Pressable>;
      })}
      <Pressable accessibilityRole="button" accessibilityLabel={`지도에서 ${kind} 직접 선택`} disabled={!origin || disabled}
        onPress={() => { Keyboard.dismiss(); setMapKey(queryKey); }} style={[s.chip, manualSelected && s.chipActive, (!origin || disabled) && s.disabled]}>
        <Text style={[s.chipText, manualSelected && s.chipTextActive]}>{manualSelected ? value.name : '직접 선택'}</Text>
      </Pressable>
    </ScrollView>
  </View>;
}
