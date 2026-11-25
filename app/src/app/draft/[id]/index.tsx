import { UndoNotice, type UndoNoticeValue } from '@/components/undo-notice';
import { DeletedPlaces } from '@/components/deleted-places';
import { useScreenAlert } from '@/components/use-screen-alert';
import { ItineraryDay } from '@/components/itinerary-day';
import { TransportDeparture, TransportReturnArrival } from '@/components/transport-departure';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Share, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { draftsApi, type DraftRoom } from '../../../../services/drafts';
import type { Place, TripDraft } from '../../../../services/trips';
import { MemoModal } from '@/components/memo-modal';
import { DraggablePlaces } from '@/components/draggable-places';
import { itineraryRecommendations } from '@/lib/place-blurb';
import { RequestReflection } from '@/components/request-reflection';
import { TravelDetailsSummary } from '@/components/travel-details-input';
import { TripActions } from '@/components/trip-actions';
import { useDragScroll } from '@/hooks/use-drag-scroll';
import { reorderItems } from '@/lib/reorder';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { draftOptionLabel, dateRange, formatBudget, ROLE_LABEL, shareText, shortDate } from '@/lib/format';
import { C, DAY_COLORS, s } from '@/lib/styles';
import { useDraftEdit } from '@/hooks/use-draft-edit';
import { DraftEditNotice } from '@/components/draft-edit-notice';
import { useCrossDayDrag } from '@/hooks/use-cross-day-drag';
import { dragVisit } from '@/lib/drag-visit';

export default function DraftRoute() { return <RequireAuth><DraftScreen /></RequireAuth>; }

