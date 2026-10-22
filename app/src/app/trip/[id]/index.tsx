import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Pressable, RefreshControl, ScrollView, Share, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { tripsApi, type Place, type Trip } from '../../../../services/trips';
import { MemoModal } from '@/components/memo-modal';
import { DraggablePlaces } from '@/components/draggable-places';
import { TripActions } from '@/components/trip-actions';
import { useDragScroll } from '@/hooks/use-drag-scroll';
import { reorderItems } from '@/lib/reorder';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { TalkCalendarPanel } from '@/components/talk-calendar';
import { useAuth } from '@/lib/auth-context';
import { ROLE_LABEL, dateRange, formatBudget, shareText, shortDate } from '@/lib/format';
import { C, DAY_COLORS, s } from '@/lib/styles';

const POLL_MS = 10_000;

export default function TripDetailRoute() {
  return <RequireAuth><TripDetailScreen /></RequireAuth>;
}

function TripDetailScreen() {
  const { user } = useAuth();
  const { attach, dragging, onScroll, onContentSizeChange, controller: dragScroll } = useDragScroll();
  const refreshingRef = useRef(false);
  const memoRef = useRef(false);
  const memoVersion = useRef<number | null>(null);
  const { id } = useLocalSearchParams<{ id: string }>();
  const tripId = Number(id);
  const [trip, setTrip] = useState<Trip | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [memo, setMemo] = useState<Place | null>(null);
  const busyRef = useRef(false);
  const versionRef = useRef<number | null>(null);

  const apply = useCallback((t: Trip) => {
    if (versionRef.current != null && t.version < versionRef.current) return;
    versionRef.current = t.version;
    setTrip(t);
  }, []);

  const load = useCallback(async () => {
    try {
      const latest = await tripsApi.get(tripId);
      if (dragScroll.isDragging() || memoRef.current) return false;
      apply(latest);
      setError(null);
      return true;
    } catch (e) {
      setError(apiError(e));
      return false;
    }
  }, [tripId, apply, dragScroll]);

  // 공동 편집: 화면이 보이는 동안 주기적으로 변경 확인 (간단 폴링)
  useFocusEffect(useCallback(() => {
    let mounted = true;
    let active = AppState.currentState === 'active';
    void load();
    const sub = AppState.addEventListener('change', (st) => { active = st === 'active'; });
    const timer = setInterval(async () => {
      if (!active || busyRef.current || refreshingRef.current || dragScroll.isDragging() || memoRef.current || versionRef.current == null) return;
      try {
        const c = await tripsApi.changes(tripId, versionRef.current);
        if (mounted && c.changed && c.trip && !busyRef.current && !refreshingRef.current && !dragScroll.isDragging() && !memoRef.current && c.trip.version >= (versionRef.current ?? 0)) {
          apply(c.trip);
          setNotice('동행자가 일정을 수정해 최신 내용으로 갱신했어요.');
        }
      } catch {
        // 폴링 실패는 조용히 무시 (다음 주기에 재시도)
      }
    }, POLL_MS);
    return () => { mounted = false; clearInterval(timer); sub.remove(); };
  }, [tripId, apply, load, dragScroll]));

  /** 수정 요청 공통 처리: 중복 방지 + 409 충돌 시 최신본 재조회 */
  async function mutate(action: (version: number) => Promise<Trip>, expectedVersion?: number) {
    if (!trip || busyRef.current || refreshingRef.current) return;
    busyRef.current = true; setBusy(true); setNotice(null);
    try {
      apply(await action(expectedVersion ?? versionRef.current ?? trip.version));
      setNotice('변경 내용을 DB에 저장했어요.');
    } catch (e) {
      if (isConflict(e)) {
        Alert.alert('일정이 변경되었어요', apiError(e));
        await load();
      } else {
        Alert.alert('요청 실패', apiError(e));
      }
    } finally {
      busyRef.current = false; setBusy(false);
    }
  }

  function reorder(dayIdx: number, from: number, to: number) {
    const day = trip?.days[dayIdx];
    if (!day?.id) return;
    const ids = reorderItems(day.places.map((p) => p.id!), from, to);
    void mutate((v) => tripsApi.reorder(tripId, day.id!, ids, v));
  }

  function removePlace(p: Place) {
    Alert.alert('장소 삭제', `'${p.name}'을(를) 일정에서 뺄까요?`, [
      { text: '취소', style: 'cancel' },
      { text: '삭제', style: 'destructive', onPress: () => { void mutate((v) => tripsApi.deletePlace(tripId, p.id!, v)); } },
    ]);
  }

  function deleteTrip() {
    Alert.alert('여행 삭제', '이 여행을 삭제할까요? 동행자에게서도 사라집니다.', [
      { text: '취소', style: 'cancel' },
      {
        text: '삭제', style: 'destructive', onPress: async () => {
          if (busyRef.current) return;
          busyRef.current = true; setBusy(true);
          try {
            await tripsApi.remove(tripId);
            router.replace('/');
          } catch (e) {
            Alert.alert('삭제 실패', apiError(e));
          } finally {
            busyRef.current = false; setBusy(false);
          }
        },
      },
    ]);
  }

  async function onRefresh() {
    if (busyRef.current || refreshingRef.current || dragScroll.isDragging() || memoRef.current) return;
    refreshingRef.current = true; setRefreshing(true);
    try { if (await load()) setNotice('DB의 최신 일정으로 동기화했어요.'); }
    finally { refreshingRef.current = false; setRefreshing(false); }
  }

  if (!trip) {
    return (
      <SafeAreaView style={s.safe}>
        <ScreenHeader title="여행 일정" />
        <View style={s.center}>
          {error ? (
            <>
              <Text style={s.emptyTitle}>일정을 불러오지 못했어요</Text>
              <Text style={[s.empty, { textAlign: 'center' }]}>{error}</Text>
              <Pressable style={s.outlineButton} onPress={load}><Text style={s.outlineText}>다시 시도</Text></Pressable>
            </>
          ) : <ActivityIndicator size="large" color={C.primary} />}
        </View>
      </SafeAreaView>
    );
  }

  const canEdit = trip.myRole !== 'VIEWER';
  const isOwner = trip.myRole === 'OWNER';
  const companions = trip.members.filter((member) => member.role !== 'OWNER');

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="여행 일정" right={busy ? <ActivityIndicator color={C.primary} /> : undefined} />
      <ScrollView ref={attach} scrollEnabled={!dragging} onScroll={onScroll} scrollEventThrottle={16} onContentSizeChange={onContentSizeChange} contentContainerStyle={s.content} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
        <View style={s.detailHero}>
          <Text style={s.eyebrowLight}>
            {[trip.destination, `${trip.dayCount}일`, dateRange(trip.startDate, trip.endDate), formatBudget(trip.budget)].filter(Boolean).join(' · ')}
          </Text>
          <Text style={s.detailTitle}>{trip.title}</Text>
          <Text style={s.detailMeta}>여행 스타일: {trip.styles.length ? trip.styles.join(' · ') : '미선택'} · 내 이름: {user?.name ?? '사용자'}</Text>
        </View>

        {notice && (
          <Pressable style={s.infoBox} onPress={() => setNotice(null)}>
            <Text style={s.infoText}>{notice}</Text>
          </Pressable>
        )}

        <TalkCalendarPanel tripId={trip.id} version={trip.version} />
        <TripActions disabled={busy || refreshing || dragging}
          onMap={() => router.push(`/trip/${trip.id}/map`)}
          onShare={() => { void Share.share({ message: shareText(trip) }).catch(() => undefined); }}
          onInvite={() => isOwner ? router.push(`/trip/${trip.id}/invite`) : Alert.alert('초대 권한 없음', '초대는 여행을 만든 사람만 할 수 있어요.')} />
        <Pressable accessibilityRole="button" disabled={busy || refreshing || dragging || !!memo} style={[s.outlineButton, { marginTop: 0 }, (busy || refreshing || dragging) && s.disabled]} onPress={() => { void onRefresh(); }}>
          <Text style={s.outlineText}>{refreshing ? '동기화 중…' : '↻ 지금 동기화'}</Text>
        </Pressable>
        {!!error && <Text style={s.warnText}>{error}</Text>}
        <View style={s.sectionHead}>
          <Text style={s.sectionTitle}>일정</Text>
          <Text style={s.sectionHint}>{canEdit ? '길게 눌러 이동 · 10초마다 동기화' : '보기 전용'}</Text>
        </View>

        {trip.days.map((day, dayIdx) => {
          const color = DAY_COLORS[dayIdx % DAY_COLORS.length];
          return (
            <View key={day.id ?? day.dayNumber}>
              <Text style={[s.dayLabel, { color }]}>DAY {day.dayNumber}{day.date ? ` · ${shortDate(day.date)}` : ''}</Text>
              <DraggablePlaces places={day.places} color={color} editable={canEdit} busy={busy || refreshing} scroll={dragScroll}
                onReorder={(from, to) => reorder(dayIdx, from, to)} onDelete={removePlace}
                onEditMemo={(p) => { memoRef.current = true; memoVersion.current = trip.version; setMemo(p); }} />
            </View>
          );
        })}

        <View style={[s.memberBox, { marginTop: 14 }]}>
          <Text style={s.sectionTitleSmall}>함께 가는 동행자 ({companions.length})</Text>
          {companions.length === 0 && <Text style={s.mutedSmall}>아직 참여한 동행자가 없어요.</Text>}
          {companions.map((m) => (
            <Text key={m.userId} style={s.memberNames}>{m.name} · {ROLE_LABEL[m.role]}</Text>
          ))}
        </View>

        {isOwner && (
          <Pressable disabled={busy} style={s.outlineButton} onPress={deleteTrip}>
            <Text style={[s.outlineText, { color: C.danger }]}>여행 삭제</Text>
          </Pressable>
        )}
        <View style={s.footerNote}>
          <Text style={s.footerText}>방문 시각은 AI 추정치이며, 영업시간·교통은 방문 전 카카오맵에서 확인해 주세요.</Text>
        </View>
      </ScrollView>

      <MemoModal
        visible={!!memo}
        title={memo?.name ?? ''}
        initialTime={memo?.visitTime}
        initialNote={memo?.note}
        onCancel={() => { setMemo(null); memoRef.current = false; }}
        onSave={(visitTime, note) => {
          const target = memo;
          setMemo(null); memoRef.current = false;
          if (target?.id) void mutate((v) => tripsApi.updatePlace(tripId, target.id!, visitTime, note, v), memoVersion.current ?? undefined);
        }}
      />
    </SafeAreaView>
  );
}
