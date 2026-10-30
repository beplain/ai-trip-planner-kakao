import { TransportDeparture, TransportReturnArrival } from '@/components/transport-departure';
import { userMessage } from '../../../../services/user-message';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, Share, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { draftsApi, type DraftRoom } from '../../../../services/drafts';
import type { Place, TripDraft } from '../../../../services/trips';
import { MemoModal } from '@/components/memo-modal';
import { DraggablePlaces } from '@/components/draggable-places';
import { RequestReflection } from '@/components/request-reflection';
import { TravelDetailsSummary } from '@/components/travel-details-input';
import { TripActions } from '@/components/trip-actions';
import { useDragScroll } from '@/hooks/use-drag-scroll';
import { reorderItems } from '@/lib/reorder';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { TalkCalendarConnection } from '@/components/talk-calendar';
import { draftOptionLabel, dateRange, formatBudget, ROLE_LABEL, shareText, shortDate } from '@/lib/format';
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
  const [previewId, setPreviewId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [memo, setMemo] = useState<{ day: number; index: number; version: number } | null>(null);
  const memoOpen = useRef(false);
  const apply = useCallback((next: DraftRoom) => {
    current.current = next; setRoom(next); setError('');
    if (next.myRole !== 'VIEWER') setPreviewId(null);
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

  async function switchOption(optionId: string) {
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || memoOpen.current || dragScroll.isDragging()) return;
    if (snapshot.myRole === 'VIEWER') { setPreviewId(optionId); return; }
    if (snapshot.draft.selectedOption === optionId) return;
    lock.current = true; setBusy(true);
    try { apply(await draftsApi.switchOption(id, optionId, snapshot.version)); setPreviewId(null); }
    catch (e) {
      Alert.alert('유형 전환 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
    } finally { lock.current = false; setBusy(false); }
  }

  async function save() {
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || snapshot.myRole === 'VIEWER') return;
    lock.current = true; setBusy(true);
    try {
      const trip = await draftsApi.save(id, snapshot.version);
      if (trip.calendar && trip.calendar.status !== 'CREATED') Alert.alert('여행은 저장됐어요', userMessage(trip.calendar.message));
      router.replace(`/trip/${trip.id}`);
    } catch (e) {
      Alert.alert('저장 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
    } finally { lock.current = false; setBusy(false); }
  }

  function deleteDraft() {
    const snapshot = current.current;
    if (!snapshot || snapshot.myRole !== 'OWNER' || lock.current || syncingRef.current || dragScroll.isDragging() || memoOpen.current) return;
    Alert.alert('초안 삭제', '이 여행의 모든 초안 유형을 삭제할까요? 동행자의 목록에서도 사라지고 초대 링크도 사용할 수 없게 됩니다.', [
      { text: '취소', style: 'cancel' },
      { text: '삭제', style: 'destructive', onPress: () => { void removeDraft(snapshot); } },
    ]);
  }
  async function removeDraft(snapshot: DraftRoom) {
    if (lock.current || syncingRef.current || dragScroll.isDragging() || memoOpen.current) return;
    lock.current = true; setBusy(true);
    try {
      await draftsApi.delete(id, snapshot.version);
      router.replace('/');
    } catch (e) {
      Alert.alert('초안 삭제 실패', apiError(e));
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
  const stored = room?.draft;
  const option = stored?.options?.find((entry) => entry.id === (previewId ?? stored.selectedOption));
  const draft = stored && option ? { ...stored, days: option.days, warnings: option.warnings, selectedOption: option.id } : stored;
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
          <TravelDetailsSummary value={draft.travelDetails} />
          <TripActions disabled={busy || syncing || dragging}
            onMap={() => router.push(`/trip/0/map?draftId=${id}&optionId=${draft.selectedOption ?? ''}`)}
            onShare={() => { void Share.share({ message: shareText(draft) }).catch(() => undefined); }}
            onInvite={() => room?.myRole === 'OWNER' ? router.push(`/trip/0/invite?draftId=${id}`) : Alert.alert('초대 권한 없음', '초대는 여행을 만든 사람만 할 수 있어요.')} />
          <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || !!memo} style={[s.outlineButton, { marginTop: 0 }, (busy || syncing || dragging) && s.disabled]} onPress={() => { void syncNow(); }}>
            <Text style={s.outlineText}>{syncing ? '동기화 중…' : '↻ 지금 동기화'}</Text>
          </Pressable>
          {room?.myRole !== 'VIEWER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging} style={[s.outlineButton, { marginTop: 0 }]} onPress={() => router.push(`/draft/${id}/edit`)}>
            <Text style={s.outlineText}>여행 정보 수정</Text>
          </Pressable>}
          {room?.myRole === 'OWNER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || !!memo} style={[s.outlineButton, { marginTop: 0, borderColor: C.danger }, (busy || syncing || dragging || !!memo) && s.disabled]} onPress={deleteDraft}>
            <Text style={[s.outlineText, { color: C.danger }]}>여행 계획 삭제</Text>
          </Pressable>}
          {!!error && <Text style={s.warnText}>{error}</Text>}
          {!!draft.warnings?.length && <View style={s.warnBox}>{draft.warnings.map((w, i) => <Text key={i} style={s.warnText}>• {userMessage(w)}</Text>)}</View>}
          <View style={s.memberBox}>
            <Text style={s.sectionTitleSmall}>함께 계획하는 동행자 {companions.length}명</Text>
            {companions.length === 0 && <Text style={s.mutedSmall}>아직 참여한 동행자가 없어요.</Text>}
            {companions.map((m) => <Text key={m.userId} style={s.memberNames}>{m.name} ({ROLE_LABEL[m.role]})</Text>)}
          </View>
          <RequestReflection draft={draft} />
          <View style={draft.options?.length ? { marginTop: 16, marginBottom: 16, borderWidth: 1, borderColor: '#CCD5E6', borderRadius: 14, backgroundColor: '#fff' } : undefined}>
          {!!draft.options?.length && <View style={{ flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: '#CCD5E6' }}>
            {draft.options.map((entry, index) => <Pressable key={entry.id} accessibilityRole="tab"
              accessibilityState={{ selected: draft.selectedOption === entry.id, disabled: busy || syncing || dragging || !!memo }} disabled={busy || syncing || dragging || !!memo}
              onPress={() => { void switchOption(entry.id); }}
              style={{ flex: 1, minHeight: 56, paddingHorizontal: 6, paddingVertical: 12, alignItems: 'center', justifyContent: 'center',
                borderRightWidth: index < draft.options!.length - 1 ? 1 : 0, borderRightColor: '#CCD5E6',
                borderTopLeftRadius: index === 0 ? 13 : 0, borderTopRightRadius: index === draft.options!.length - 1 ? 13 : 0,
                backgroundColor: draft.selectedOption === entry.id ? C.primary : '#F3F5FA' }}>
              <Text style={{ color: draft.selectedOption === entry.id ? '#fff' : C.sub, fontSize: 13, fontWeight: '800', textAlign: 'center' }}>{draftOptionLabel(entry.label)}</Text>
            </Pressable>)}
          </View>}
          <View style={draft.options?.length ? { padding: 12 } : undefined}>
          {!!draft.options?.length && <Text style={s.mutedSmall}>{option?.description ?? draft.options.find((entry) => entry.id === draft.selectedOption)?.description}</Text>}
          {draft.days.map((day, dayIdx) => <View key={day.dayNumber}>
            <Text style={[s.dayLabel, { color: DAY_COLORS[dayIdx % DAY_COLORS.length] }]}>DAY {day.dayNumber}{day.date ? ` · ${shortDate(day.date)}` : ''}</Text>
            <TransportDeparture value={draft.travelDetails} dayNumber={day.dayNumber} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} />
              <DraggablePlaces places={day.places} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} editable={canEdit} busy={busy || syncing} scroll={dragScroll}
              onReorder={(from, to) => updatePlaces(dayIdx, (places) => reorderItems(places, from, to).map((p, i) => draft.travelDetails ? { ...p, visitTime: places[i].visitTime } : p))}
              onDelete={(_p, i) => updatePlaces(dayIdx, (places) => places.filter((_, j) => i !== j))}
              onEditMemo={(_p, i) => { memoOpen.current = true; setMemo({ day: dayIdx, index: i, version: room!.version }); }} />
            <TransportReturnArrival value={draft.travelDetails} dayNumber={day.dayNumber} dayCount={draft.days.length} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} />
          </View>)}
          </View>
          </View>
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
