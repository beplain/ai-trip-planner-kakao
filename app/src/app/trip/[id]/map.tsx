import { useLocalSearchParams } from 'expo-router';
import * as Location from 'expo-location';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

import { loadMapRoutes, type RouteLeg, type RouteSegment } from '../../../../services/map-routes';
import { draftsApi } from '../../../../services/drafts';
import { apiError } from '../../../../services/api';
import { tripsApi, type Place, type TripDraft } from '../../../../services/trips';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { draftOptionLabel, hasCoords } from '@/lib/format';
import { buildKakaoMapHtml, openDirections, openPlacePage } from '@/lib/kakao-map';
import { C, DAY_COLORS, s } from '@/lib/styles';

const KAKAO_JS_KEY = process.env.EXPO_PUBLIC_KAKAO_JS_KEY ?? '';

export default function TripMapRoute() {
  return <RequireAuth><TripMapScreen /></RequireAuth>;
}

type MapPlace = { key: string; label: string; lat: number; lng: number };
type MapGroup = { color: string; places: MapPlace[] };

function TripMapScreen() {
  const { id, draftId, optionId } = useLocalSearchParams<{ id: string; draftId?: string; optionId?: string }>();
  const [trip, setTrip] = useState<TripDraft | null>(null);
  const [dayIdx, setDayIdx] = useState<number | 'all'>(0);
  const [routes, setRoutes] = useState<(RouteSegment & { color: string })[]>([]);
  const [routeState, setRouteState] = useState('');
  const [routeRetry, setRouteRetry] = useState(0);
  const routeCache = useRef(new Map<string, RouteLeg[]>());
  const [mapReady, setMapReady] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  const [locating, setLocating] = useState(false);
  const [position, setPosition] = useState<Location.LocationObject | null>(null);
  const locationLock = useRef(false);
  const mounted = useRef(true);
  const [selected, setSelected] = useState<{ place: Place; dayNumber: number; order: number } | null>(null);
  const webRef = useRef<WebView<Record<never, never>>>(null);
  const source = useMemo(() => ({ html: buildKakaoMapHtml(KAKAO_JS_KEY), baseUrl: 'http://localhost' }), []);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  async function locate() {
    if (locationLock.current || !mapReady || mapError) return;
    locationLock.current = true;
    setLocating(true);
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      if (!await Location.hasServicesEnabledAsync()) {
        Alert.alert('위치 서비스를 켜 주세요', '휴대폰 설정에서 위치 서비스를 켠 뒤 다시 눌러 주세요.');
        return;
      }
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!mounted.current) return;
      if (!permission.granted) {
        Alert.alert('위치 접근 권한이 필요해요', '내 위치를 표시하려면 위치 접근을 허용해 주세요.', [
          { text: '취소', style: 'cancel' },
          ...(!permission.canAskAgain ? [{ text: '설정 열기', onPress: () => { void Linking.openSettings(); } }] : []),
        ]);
        return;
      }
      const current = await Promise.race([
        Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error('위치 확인 시간이 초과됐어요. 실외나 창가에서 다시 시도해 주세요.')), 20000); }),
      ]);
      if (mounted.current) setPosition(current);
    } catch (e) {
      if (mounted.current) Alert.alert('위치를 확인하지 못했어요', apiError(e));
    } finally {
      if (timeout) clearTimeout(timeout);
      locationLock.current = false;
      if (mounted.current) setLocating(false);
    }
  }

  useEffect(() => {
    (draftId ? draftsApi.get(draftId).then((room) => {
      const option = room.draft.options?.find((entry) => entry.id === optionId);
      return option ? { ...room.draft, days: option.days, warnings: option.warnings, selectedOption: option.id } : room.draft;
    }) : tripsApi.get(Number(id))).then(setTrip).catch((e) => Alert.alert('불러오기 실패', apiError(e)));
  }, [id, draftId, optionId]);

  // 지도에 보낼 데이터 + 마커 key → 장소 매핑
  const { groups, lookup, missing } = useMemo(() => {
    const lookup = new Map<string, { place: Place; dayNumber: number; order: number }>();
    const groups: MapGroup[] = [];
    let missing = 0;
    trip?.days.forEach((d, i) => {
      missing += d.places.filter((p) => !hasCoords(p)).length;
      if (dayIdx !== 'all' && dayIdx !== i) return;
      const places: MapPlace[] = [];
      d.places.forEach((p, order) => {
        if (!hasCoords(p)) return;
        const key = `${d.dayNumber}-${order}`;
        lookup.set(key, { place: p, dayNumber: d.dayNumber, order });
        places.push({ key, label: String(order + 1), lat: p.latitude, lng: p.longitude });
      });
      groups.push({ color: DAY_COLORS[i % DAY_COLORS.length], places });
    });
    return { groups, lookup, missing };
  }, [trip, dayIdx]);

  useEffect(() => {
    if (!trip || !mapReady || !KAKAO_JS_KEY) return;
    let active = true;
    const mode = trip.travelDetails?.movementMode ?? 'CAR';
    const requests = groups.map((group) => {
      const legs = group.places.slice(1).flatMap((place, index) => {
        const previous = group.places[index];
        // Never join across an itinerary entry with missing coordinates.
        if (Number(place.label) !== Number(previous.label) + 1) return [];
        return [{ from: { latitude: previous.lat, longitude: previous.lng }, to: { latitude: place.lat, longitude: place.lng } }];
      });
      return { group, legs, key: JSON.stringify([mode, legs]) };
    });
    void (async () => {
      await Promise.resolve();
      if (!active) return;
      setRoutes([]); setRouteState('이동 경로를 확인하고 있어요…');
      const drawn: (RouteSegment & { color: string })[] = [];
      let unavailable = 0, total = 0;
      for (const request of requests) {
        if (!active) return;
        if (!request.legs.length) continue;
        let result = routeCache.current.get(request.key);
        if (!result) {
          try {
            result = await loadMapRoutes(mode, request.legs);
            if (result.length === request.legs.length && result.every((leg) => leg.status === 'OK')) routeCache.current.set(request.key, result);
          } catch { result = []; }
        }
        if (!active) return;
        total += request.legs.length;
        unavailable += request.legs.filter((_, index) => result?.[index]?.status !== 'OK').length;
        result.forEach((leg) => leg.segments.forEach((segment) => drawn.push({ ...segment, color: request.group.color })));
        setRoutes([...drawn]);
      }
      if (active) setRouteState(total === 0 ? '' : unavailable > 0
        ? `이동 구간 ${total}개 중 ${unavailable}개는 경로를 모두 확인하지 못했어요.` : '실제 이동 경로 · 현재 조회 기준');
    })();
    return () => { active = false; };
  }, [trip, groups, mapReady, routeRetry]);

  useEffect(() => {
    if (mapReady) webRef.current?.injectJavaScript(`window.renderRoutes(${JSON.stringify(routes)}); true;`);
  }, [mapReady, routes]);

  const count = groups.reduce((n, g) => n + g.places.length, 0);

  // 지도 준비 후, 그리고 일차 선택이 바뀔 때마다 마커 다시 그리기
  useEffect(() => {
    if (!mapReady) return;
    webRef.current?.injectJavaScript(`window.renderPlaces(${JSON.stringify(groups)}); true;`);
  }, [mapReady, groups]);

  useEffect(() => {
    if (!mapReady || !position) return;
    const { latitude, longitude, accuracy } = position.coords;
    webRef.current?.injectJavaScript(`window.showUserLocation(${JSON.stringify({ latitude, longitude, accuracy })}); true;`);
  }, [mapReady, position]);

  function onMessage(e: WebViewMessageEvent) {
    let msg: { type: string; key?: string; message?: string };
    try { msg = JSON.parse(e.nativeEvent.data); } catch { return; }
    if (msg.type === 'ready') {
      setSelected(null); setMapError(null); setMapReady(true);
      webRef.current?.injectJavaScript(`window.renderPlaces(${JSON.stringify(groups)}); true;`);
    }
    else if (msg.type === 'select' && msg.key) setSelected(lookup.get(msg.key) ?? null);
    else if (msg.type === 'error') {
      setMapError(msg.message === 'sdk-load'
        ? '지도를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'
        : '지도를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
    }
  }

  if (!trip) {
    return <SafeAreaView style={s.safe}><ScreenHeader title="지도" /><View style={s.center}><ActivityIndicator color={C.primary} size="large" /></View></SafeAreaView>;
  }

  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      <ScreenHeader title={draftOptionLabel(trip.options?.find((entry) => entry.id === trip.selectedOption)?.label) ?? '여행 지도'} />
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingBottom: 10, gap: 8 }}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flex: 1, minWidth: 0 }} contentContainerStyle={{ alignItems: 'center', gap: 8, paddingRight: 8 }}>
          <Pressable onPress={() => { setSelected(null); setDayIdx('all'); }} style={[s.chip, dayIdx === 'all' && s.chipActive]}>
            <Text style={[s.chipText, dayIdx === 'all' && s.chipTextActive]}>전체</Text>
          </Pressable>
          {trip.days.map((d, i) => (
            <Pressable key={d.id ?? i} onPress={() => { setSelected(null); setDayIdx(i); }} style={[s.chip, dayIdx === i && { borderColor: DAY_COLORS[i % DAY_COLORS.length], borderWidth: 2 }]}>
              <Text style={[s.chipText, { color: DAY_COLORS[i % DAY_COLORS.length] }]}>DAY {d.dayNumber}</Text>
            </Pressable>
          ))}
        </ScrollView>
        <Pressable accessibilityRole="button" accessibilityLabel="현재 위치 확인" disabled={!mapReady || !!mapError || locating}
          onPress={() => { void locate(); }} style={[s.smallButton, { minHeight: 44, paddingHorizontal: 12, justifyContent: 'center', flexShrink: 0 }, (!mapReady || !!mapError || locating) && s.disabled]}>
          <Text style={s.smallButtonText}>{locating ? '위치 확인 중…' : '◎ 내 위치'}</Text>
        </Pressable>
      </View>

      {!KAKAO_JS_KEY ? (
        <View style={s.center}>
          <Text style={s.emptyTitle}>지도를 이용할 수 없어요</Text>
          <Text style={[s.empty, { textAlign: 'center' }]}>잠시 후 지도를 다시 열어 주세요.</Text>
        </View>
      ) : (
        <View style={{ flex: 1 }}>
          <WebView<Record<never, never>>
            ref={webRef}
            source={source}
            onLoadStart={() => { setMapReady(false); setMapError(null); }}
            onLoadEnd={() => webRef.current?.injectJavaScript(`if (window.renderPlaces) window.renderPlaces(${JSON.stringify(groups)}); true;`)}
            originWhitelist={['*']}
            javaScriptEnabled
            domStorageEnabled
            onMessage={onMessage}
            style={{ flex: 1 }}
          />
          {mapError && (
            <View style={[s.warnBox, { position: 'absolute', top: 12, left: 12, right: 12, marginTop: 0 }]}>
              <Text style={s.warnText}>{mapError}</Text>
            </View>
          )}
          {mapReady && count === 0 && !mapError && (
            <View style={[s.infoBox, { position: 'absolute', top: 12, left: 12, right: 12, marginTop: 0 }]}>
              <Text style={s.infoText}>지도에 표시할 수 있는 장소가 없어요. 일정을 확인해 주세요.</Text>
            </View>
          )}

          <View style={{ position: 'absolute', left: 12, right: 12, bottom: 12, backgroundColor: 'rgba(255,255,255,0.97)', borderRadius: 14, padding: 12 }}>
            {!!routeState && <Text style={[s.mutedSmall, { marginBottom: 4 }]}>{routeState}</Text>}
            {routeState.includes('확인하지 못') && <Pressable accessibilityRole="button" onPress={() => setRouteRetry((value) => value + 1)} style={{ paddingVertical: 8 }}><Text style={s.smallButtonText}>경로 다시 조회</Text></Pressable>}
            {trip.travelDetails?.movementMode === 'PUBLIC_TRANSIT' && <Text style={[s.mutedSmall, { marginBottom: 4 }]}>파랑: 버스 · 초록: 지하철 · 회색 점선: 도보</Text>}
            {position && <Text style={[s.mutedSmall, { marginBottom: 6 }]}>내 위치 · {new Date(position.timestamp).toLocaleTimeString()}{position.coords.accuracy != null ? ` · 정확도 약 ${Math.round(position.coords.accuracy)}m` : ''} · 버튼을 눌러 갱신</Text>}
            {selected ? (
              <>
                <Text style={s.placeTime}>DAY {selected.dayNumber} · 장소 {selected.order + 1}{selected.place.visitTime ? ` · ${selected.place.visitTime}` : ''}</Text>
                <Text style={[s.placeName, { marginTop: 2 }]} numberOfLines={1}>{selected.place.name}</Text>
                {!!selected.place.address && <Text style={s.placeAddr} numberOfLines={1}>{selected.place.address}</Text>}
                <View style={[s.row, { marginTop: 10 }]}>
                  <Pressable style={[s.smallButton, { flex: 1, alignItems: 'center' }]} onPress={() => { void openDirections(selected.place); }}>
                    <Text style={s.smallButtonText}>길찾기</Text>
                  </Pressable>
                  <Pressable style={[s.smallButton, { flex: 1, alignItems: 'center' }]} onPress={() => openPlacePage(selected.place)}>
                    <Text style={s.smallButtonText}>카카오맵 상세</Text>
                  </Pressable>
                </View>
              </>
            ) : (
              <>
                <Text style={s.mutedSmall}>번호 마커를 누르면 장소 정보가 보여요. 확인되지 않은 이동 구간은 선으로 표시하지 않아요.</Text>
                {missing > 0 && <Text style={[s.mutedSmall, { color: C.warnText }]}>지도 미확인 장소 {missing}곳은 표시되지 않았어요.</Text>}
              </>
            )}
          </View>
        </View>
      )}
    </SafeAreaView>
  );
}
