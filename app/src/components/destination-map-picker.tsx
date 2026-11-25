import { AppTextInput as TextInput } from '@/components/app-text-input';
import { AppModal as Modal } from './app-modal';
import { initialMapLoadState, mapLoadState, type MapLoadAction } from '@/lib/map-load-state';
import { mapDestinationRegion } from '@/lib/destination-region';
import { userMessage } from '../../services/user-message';
import * as Location from 'expo-location';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, Keyboard, Linking, Platform, Pressable, Text, View, type TextInputInstance } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { WebView, type MapWebViewHandle, type WebViewMessageEvent } from '@/components/map-webview';

import { buildLodgingMapHtml, lodgingSearchQuery, parseLodgingPlace, type LodgingPlace } from '@/lib/lodging-map';
import { buildDestinationMapHtml } from '@/lib/destination-map';
import { C, s } from '@/lib/styles';

const JS_KEY = process.env.EXPO_PUBLIC_KAKAO_JS_KEY ?? '';

function PickerContent({ initialDestination = '', onSelect, onClose, onPlaceSelect, placeKind = 'lodging', centerOnUser = false, initialRegion, initialCenter, pickerTitle }: {
  initialDestination: string; onSelect: (destination: string) => void; onClose: () => void; onPlaceSelect?: (place: LodgingPlace) => void; placeKind?: 'lodging' | 'transport' | 'visit'; centerOnUser?: boolean; initialRegion?: string; initialCenter?: Pick<LodgingPlace, 'latitude' | 'longitude'>; pickerTitle?: string;
}) {
  const lodging = !!onPlaceSelect;
  const [selectedPlace, setSelectedPlace] = useState<LodgingPlace | null>(null);
  const [results, setResults] = useState<LodgingPlace[]>([]);
  const [resultsExpanded, setResultsExpanded] = useState(true);
  const [ready, setReady] = useState(false);
  const initialQuery = lodging && placeKind === 'lodging' ? lodgingSearchQuery(initialRegion || initialDestination) : '';
  const [query, setQuery] = useState(initialQuery);
  const [selected, setSelected] = useState('');
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fatal, setFatal] = useState(false);
  const [locating, setLocating] = useState(false);
  const [position, setPosition] = useState<Location.LocationObject | null>(null);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const mapLoad = useRef(initialMapLoadState);
  const locationLock = useRef(false);
  const initialLocationRequested = useRef(false);
  const initialSearchRequested = useRef(false);
  const mapInteraction = useRef(0);
  const mounted = useRef(true);
  const searchInput = useRef<TextInputInstance>(null);
  const suppressKeyboard = useRef(true);
  const web = useRef<MapWebViewHandle<Record<never, never>>>(null);
  const pending = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latitude=initialCenter?.latitude, longitude=initialCenter?.longitude;
  const html = useMemo(() => lodging ? buildLodgingMapHtml(JS_KEY, placeKind, initialRegion,
    latitude!==undefined&&longitude!==undefined?{latitude,longitude}:undefined)
    : buildDestinationMapHtml(JS_KEY), [lodging, placeKind, initialRegion, latitude, longitude]);

  const updateMapLoad = useCallback((action: MapLoadAction) => {
    const next = mapLoadState(mapLoad.current, action);
    if (next === mapLoad.current) return;
    mapLoad.current = next;
    setReady(next.status === 'ready');
    setFatal(next.status === 'slow' || next.status === 'error');
    setError(next.message);
  }, []);

  useEffect(() => {
    if (ready || fatal || !JS_KEY) return;
    const timeout = setTimeout(() => { updateMapLoad({ type: 'timeout', attempt: loadAttempt }); }, 20000);
    return () => clearTimeout(timeout);
  }, [ready, fatal, loadAttempt, updateMapLoad]);
  useEffect(() => () => { if (pending.current) clearTimeout(pending.current); }, []);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const subscription = Keyboard.addListener('keyboardDidShow', () => {
      if (suppressKeyboard.current) { searchInput.current?.blur(); Keyboard.dismiss(); }
    });
    return () => subscription.remove();
  }, []);

  function reloadMap() {
    dismissSearchKeyboard();
    if (pending.current) { clearTimeout(pending.current); pending.current = null; }
    updateMapLoad({ type: 'retry' });
    initialLocationRequested.current = false; initialSearchRequested.current = false;
    locationLock.current = false; setLocating(false); setPosition(null);
    setResolving(false); setSelected(''); setSelectedPlace(null); setResults([]); setResultsExpanded(true);
    setLoadAttempt(mapLoad.current.attempt);
  }

  function dismissSearchKeyboard() {
    suppressKeyboard.current = true;
    searchInput.current?.blur();
    Keyboard.dismiss();
  }

  async function locate(initial = false) {
    if (mapLoad.current.status !== 'ready' || locationLock.current) return;
    const mapAttempt = mapLoad.current.attempt;
    const interaction = mapInteraction.current;
    dismissSearchKeyboard(); locationLock.current = true; setLocating(true);
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      const enabled = await Location.hasServicesEnabledAsync();
      if (!mounted.current || mapAttempt !== mapLoad.current.attempt) return;
      if (!enabled) {
        Alert.alert('위치 서비스를 켜 주세요', '휴대폰 설정에서 위치 서비스를 켠 뒤 다시 눌러 주세요.');
        return;
      }
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!mounted.current || mapAttempt !== mapLoad.current.attempt) return;
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
      if (!mounted.current || mapAttempt !== mapLoad.current.attempt || (initial && interaction !== mapInteraction.current)) return;
      const { latitude, longitude, accuracy } = current.coords;
      setPosition(current);
      waitForSelection();
      web.current?.injectJavaScript(`window.showDestinationUserLocation(${JSON.stringify({ latitude, longitude, accuracy })}, false); true;`);
    } catch (e) {
      if (mounted.current && mapAttempt === mapLoad.current.attempt) Alert.alert('위치를 확인하지 못했어요', userMessage(e instanceof Error ? e.message : null, '위치 권한과 인터넷 연결을 확인하고 다시 시도해 주세요.'));
    } finally {
      if (timeout) clearTimeout(timeout);
      if (mapAttempt === mapLoad.current.attempt) {
        locationLock.current = false;
        if (mounted.current) setLocating(false);
      }
    }
  }

  function waitForSelection() {
    if (pending.current) clearTimeout(pending.current);
    setSelected(''); setSelectedPlace(null); setResults([]); setResultsExpanded(true); setError(null); setResolving(true);
    pending.current = setTimeout(() => { setResolving(false); setError('지역 확인이 늦어지고 있어요. 다른 지점을 선택하거나 다시 검색해 주세요.'); }, 15000);
  }
  function search() {
    if (!ready || fatal || !query.trim()) return;
    mapInteraction.current++;
    dismissSearchKeyboard(); waitForSelection();
    const argument = JSON.stringify(query.trim()).replace(/</g, '\\u003c');
    web.current?.injectJavaScript(`window.searchDestination(${argument}); true;`);
  }
  function onMessage(event: WebViewMessageEvent, attempt = loadAttempt) {
    if (attempt !== mapLoad.current.attempt) return;
    let message: { type?: string; destination?: string; message?: string; places?: unknown[]; place?: unknown };
    try { message = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (!message || typeof message !== 'object') return;
    if (message.type === 'ready') {
      updateMapLoad({ type: 'ready', attempt });
      if (centerOnUser && !initialLocationRequested.current) {
        initialLocationRequested.current = true;
        void locate(true);
      } else if (lodging && placeKind === 'lodging' && !initialSearchRequested.current) {
        initialSearchRequested.current = true;
        waitForSelection();
        web.current?.injectJavaScript(`window.searchDestination(${JSON.stringify(initialQuery).replace(/</g, '\\u003c')}); true;`);
      }
      return;
    }
    if (message.type === 'resolving') { mapInteraction.current++; waitForSelection(); return; }
    if (pending.current) clearTimeout(pending.current);
    setResolving(false);
    if (lodging && message.type === 'lodging-results') {
      setResults((Array.isArray(message.places) ? message.places.slice(0, 15) : []).map(p => parseLodgingPlace(p, placeKind === 'transport')).filter((p): p is LodgingPlace => p !== null));
      setError(null); return;
    }
    if (lodging && message.type === 'lodging-selected') {
      const place = parseLodgingPlace(message.place, placeKind === 'transport');
      if (place) { dismissSearchKeyboard(); setSelectedPlace(place); setError(null); }
      return;
    }
    if (message.type === 'selected' && typeof message.destination === 'string' && message.destination.trim().length <= 50) {
      const destination = mapDestinationRegion(message.destination);
      setSelected(destination ?? ''); setError(destination ? null : '이 지점의 지역명을 확인하지 못했어요. 다른 지점을 선택해 주세요.');
    } else if (message.type === 'error' || message.type === 'selection-error') {
      setSelected(''); setError(userMessage(message.message, '지역을 확인하지 못했어요. 다른 지점을 선택해 주세요.'));
      if (message.type === 'error') updateMapLoad({ type: 'error', attempt, message: userMessage(message.message, '지도를 불러오지 못했어요. 다시 불러와 주세요.') });
    }
  }
  return (
    <SafeAreaView style={s.safe}>
      <View style={{ flexDirection: 'row', alignItems: 'center', padding: 16, gap: 12 }}>
        <Text style={[s.sectionTitle, { flex: 1 }]}>{pickerTitle ?? (lodging ? placeKind === 'visit' ? '장소 선택' : placeKind === 'transport' ? '도착·출발 장소 선택' : '카카오맵에서 숙소 선택' : '지도에서 목적지 선택')}</Text>
        <Pressable accessibilityRole="button" onPress={() => { dismissSearchKeyboard(); onClose(); }} style={{ padding: 10 }}><Text style={{ color: C.primary }}>닫기</Text></Pressable>
      </View>
      {!JS_KEY ? (
        <View style={s.center}><Text style={s.emptyTitle}>지도를 이용할 수 없어요</Text><Text style={s.empty}>잠시 후 지도를 다시 열어 주세요.</Text></View>
      ) : (
        <>
          <View style={{ flexDirection: 'row', paddingHorizontal: 16, paddingBottom: 12, gap: 8 }}>
            <View style={{ flex: 1 }}>
              <TextInput ref={searchInput}
                onTouchStart={() => { suppressKeyboard.current = false; }}
                onAccessibilityTap={() => { suppressKeyboard.current = false; searchInput.current?.focus(); }}
                onFocus={() => { if (Platform.OS === 'web') suppressKeyboard.current = false; else if (suppressKeyboard.current) dismissSearchKeyboard(); }}
                accessibilityLabel="지도에서 지역 또는 장소 검색" value={query} onChangeText={setQuery} placeholder={lodging ? placeKind !== "lodging" ? "예: 해운대해수욕장, 정동문화사" : "예: 부산 해운대 호텔" : "예: 부산 해운대"} maxLength={lodging ? 100 : 50} returnKeyType="search" onSubmitEditing={search} style={[s.input, { paddingRight: 44 }]} />
              {!!query && <Pressable accessibilityRole="button" accessibilityLabel="지도 검색어 지우기"
                onPress={() => setQuery('')}
                style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 44, alignItems: 'center', justifyContent: 'center' }}>
                <View style={{ width: 20, height: 20, borderRadius: 10, backgroundColor: '#E8EBF0', alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: '#657087', fontSize: 16, lineHeight: 20 }}>×</Text>
                </View>
              </Pressable>}
            </View>
            <Pressable accessibilityRole="button" disabled={!ready || fatal || !query.trim()} onPress={search} style={[s.smallButton, { justifyContent: 'center', paddingHorizontal: 16 }, (!ready || fatal) && s.disabled]}><Text style={s.smallButtonText}>검색</Text></Pressable>
          </View>
          <View style={{ flex: 1 }} onLayout={() => { web.current?.injectJavaScript('window.relayoutDestinationMap && window.relayoutDestinationMap(); true;'); }}>
            <WebView<Record<never, never>> key={loadAttempt} ref={web} source={{ html, baseUrl: 'http://localhost' }} originWhitelist={['*']} javaScriptEnabled domStorageEnabled keyboardDisplayRequiresUserAction onTouchStart={dismissSearchKeyboard} onMessage={(event) => onMessage(event, loadAttempt)}
              onError={() => updateMapLoad({ type: 'error', attempt: loadAttempt, message: '지도를 불러오지 못했어요. 인터넷 연결을 확인하고 다시 불러와 주세요.' })} style={{ flex: 1, opacity: (initialRegion || initialCenter) && !ready && !fatal ? 0 : 1 }} />
            {!ready && !fatal && <View pointerEvents="none" style={{ position: 'absolute', top: 24, alignSelf: 'center' }}><ActivityIndicator color={C.primary} /><Text style={s.mutedSmall}>지도 불러오는 중…</Text></View>}
            {centerOnUser && !fatal && (!ready || locating) && <View style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center' }}>
              <ActivityIndicator color={C.primary} />
              <Text style={s.mutedSmall}>내 위치에서 지도를 여는 중…</Text>
            </View>}
            <Pressable accessibilityRole="button" accessibilityLabel="GPS로 현재 위치 표시" disabled={!ready || fatal || locating}
              onPress={() => { void locate(); }}
              style={[s.smallButton, { position: 'absolute', top: 16, left: 12, minHeight: 44, justifyContent: 'center', paddingHorizontal: 16, backgroundColor: '#fff' }, (!ready || fatal || locating) && s.disabled]}>
              <Text style={s.smallButtonText}>{locating ? '위치 확인 중…' : '◎ 내 위치'}</Text>
            </Pressable>
          </View>
          {lodging && results.length > 0 && <Pressable accessibilityRole="button" accessibilityLabel={resultsExpanded ? '장소 목록 접기' : '장소 목록 펼치기'} accessibilityState={{ expanded: resultsExpanded }}
            onPress={() => { dismissSearchKeyboard(); setResultsExpanded(value => !value); }} style={{ padding: 12, backgroundColor: '#fff', alignItems: 'center' }}>
            <Text style={s.smallButtonText}>{resultsExpanded ? '장소 목록 접기 ▴' : '장소 목록 펼치기 ▾'} · {results.length}곳</Text>
          </Pressable>}
          {lodging && results.length > 0 && resultsExpanded && <FlatList style={{ flexGrow: 0, maxHeight: 190 }} keyboardShouldPersistTaps="always" keyboardDismissMode="on-drag"
            data={results} keyExtractor={(place) => place.placeId} renderItem={({ item }) => <Pressable accessibilityRole="button"
              accessibilityState={{ selected: selectedPlace?.placeId === item.placeId }}
              onPressIn={dismissSearchKeyboard}
              onPress={() => { dismissSearchKeyboard(); setSelectedPlace(item); web.current?.injectJavaScript(`window.selectLodging(${JSON.stringify(item.placeId)}); true;`); }}
              style={{ paddingHorizontal: 20, paddingVertical: 12, borderBottomWidth: 1, borderColor: '#e3e7ef', backgroundColor: selectedPlace?.placeId === item.placeId ? '#edf2ff' : '#fff' }}>
              <Text style={s.sectionTitleSmall}>{item.name}</Text><Text style={s.mutedSmall}>{item.address}</Text>
            </Pressable>} />}
          <View style={{ backgroundColor: '#fff', padding: 20, gap: 10 }}>
            <Text style={s.mutedSmall}>{lodging ? placeKind === 'transport' ? '지도를 누르면 50m 안의 실제 장소가 표시돼요. 목록이나 마커에서 원하는 장소를 선택해 주세요.' : '장소를 검색한 뒤 목록이나 지도 마커를 누르세요.' : '지도를 확대하고 원하는 지점을 누르세요. 선택한 지점이 속한 지역으로 여행을 계획합니다.'}</Text>
            {position && <Text style={s.mutedSmall}>내 위치 확인: {new Date(position.timestamp).toLocaleTimeString()}{position.coords.accuracy != null ? ` · 정확도 약 ${Math.round(position.coords.accuracy)}m` : ''} · 버튼을 눌러 갱신</Text>}
            {resolving && <ActivityIndicator color={C.primary} />}
            <Text accessibilityLiveRegion="polite" style={error ? s.warnText : s.sectionTitleSmall}>{error ?? (resolving ? lodging ? '장소 확인 중…' : '지역 확인 중…' : (lodging ? selectedPlace?.name || (placeKind !== 'lodging' ? '장소를 선택해 주세요' : '숙소를 선택해 주세요') : selected || '아직 선택한 지역이 없어요'))}</Text>
            {fatal && <Pressable accessibilityRole="button" onPress={reloadMap} style={[s.smallButton, { alignSelf: 'flex-start', padding: 12 }]}>
              <Text style={s.smallButtonText}>지도 다시 불러오기</Text>
            </Pressable>}
            {lodging && selectedPlace && <Text style={s.mutedSmall}>{selectedPlace.address}</Text>}
            <Pressable accessibilityRole="button" disabled={!(lodging ? selectedPlace : selected) || resolving || fatal} onPress={() => { dismissSearchKeyboard(); if (lodging && selectedPlace) onPlaceSelect?.(selectedPlace); else if (!lodging) onSelect(selected); }} style={[s.primary, (!(lodging ? selectedPlace : selected) || resolving || fatal) && s.disabled]}><Text style={s.primaryText}>{lodging ? placeKind !== 'lodging' ? '이 장소 선택' : '이 숙소 선택' : '이 지역을 목적지로 선택'}</Text></Pressable>
          </View>
        </>
      )}
    </SafeAreaView>
  );
}

export function DestinationMapPicker({ visible, initialDestination, onSelect, onClose, onPlaceSelect, placeKind = 'lodging', centerOnUser = false, initialRegion, initialCenter, pickerTitle }: {
  visible: boolean; initialDestination: string; onSelect: (destination: string) => void; onClose: () => void; onPlaceSelect?: (place: LodgingPlace) => void; placeKind?: 'lodging' | 'transport' | 'visit'; centerOnUser?: boolean; initialRegion?: string; initialCenter?: Pick<LodgingPlace, 'latitude' | 'longitude'>; pickerTitle?: string;
}) {
  return (
    <Modal visible={visible} animationType="slide" presentationStyle="fullScreen" onRequestClose={onClose}>
      <SafeAreaProvider>
        {visible && <PickerContent initialDestination={initialDestination} onSelect={onSelect} onClose={onClose} onPlaceSelect={onPlaceSelect} placeKind={placeKind} centerOnUser={centerOnUser} initialRegion={initialRegion} initialCenter={initialCenter} pickerTitle={pickerTitle} />}
      </SafeAreaProvider>
    </Modal>
  );
}
