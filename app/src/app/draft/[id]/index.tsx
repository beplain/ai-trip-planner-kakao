import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, Share, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { draftsApi, type DraftRoom } from '../../../../services/drafts';
import type { Place, TripDraft } from '../../../../services/trips';
import { MemoModal } from '@/components/memo-modal';
import { DraggablePlaces } from '@/components/draggable-places';
import { TripActions } from '@/components/trip-actions';
import { useDragScroll } from '@/hooks/use-drag-scroll';
import { reorderItems } from '@/lib/reorder';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { TalkCalendarConnection } from '@/components/talk-calendar';
import { dateRange, formatBudget, ROLE_LABEL, shareText, shortDate } from '@/lib/format';
import { C, DAY_COLORS, s } from '@/lib/styles';

export default function DraftRoute() { return <RequireAuth><DraftScreen /></RequireAuth>; }

function DraftScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { attach, dragging, onScroll, onContentSizeChange, controller: dragScroll } = useDragScroll();
  const [syncing, setSyncing] = useState(false);
  const syncingRef = useRef(false);
  const [room, setRoom] = useState<DraftRoom | null>(null);
  const current = useRef<DraftRoom | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [memo, setMemo] = useState<{ day: number; index: number; version: number } | null>(null);
  const memoOpen = useRef(false);
  const apply = useCallback((next: DraftRoom) => {
    current.current = next; setRoom(next); setError('');
    if (next.savedTripId) router.replace(`/trip/${next.savedTripId}`);
  }, []);

  useFocusEffect(useCallback(() => {
    let active = true;
    let fetching = false;
    async function refresh() {
      if (fetching || lock.current || syncingRef.current || memoOpen.current || dragScroll.isDragging()) return;
      fetching = true;
      try {
        const next = await draftsApi.get(id);
        if (active && !lock.current && !syncingRef.current && !memoOpen.current && !dragScroll.isDragging() && (!current.current || next.version >= current.current.version)) {
          apply(next);
        }
      } catch (e) { if (active) setError(apiError(e)); }
      finally { fetching = false; }
    }
    void refresh();
    const timer = setInterval(() => { void refresh(); }, 10000);
    return () => { active = false; clearInterval(timer); };
  }, [id, apply, dragScroll]));

  async function change(transform: (draft: TripDraft) => TripDraft, expectedVersion?: number) {
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || snapshot.myRole === 'VIEWER') return;
    lock.current = true; setBusy(true);
    try {
      apply(await draftsApi.update(id, transform(snapshot.draft), expectedVersion ?? snapshot.version));
    } catch (e) {
      Alert.alert('초안 수정 실패', apiError(e));
      // Never resend stale edits over someone else's work.
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }

    } finally { lock.current = false; setBusy(false); }
  }

  async function save() {
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || snapshot.myRole === 'VIEWER') return;
    lock.current = true; setBusy(true);
    try {
      const trip = await draftsApi.save(id, snapshot.version);
      if (trip.calendar && trip.calendar.status !== 'CREATED') Alert.alert('여행은 저장됐어요', trip.calendar.message);
      router.replace(`/trip/${trip.id}`);
    } catch (e) {
      Alert.alert('저장 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
    } finally { lock.current = false; setBusy(false); }
  }

  function updatePlaces(day: number, transform: (places: Place[]) => Place[], version?: number) {
    void change((d) => ({ ...d, days: d.days.map((entry, index) => index === day ? { ...entry, places: transform(entry.places) } : entry) }), version);
  }
  async function syncNow() {
    if (lock.current || syncingRef.current || dragScroll.isDragging() || memoOpen.current) return;
    syncingRef.current = true; setSyncing(true);
    try {
      const latest = await draftsApi.get(id);
      if (!current.current || latest.version >= current.current.version) apply(latest);
    } catch (e) { setError(apiError(e)); }
    finally { syncingRef.current = false; setSyncing(false); }
  }
  const draft = room?.draft;
  const creator = room?.members.find((member) => member.role === 'OWNER');
  const companions = room?.members.filter((member) => member.role !== 'OWNER') ?? [];
  const canEdit = room?.myRole !== 'VIEWER' && !busy && !syncing;
  const memoPlace = memo && draft ? draft.days[memo.day]?.places[memo.index] : undefined;
  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="일정 초안 검토" onBack={() => router.replace('/')} />
      {!draft ? <View style={s.center}>{error ? <Text style={s.warnText}>{error}</Text> : <ActivityIndicator size="large" color={C.primary} />}</View> : (
        <ScrollView ref={attach} scrollEnabled={!dragging} onScroll={onScroll} scrollEventThrottle={16} onContentSizeChange={onContentSizeChange} contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
          <View style={s.detailHero}>
            <Text style={s.eyebrowLight}>{[draft.destination, `${draft.days.length}일`, dateRange(draft.startDate, draft.endDate), formatBudget(draft.budget)].filter(Boolean).join(' · ')}</Text>
            <Text style={s.detailTitle}>{draft.title}</Text>
            <Text style={s.detailMeta}>만든 사람: {creator?.name || '이름 없음'}</Text>
          </View>
          <TripActions disabled={busy || syncing || dragging}
            onMap={() => router.push(`/trip/0/map?draftId=${id}`)}
            onShare={() => { void Share.share({ message: shareText(draft) }).catch(() => undefined); }}
            onInvite={() => room?.myRole === 'OWNER' ? router.push(`/trip/0/invite?draftId=${id}`) : Alert.alert('초대 권한 없음', '초대는 여행을 만든 사람만 할 수 있어요.')} />
          <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || !!memo} style={[s.outlineButton, { marginTop: 0 }, (busy || syncing || dragging) && s.disabled]} onPress={() => { void syncNow(); }}>
            <Text style={s.outlineText}>{syncing ? '동기화 중…' : '↻ 지금 동기화'}</Text>
          </Pressable>
          {room?.myRole !== 'VIEWER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging} style={[s.outlineButton, { marginTop: 0 }]} onPress={() => router.push(`/draft/${id}/edit`)}>
            <Text style={s.outlineText}>여행 정보 수정</Text>
          </Pressable>}
          {!!error && <Text style={s.warnText}>{error}</Text>}
          {!!draft.warnings?.length && <View style={s.warnBox}>{draft.warnings.map((w, i) => <Text key={i} style={s.warnText}>• {w}</Text>)}</View>}
          <View style={s.memberBox}>
            <Text style={s.sectionTitleSmall}>함께 계획하는 동행자 {companions.length}명</Text>
            {companions.length === 0 && <Text style={s.mutedSmall}>아직 참여한 동행자가 없어요.</Text>}
            {companions.map((m) => <Text key={m.userId} style={s.memberNames}>{m.name} ({ROLE_LABEL[m.role]})</Text>)}
          </View>
          {draft.days.map((day, dayIdx) => <View key={day.dayNumber}>
            <Text style={[s.dayLabel, { color: DAY_COLORS[dayIdx % DAY_COLORS.length] }]}>DAY {day.dayNumber}{day.date ? ` · ${shortDate(day.date)}` : ''}</Text>
            <DraggablePlaces places={day.places} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} editable={canEdit} busy={busy || syncing} scroll={dragScroll}
              onReorder={(from, to) => updatePlaces(dayIdx, (places) => reorderItems(places, from, to))}
              onDelete={(_p, i) => updatePlaces(dayIdx, (places) => places.filter((_, j) => i !== j))}
              onEditMemo={(_p, i) => { memoOpen.current = true; setMemo({ day: dayIdx, index: i, version: room!.version }); }} />
          </View>)}
          {room?.myRole !== 'VIEWER' && <><TalkCalendarConnection /><Pressable accessibilityRole="button" disabled={busy || syncing || dragging} style={[s.primary, (busy || syncing || dragging) && s.disabled]} onPress={() => { void save(); }}><Text style={s.primaryText}>{busy ? '반영 중…' : '이 일정 확정하기'}</Text></Pressable></>}
        </ScrollView>
      )}
      <MemoModal visible={!!memoPlace} title={memoPlace?.name ?? ''} initialTime={memoPlace?.visitTime} initialNote={memoPlace?.note}
        onCancel={() => { setMemo(null); memoOpen.current = false; }} onSave={(visitTime, note) => {
          if (memo) updatePlaces(memo.day, (places) => places.map((p, i) => i === memo.index ? { ...p, visitTime: visitTime || undefined, note: note || undefined } : p), memo.version);
          setMemo(null); memoOpen.current = false;
        }} />
    </SafeAreaView>
  );
}