function DraftScreen() {
  const { alert, dialog } = useScreenAlert();
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
  const [undoNotice, setUndoNotice] = useState<UndoNoticeValue | null>(null);
  const [switchingOption,setSwitchingOption] = useState(false);
  const optionRequest = useRef<string|null>(null);
  const optionRunning = useRef(false);
  const apply = useCallback((next: DraftRoom) => {
    current.current = next; setRoom(next); setError('');
    if (next.myRole !== 'VIEWER' && !optionRunning.current) setPreviewId(null);
    if (next.savedTripId) router.replace(`/trip/${next.savedTripId}`);
  }, []);
  const onUnlocked = useCallback(() => {
    if(lock.current||syncingRef.current||memoOpen.current||dragScroll.isDragging())return;
    void draftsApi.get(id).then(next=>{
      if(!lock.current&&!syncingRef.current&&!memoOpen.current&&!dragScroll.isDragging()&&(!current.current||next.version>=current.current.version))apply(next);
    }).catch(e=>setError(apiError(e)));
  },[id,apply,dragScroll]);
  const editor = useDraftEdit(id,onUnlocked);
  const editAction = useRef<string | null>(null);
  const releaseEdit=editor.release;
  const endEdit = useCallback(() => { editAction.current=null; void releaseEdit(); },[releaseEdit]);
  async function beginEdit(action: string): Promise<DraftRoom | null> {
    if(editAction.current||lock.current||syncingRef.current||current.current?.myRole==='VIEWER')return null;
    editAction.current=action;
    if(!await editor.begin()){editAction.current=null;return null;}
    try {
      if(current.current && editor.getStatus?.().version===current.current.version)return current.current;
      const latest=await draftsApi.get(id);if(latest.version!==current.current?.version)apply(latest);return latest;
    }
    catch(e){alert('편집을 시작하지 못했어요',apiError(e));endEdit();return null;}
  }
  async function beginDrag() {
    const version=current.current?.version;
    const latest=await beginEdit('drag');
    if(!latest)return false;
    if(latest.version!==version){setError('최신 일정으로 갱신했어요. 이동할 장소를 다시 길게 눌러 주세요.');endEdit();return false;}
    return true;
  }
  async function beginMemo(place: Place) {
    const latest=await beginEdit('memo');if(!latest)return;
    for(let day=0;day<latest.draft.days.length;day++) {
      const index=latest.draft.days[day].places.findIndex(p=>p.placeId===place.placeId&&p.name===place.name);
      if(index>=0){memoOpen.current=true;setMemo({day,index,version:latest.version});return;}
    }
    alert('장소가 변경되었어요','최신 일정을 확인해 주세요.');endEdit();
  }
  async function beginDeletePlace(place: Place) {
    const latest=await beginEdit('delete');if(!latest)return;
    for(let day=0;day<latest.draft.days.length;day++) {
      const index=latest.draft.days[day].places.findIndex(p=>p.placeId===place.placeId&&p.name===place.name);
      if(index<0)continue;
      alert('장소 삭제',`정말 ${place.name} 일정을 삭제하시겠습니까?`,[
        {text:'취소',style:'cancel',onPress:endEdit},
        {text:'삭제',style:'destructive',onPress:()=>updatePlaces(day,places=>places.filter((_,i)=>i!==index),latest.version)},
      ]);return;
    }
    alert('장소가 변경되었어요','최신 일정을 확인해 주세요.');endEdit();
  }

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

  async function change(transform: (draft: TripDraft) => TripDraft, expectedVersion?: number, onError?: (message:string)=>void) {
    if(!editAction.current && !await beginEdit('change'))return false;
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || snapshot.myRole === 'VIEWER') return false;
    if(!await editor.begin()){if(editAction.current!=='memo')endEdit();return false;}
    lock.current = true; setBusy(true);
    try {
      apply(await draftsApi.update(id, transform(snapshot.draft), expectedVersion ?? snapshot.version));
      return true;
    } catch (e) {
      if(onError)onError(apiError(e));else alert('초안 수정 실패', apiError(e));
      // Never resend stale edits over someone else's work.
      setError(apiError(e));
      if (isConflict(e)&&!memoOpen.current) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
      return false;

    } finally { lock.current = false; setBusy(false); if(editAction.current!=='memo'&&editAction.current!=='move')endEdit(); }
  }

  async function switchOption(optionId: string) {
    const snapshot = current.current;
    if (!snapshot || (lock.current&&!optionRunning.current) || syncingRef.current || memoOpen.current || dragScroll.isDragging()) return;
    if (snapshot.myRole === 'VIEWER') { setPreviewId(optionId); return; }
    setPreviewId(optionId);optionRequest.current=optionId;
    if(optionRunning.current)return;
    optionRunning.current=true;
    setSwitchingOption(true);
    try {
      const latest=await beginEdit('option');if(!latest){setPreviewId(null);return;}
      lock.current=true;setBusy(true);
      while(optionRequest.current){
        const target=optionRequest.current;optionRequest.current=null;
        const currentRoom=current.current!;
        if(currentRoom.draft.selectedOption!==target)apply(await draftsApi.switchOption(id,target,currentRoom.version));
        setPreviewId(optionRequest.current);
      }
    }
    catch (e) {
      alert('유형 전환 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
      setPreviewId(null);
    } finally { optionRequest.current=null;optionRunning.current=false;setSwitchingOption(false);lock.current = false; setBusy(false); endEdit(); }
  }

  async function save() {
    const snapshot = current.current;
    if (!snapshot || lock.current || syncingRef.current || snapshot.myRole === 'VIEWER') return;
    const latest=await beginEdit('save');if(!latest)return;
    lock.current = true; setBusy(true);
    try {
      const trip = await draftsApi.save(id, latest.version);
      router.replace(`/trip/${trip.id}`);
    } catch (e) {
      alert('저장 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
    } finally { lock.current = false; setBusy(false); endEdit(); }
  }

  async function deleteDraft() {
    const snapshot = current.current;
    if (!snapshot || snapshot.myRole !== 'OWNER' || lock.current || syncingRef.current || dragScroll.isDragging() || memoOpen.current) return;
    const latest=await beginEdit('delete-draft');if(!latest)return;
    alert('초안 삭제', '이 여행 계획을 휴지통으로 옮길까요? 모든 초안 유형을 함께 보관하며, 만든 사람이 30일 안에 복원할 수 있습니다. 보관 중에는 동행자와 초대 링크로 접근할 수 없습니다.', [
      { text: '취소', style: 'cancel', onPress:endEdit },
      { text: '삭제', style: 'destructive', onPress: () => { void removeDraft(latest); } },
    ]);
  }
  async function removeDraft(snapshot: DraftRoom) {
    if (lock.current || syncingRef.current || dragScroll.isDragging() || memoOpen.current) return;
    lock.current = true; setBusy(true);
    try {
      await draftsApi.delete(id, snapshot.version);
      router.replace('/');
    } catch (e) {
      alert('초안 삭제 실패', apiError(e));
      if (isConflict(e)) try { apply(await draftsApi.get(id)); } catch (loadError) { setError(apiError(loadError)); }
    } finally { lock.current = false; setBusy(false); endEdit(); }
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
  const canEdit = room?.myRole !== 'VIEWER' && !busy && !syncing && !editor.blocked;
  const memoPlace = memo && draft ? draft.days[memo.day]?.places[memo.index] : undefined;
  const blurbs = itineraryRecommendations(draft?.days ?? []);
  const crossDrag=useCrossDayDrag(dragScroll,{
    start:beginDrag,cancel:endEdit,error:message=>alert('일정 이동',message),
    drop:async(source,target)=>{await change(d=>({...d,days:dragVisit(d.days,source.day,source.index,target.day,target.index,d.travelDetails)}));},
  });

  return (
    <SafeAreaView style={s.safe}>{dialog}
      <UndoNotice notice={undoNotice} onDismiss={() => setUndoNotice(null)} />
      <ScreenHeader title="일정 초안 검토" onBack={() => router.replace('/')} />
      {!draft ? <View style={s.center}>{error ? <Text style={s.warnText}>{error}</Text> : <ActivityIndicator size="large" color={C.primary} />}</View> : (
        <ScrollView ref={attach} scrollEnabled={!dragging} onScroll={onScroll} scrollEventThrottle={16} onContentSizeChange={onContentSizeChange} contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
          <View style={s.detailHero}>
            <Text style={s.eyebrowLight}>{[draft.destination, `${draft.days.length}일`, dateRange(draft.startDate, draft.endDate), formatBudget(draft.budget)].filter(Boolean).join(' · ')}</Text>
            <Text style={s.detailTitle}>{draft.title}</Text>
            <Text style={s.detailMeta}>만든 사람: {creator?.name || '이름 없음'}</Text>
          </View>
          <DraftEditNotice editingKey={editor.active&&!editor.mine?`${id}:${editor.editorId}`:undefined} editorName={editor.editorName} message={editor.message} blocked={editor.blocked} onRetry={editor.refresh} />
          <TravelDetailsSummary value={draft.travelDetails} compact />
          <TripActions disabled={busy || syncing || dragging}
            onMap={() => router.push(`/trip/0/map?draftId=${id}&optionId=${draft.selectedOption ?? ''}`)}
            onShare={() => { void Share.share({ message: shareText(draft) }).catch(() => undefined); }}
            onInvite={() => room?.myRole === 'OWNER' ? router.push(`/trip/0/invite?draftId=${id}`) : alert('초대 권한 없음', '초대는 여행을 만든 사람만 할 수 있어요.')} />
          {room?.myRole !== 'VIEWER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || editor.blocked} style={[s.outlineButton, { marginTop: 0 },editor.blocked&&s.disabled]} onPress={() => {if(!editAction.current)router.push(`/draft/${id}/edit`);}}>
            <Text style={s.outlineText}>여행 정보 수정</Text>
          </Pressable>}
          {room?.myRole === 'OWNER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || !!memo || editor.blocked} style={[s.outlineButton, { marginTop: 0, borderColor: C.danger }, (busy || syncing || dragging || !!memo || editor.blocked) && s.disabled]} onPress={() => {void deleteDraft();}}>
            <Text style={[s.outlineText, { color: C.danger }]}>여행 계획 삭제</Text>
          </Pressable>}
          {!!error && <Text style={s.warnText}>{error}</Text>}
          <View style={s.memberBox}>
            <Text style={s.sectionTitleSmall}>함께 계획하는 동행자 {companions.length}명</Text>
            {companions.length === 0 && <Text style={s.mutedSmall}>아직 참여한 동행자가 없어요.</Text>}
            {companions.map((m) => <Text key={m.userId} style={s.memberNames}>{m.name} ({ROLE_LABEL[m.role]})</Text>)}
          </View>
          <RequestReflection draft={draft} />
          <View style={draft.options?.length ? { marginTop: 16, marginBottom: 16, borderWidth: 1, borderColor: '#CCD5E6', borderRadius: 14, backgroundColor: '#fff' } : undefined}>
          {!!draft.options?.length && <View style={{ flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: '#CCD5E6' }}>
            {draft.options.map((entry, index) => <Pressable key={entry.id} accessibilityRole="tab"
              accessibilityState={{ selected: draft.selectedOption === entry.id, disabled: (busy&&!switchingOption) || syncing || dragging || !!memo || (room?.myRole!=='VIEWER'&&editor.blocked) }} disabled={(busy&&!switchingOption) || syncing || dragging || !!memo || (room?.myRole!=='VIEWER'&&editor.blocked)}
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
          {draft.days.map((day, dayIdx) => <View key={day.dayNumber} ref={crossDrag.bind(dayIdx,-1,true)} collapsable={false}><ItineraryDay
            title={`DAY ${day.dayNumber}${day.date ? ` · ${shortDate(day.date)}` : ''}`}
            color={DAY_COLORS[dayIdx % DAY_COLORS.length]} disabled={dragging || busy || syncing}>
            <TransportDeparture value={draft.travelDetails} dayNumber={day.dayNumber} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} />
              <DraggablePlaces crossDay={crossDrag} dayIndex={dayIdx} blurbs={blurbs} visitDate={day.date} places={day.places} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} editable={canEdit} busy={busy || syncing} scroll={dragScroll}
              onEditStart={beginDrag} onEditCancel={endEdit}
              onReorder={(from, to) => updatePlaces(dayIdx, (places) => reorderItems(places, from, to).map((p, i) => draft.travelDetails ? { ...p, visitTime: places[i].visitTime } : p))}
              onDelete={(place) => {void beginDeletePlace(place);}}
              onEditMemo={(place) => {void beginMemo(place);}} />
            <TransportReturnArrival value={draft.travelDetails} dayNumber={day.dayNumber} dayCount={draft.days.length} color={DAY_COLORS[dayIdx % DAY_COLORS.length]} />
          </ItineraryDay></View>)}
          </View>
          </View>
          <DeletedPlaces kind="drafts" id={id} version={room!.version} editable={canEdit && !dragging && !memo} selectedOption={draft.selectedOption} onRestored={(value) => apply(value as DraftRoom)} onRefresh={syncNow} onUndoChange={setUndoNotice}
            onBeginEdit={async()=>{const latest=await beginEdit('restore');return latest?.version??null;}} onEndEdit={endEdit} />
          {room?.myRole !== 'VIEWER' && <Pressable accessibilityRole="button" disabled={busy || syncing || dragging || editor.blocked} style={[s.primary, (busy || syncing || dragging || editor.blocked) && s.disabled]} onPress={() => { void save(); }}><Text style={s.primaryText}>{busy ? '반영 중…' : '이 일정 확정하기'}</Text></Pressable>}
        </ScrollView>
      )}
      <MemoModal visible={!!memoPlace} title={memoPlace?.name ?? ''} initialTime={memoPlace?.visitTime} initialNote={memoPlace?.note}
        disabled={busy||editor.blocked} notice={editor.message||error}
        onCancel={() => {if(lock.current)return;setMemo(null);memoOpen.current=false;endEdit();}} onSave={(visitTime,note) => {
          if(!memo)return;
          void change(d=>({...d,days:d.days.map((day,index)=>index===memo.day?{...day,places:day.places.map((p,i)=>i===memo.index?{...p,visitTime:visitTime||undefined,note:note||undefined}:p)}:day)}),memo.version)
            .then(saved=>{if(saved){setMemo(null);memoOpen.current=false;endEdit();}});
        }} />
 
    </SafeAreaView>
  );
}
