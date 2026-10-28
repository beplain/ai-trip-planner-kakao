import * as Location from 'expo-location';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, Keyboard, Linking, Modal, Pressable, Text, TextInput, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

import { buildLodgingMapHtml, parseLodgingPlace, type LodgingPlace } from '@/lib/lodging-map';
import { buildDestinationMapHtml } from '@/lib/destination-map';
import { C, s } from '@/lib/styles';

const JS_KEY = process.env.EXPO_PUBLIC_KAKAO_JS_KEY ?? '';

function PickerContent({ initialDestination, onSelect, onClose, onPlaceSelect, placeKind = 'lodging' }: {
  initialDestination: string; onSelect: (destination: string) => void; onClose: () => void; onPlaceSelect?: (place: LodgingPlace) => void; placeKind?: 'lodging' | 'transport';
}) {
  const lodging = !!onPlaceSelect;
  const [selectedPlace, setSelectedPlace] = useState<LodgingPlace | null>(null);
  const [results, setResults] = useState<LodgingPlace[]>([]);
  const [ready, setReady] = useState(false);
  const [query, setQuery] = useState(initialDestination);
  const [selected, setSelected] = useState('');
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fatal, setFatal] = useState(false);
  const [locating, setLocating] = useState(false);
  const [position, setPosition] = useState<Location.LocationObject | null>(null);
  const locationLock = useRef(false);
  const mounted = useRef(true);
  const web = useRef<WebView<Record<never, never>>>(null);
  const pending = useRef<ReturnType<typeof setTimeout> | null>(null);
  const html = useMemo(() => lodging ? buildLodgingMapHtml(JS_KEY, placeKind) : buildDestinationMapHtml(JS_KEY), [lodging, placeKind]);

  useEffect(() => {
    if (ready || fatal || !JS_KEY) return;
    const timeout = setTimeout(() => { setFatal(true); setError('지도를 불러오는 시간이 초과됐어요. 인터넷 연결과 카카오 지도 설정을 확인해 주세요.'); }, 20000);
    return () => clearTimeout(timeout);
  }, [ready, fatal]);
  useEffect(() => () => { if (pending.current) clearTimeout(pending.current); }, []);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  async function locate() {
    if (!ready || fatal || locationLock.current) return;
    Keyboard.dismiss(); locationLock.current = true; setLocating(true);
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      const enabled = await Location.hasServicesEnabledAsync();
      if (!mounted.current) return;
      if (!enabled) {
        Alert.alert('위치 서비스를 켜 주세요', '휴대폰 설정에서 위치 서비스를 켠 뒤 다시 눌러 주세요.');
        return;
      }
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!mounted.current) return;
      if (!permission.granted) {
        Alert.alert('위치 접근 권한이 필요해요', '지도에 내 위치를 표시하려면 위치 접근을 허용해 주세요.', [
          { text: '취소', style: 'cancel' },
          ...(!permission.canAskAgain ? [{ text: '설정 열기', onPress: () => { void Linking.openSettings(); } }] : []),
        ]);
        return;
      }
      const current = await Promise.race([
        Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error('위치 확인 시간이 초과됐어요. 실외나 창가에서 다시 시도해 주세요.')), 20000); }),
      ]);
      if (!mounted.current) return;
      const { latitude, longitude, accuracy } = current.coords;
      setPosition(current); waitForSelection();
      web.current?.injectJavaScript(`window.showDestinationUserLocation(${JSON.stringify({ latitude, longitude, accuracy })}); true;`);
    } catch (e) {
      if (mounted.current) Alert.alert('위치를 확인하지 못했어요', e instanceof Error ? e.message : '잠시 후 다시 시도해 주세요.');
    } finally {
      if (timeout) clearTimeout(timeout);
      locationLock.current = false;
      if (mounted.current) setLocating(false);
    }
  }

  function waitForSelection() {
    if (pending.current) clearTimeout(pending.current);
    setSelected(''); setSelectedPlace(null); setResults([]); setError(null); setResolving(true);
    pending.current = setTimeout(() => { setResolving(false); setError('지역 확인이 늦어지고 있어요. 다른 지점을 선택하거나 다시 검색해 주세요.'); }, 15000);
  }
  function search() {
    if (!ready || fatal || !query.trim()) return;
    Keyboard.dismiss(); waitForSelection();
    const argument = JSON.stringify(query.trim()).replace(/</g, '\\u003c');
    web.current?.injectJavaScript(`window.searchDestination(${argument}); true;`);
  }
  function onMessage(event: WebViewMessageEvent) {
    let message: { type?: string; destination?: string; message?: string; places?: unknown[]; place?: unknown };
    try { message = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (!message || typeof message !== 'object') return;
    if (message.type === 'ready') {
      setReady(true);
      if (lodging && initialDestination.trim()) {
        waitForSelection();
        const initialQuery = JSON.stringify(initialDestination.trim()).replace(/</g, '\\u003c');
        web.current?.injectJavaScript(`window.searchDestination(${initialQuery}); true;`);
      }
      return;
    }
    if (message.type === 'resolving') { waitForSelection(); return; }
    if (pending.current) clearTimeout(pending.current);
    setResolving(false);
    if (lodging && message.type === 'lodging-results') {
      setResults((Array.isArray(message.places) ? message.places.slice(0, 15) : []).map(parseLodgingPlace).filter((p): p is LodgingPlace => p !== null));
      setError(null); return;
    }
    if (lodging && message.type === 'lodging-selected') {
      const place = parseLodgingPlace(message.place);
      if (place) { setSelectedPlace(place); setError(null); }
      return;
    }
    if (message.type === 'selected' && typeof message.destination === 'string' && message.destination.trim().length <= 50) {
      setSelected(message.destination.trim()); setError(null);
    } else if (message.type === 'error' || message.type === 'selection-error') {
      setSelected(''); setError(message.message ?? '지역을 확인하지 못했어요.');
      if (message.type === 'error') setFatal(true);
    }
  }
  return (
    <SafeAreaView style={s.safe}>
      <View style={{ flexDirection: 'row', alignItems: 'center', padding: 16, gap: 12 }}>
        <Text style={[s.sectionTitle, { flex: 1 }]}>{lodging ? placeKind === 'transport' ? '도착·출발 장소 선택' : '카카오맵에서 숙소 선택' : '지도에서 목적지 선택'}</Text>
        <Pressable accessibilityRole="button" onPress={onClose} style={{ padding: 10 }}><Text style={{ color: C.primary }}>닫기</Text></Pressable>
      </View>
      {!JS_KEY ? (
        <View style={s.center}><Text style={s.emptyTitle}>카카오 지도 키가 필요해요</Text><Text style={s.empty}>카카오 지도 설정을 확인한 뒤 다시 열어 주세요.</Text></View>
      ) : (
        <>
          <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingBottom: 12, gap: 8 }}>
            <TextInput accessibilityLabel="지도에서 지역 또는 장소 검색" value={query} onChangeText={setQuery} placeholder={lodging ? placeKind === "transport" ? "예: 부산역, 김해공항" : "예: 부산 해운대 호텔" : "예: 부산 해운대"} maxLength={lodging ? 100 : 50} returnKeyType="search" onSubmitEditing={search} style={[s.input, { flex: 1 }]} />
            <Pressable accessibilityRole="button" disabled={!ready || fatal || !query.trim()} onPress={search} style={[s.smallButton, { justifyContent: 'center', paddingHorizontal: 16 }, (!ready || fatal) && s.disabled]}><Text style={s.smallButtonText}>검색</Text></Pressable>
          </View>
          <View style={{ flex: 1 }}>
            <WebView<Record<never, never>> ref={web} source={{ html, baseUrl: 'http://localhost' }} originWhitelist={['*']} javaScriptEnabled domStorageEnabled onMessage={onMessage}
              onError={() => { setFatal(true); setError('지도를 불러오지 못했어요. 인터넷 연결을 확인해 주세요.'); }} style={{ flex: 1 }} />
            {!ready && !fatal && <View pointerEvents="none" style={{ position: 'absolute', top: 24, alignSelf: 'center' }}><ActivityIndicator color={C.primary} /><Text style={s.mutedSmall}>지도 불러오는 중…</Text></View>}
            <Pressable accessibilityRole="button" accessibilityLabel="GPS로 현재 위치 표시" disabled={!ready || fatal || locating}
              onPress={() => { void locate(); }}
              style={[s.smallButton, { position: 'absolute', top: 16, left: 12, minHeight: 44, justifyContent: 'center', paddingHorizontal: 16, backgroundColor: '#fff' }, (!ready || fatal || locating) && s.disabled]}>
              <Text style={s.smallButtonText}>{locating ? '위치 확인 중…' : '◎ 내 위치'}</Text>
            </Pressable>
          </View>
          {lodging && results.length > 0 && <FlatList style={{ flexGrow: 0, maxHeight: 190 }} keyboardShouldPersistTaps="handled"
            data={results} keyExtractor={(place) => place.placeId} renderItem={({ item }) => <Pressable accessibilityRole="button"
              accessibilityState={{ selected: selectedPlace?.placeId === item.placeId }}
              onPress={() => { Keyboard.dismiss(); setSelectedPlace(item); web.current?.injectJavaScript(`window.selectLodging(${JSON.stringify(item.placeId)}); true;`); }}
              style={{ paddingHorizontal: 20, paddingVertical: 12, borderBottomWidth: 1, borderColor: '#e3e7ef', backgroundColor: selectedPlace?.placeId === item.placeId ? '#edf2ff' : '#fff' }}>
              <Text style={s.sectionTitleSmall}>{item.name}</Text><Text style={s.mutedSmall}>{item.address}</Text>
            </Pressable>} />}
          <View style={{ backgroundColor: '#fff', padding: 20, gap: 10 }}>
            <Text style={s.mutedSmall}>{lodging ? '장소를 검색한 뒤 목록이나 지도 마커를 누르세요.' : '지도를 확대하고 원하는 지점을 누르세요. 선택한 지점이 속한 지역으로 여행을 계획합니다.'}</Text>
            {position && <Text style={s.mutedSmall}>내 위치 확인: {new Date(position.timestamp).toLocaleTimeString()}{position.coords.accuracy != null ? ` · 정확도 약 ${Math.round(position.coords.accuracy)}m` : ''} · 버튼을 눌러 갱신</Text>}
            {resolving && <ActivityIndicator color={C.primary} />}
            <Text accessibilityLiveRegion="polite" style={error ? s.warnText : s.sectionTitleSmall}>{error ?? (resolving ? '지역 확인 중…' : (lodging ? selectedPlace?.name || (placeKind === 'transport' ? '도착·출발 장소를 선택해 주세요' : '숙소를 선택해 주세요') : selected || '아직 선택한 지역이 없어요'))}</Text>
            <Pressable accessibilityRole="button" disabled={!(lodging ? selectedPlace : selected) || resolving || fatal} onPress={() => { if (lodging && selectedPlace) onPlaceSelect?.(selectedPlace); else if (!lodging) onSelect(selected); }} style={[s.primary, (!(lodging ? selectedPlace : selected) || resolving || fatal) && s.disabled]}><Text style={s.primaryText}>{lodging ? placeKind === 'transport' ? '이 장소 선택' : '이 숙소 선택' : '이 지역을 목적지로 선택'}</Text></Pressable>
          </View>
        </>
      )}
    </SafeAreaView>
  );
}

export function DestinationMapPicker({ visible, initialDestination, onSelect, onClose, onPlaceSelect, placeKind = 'lodging' }: {
  visible: boolean; initialDestination: string; onSelect: (destination: string) => void; onClose: () => void; onPlaceSelect?: (place: LodgingPlace) => void; placeKind?: 'lodging' | 'transport';
}) {
  return (
    <Modal visible={visible} animationType="slide" presentationStyle="fullScreen" onRequestClose={onClose}>
      <SafeAreaProvider>
        {visible && <PickerContent initialDestination={initialDestination} onSelect={onSelect} onClose={onClose} onPlaceSelect={onPlaceSelect} placeKind={placeKind} />}
      </SafeAreaProvider>
    </Modal>
  );
}
