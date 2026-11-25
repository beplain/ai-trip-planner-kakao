import { TravelStyleInput } from '@/components/travel-style-input';
import { AppTextInput as TextInput } from '@/components/app-text-input';
import { requestSummary } from '@/lib/request-preferences';
import { RequestPreferencesInput } from '@/components/request-preferences-input';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, BackHandler, Keyboard, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { draftsApi, type DraftRoom } from '../../../../services/drafts';
import type { TravelDetails } from '../../../../services/trips';
import { TravelDetailsInput, TravelDetailsSummary, travelDetailsError } from '@/components/travel-details-input';
import { combineDraftOptions } from '../../../../services/trips';
import { TripPeriodInput } from '@/components/trip-period-input';
import { DestinationInput } from '@/components/destination-input';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { styleLabel, tripDayCount, tripEndDate } from '@/lib/format';
import { destinationRegion } from '@/lib/destination-region';
import { C, s } from '@/lib/styles';
import { useScreenAlert } from '@/components/use-screen-alert';
import { useDraftEdit } from '@/hooks/use-draft-edit';
import { DraftEditNotice } from '@/components/draft-edit-notice';

export default function EditDraftRoute() {
  return <RequireAuth><EditDraftScreen /></RequireAuth>;
}

const EDIT_FIELDS = [
  ['title', '여행 이름'],
  ['destination', '목적지'],
  ['period', '여행 기간'],
  ['movement', '여행 중 이동 방식'],
  ['stays', '숙소'],
  ['transport', '교통편'],
  ['styles', '여행 스타일'],
  ['request', '추가 요청'],
] as const;

function EditDraftScreen() {
  const { alert, dialog } = useScreenAlert();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lock = useRef(false);
  const [field, setField] = useState<typeof EDIT_FIELDS[number][0] | null>(null);
  const closeField = useCallback(() => {
    Keyboard.dismiss();
    setField(null);
  }, []);

  useFocusEffect(useCallback(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (lock.current) return true;
      if (!field) return false;
      closeField();
      return true;
    });
    return () => subscription.remove();
  }, [field, closeField]));
  const [room, setRoom] = useState<DraftRoom | null>(null);
  const [travelDetails, setTravelDetails] = useState<TravelDetails | undefined>();
  const [title, setTitle] = useState('');
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [styles, setStyles] = useState<string[]>([]);
  const [request, setRequest] = useState('');
  const [busy, setBusy] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const editor = useDraftEdit(id);
  const beginEditing=editor.begin;
  const releaseEditing=editor.release;
  const [formReady,setFormReady] = useState(false);
  const populate = useCallback((r:DraftRoom) => {
    const t=r.draft;setRoom(r);setTravelDetails(t.travelDetails);setTitle(t.title);setDestination(t.destination);
    setStartDate(t.startDate??'');setEndDate(t.endDate??tripEndDate(t.startDate??'',t.days.length));
    setStyles([...new Set((t.styles??[]).map(styleLabel))]);setRequest(t.request??'');
  },[]);
  useEffect(()=>{if(editor.blocked)Keyboard.dismiss();},[editor.blocked]);
  const original = room?.draft;
  const conditionsChanged = !!original && (
    destination.trim() !== original.destination.trim()
    || startDate !== (original.startDate ?? '')
    || endDate !== (original.endDate ?? tripEndDate(original.startDate ?? '', original.days.length))
    || JSON.stringify([...styles].sort()) !== JSON.stringify([...new Set((original.styles ?? []).map(styleLabel))].sort())
    || request.trim() !== (original.request ?? '').trim()
    || JSON.stringify(travelDetails ?? null) !== JSON.stringify(original.travelDetails ?? null)
  );

  useEffect(() => {
    let active = true;
    draftsApi.get(id).then(async (r) => {
      if (!active) return;
      if (r.savedTripId) { router.replace(`/trip/${r.savedTripId}`); return; }
      if (r.myRole === 'VIEWER') { alert('보기 전용', '여행 정보를 수정할 권한이 없습니다.', [{ text: '확인', onPress: () => router.back() }]); return; }
      populate(r);
      if(await beginEditing()) {
        const latest=await draftsApi.get(id);
        if(active){populate(latest);setFormReady(true);}
      }
    }).catch((e) => { if (active) {void releaseEditing();alert('불러오기 실패', apiError(e), [{ text: '확인', onPress: () => router.back() }]);} });
    return () => { active = false; };
  }, [id, alert, populate, beginEditing, releaseEditing]);
  async function startEditing() {
    if(!await editor.begin())return;
    try {populate(await draftsApi.get(id));setFormReady(true);}
    catch(e){alert('불러오기 실패',apiError(e));void editor.release();}
  }

  function save() {
    if (!room || lock.current || !editor.mine || editor.blocked || !formReady) return;
    Keyboard.dismiss();
    if (!title.trim()) return alert('여행 이름을 입력해 주세요');
    if (!conditionsChanged) {
      if (title.trim() === room.draft.title.trim()) { router.back(); return; }
      void rename();
      return;
    }
    const days = tripDayCount(startDate, endDate);
    if (!title.trim() || !destination.trim()) return alert('이름과 목적지를 입력해 주세요');
    if (destination.trim().length > 50) return alert('목적지는 50자 이내로 입력해 주세요');
    if (days < 1 || days > 7) return alert('캘린더에서 1~7일의 여행 기간을 선택해 주세요');
    if (!destinationRegion(destination)) return alert('여행할 지역을 선택해 주세요');
    const detailsError = travelDetailsError(travelDetails, days, startDate);
    if (detailsError) return alert('숙소·교통 정보를 확인해 주세요', detailsError);
    alert('여행 조건을 변경할까요?', '같은 조건으로 만든 초안이 있으면 복원하고, 없으면 새 초안을 만듭니다.', [
      { text: '취소', style: 'cancel' },
      { text: '조건 적용', onPress: () => { void regenerate(); } },
    ]);
  }

  async function rename() {
    if (!room || lock.current) return;
    if(!await editor.begin())return;
    lock.current = true; setRenaming(true); setBusy(true);
    try {
      await draftsApi.rename(id, title.trim(), room.version);
      void editor.release();
      router.back();
    } catch (e) {
      alert(isConflict(e) ? '초안이 변경되었어요' : '이름을 저장하지 못했어요', apiError(e));
      if (isConflict(e)) router.back();
    } finally { lock.current = false; setBusy(false); setRenaming(false); }
  }

  async function regenerate() {
    if (!room || lock.current) return;
    if(!await editor.begin())return;
    lock.current = true; setBusy(true);
    try {
      const input = {
        destination: destinationRegion(destination)!, startDate: startDate || undefined,
        days: tripDayCount(startDate, endDate), budget: original?.budget || undefined,
        styles, travelDetails, request: request.trim() || undefined,
      };
      const restored = await draftsApi.restoreGenerated(id, input, title.trim(), room.version);
      if (!restored) {
        const result = await draftsApi.regenerateOptions(id, input, title.trim(), room.version);
        await draftsApi.selectRegenerated(id, combineDraftOptions(result, title), room.version);
      }
      void editor.release();
      router.back();
    } catch (e) {
      alert(isConflict(e) ? '초안이 변경되었어요' : '다시 생성하지 못했어요', apiError(e));
      if (isConflict(e)) router.back();
    } finally { lock.current = false; setBusy(false); }
  }

  if (busy) return <SafeAreaView style={s.safe}>
    <ScreenHeader title={renaming ? '여행 이름 저장 중' : '여행 조건 적용 중'} back={false} />
    <View style={s.center}><ActivityIndicator color={C.primary} size="large" /><Text style={[s.sectionTitle, { marginTop: 18 }]}>{renaming ? '여행 이름을 저장하고 있어요' : '수정한 조건을 적용하고 있어요'}</Text>{!renaming && <Text style={[s.subtitle, { textAlign: 'center' }]}>완료되면 초안 화면에서 유형을 전환할 수 있습니다. 생성에 실패하면 기존 초안이 유지됩니다.</Text>}</View>
  </SafeAreaView>;

  if (!room) {
    return <SafeAreaView style={s.safe}>{dialog}<ScreenHeader title="여행 정보 수정" /><View style={s.center}><ActivityIndicator color={C.primary} size="large" /></View></SafeAreaView>;
  }

  if(editor.blocked || !editor.mine || !formReady) return <SafeAreaView style={s.safe}>{dialog}
    <ScreenHeader title="여행 정보 확인" />
    <ScrollView contentContainerStyle={s.content}>
      <DraftEditNotice editingKey={editor.active&&!editor.mine?`${id}:${editor.editorId}`:undefined} editorName={editor.editorName} message={editor.message} blocked={editor.blocked} onRetry={editor.refresh} />
      <Text style={s.sectionTitle}>{title}</Text><Text style={s.subtitle}>{destination} · {startDate} ~ {endDate}</Text>
      <Text style={s.label}>여행 스타일</Text><Text style={s.subtitle}>{styles.map(styleLabel).join(', ')||'미선택'}</Text>
      <TravelDetailsSummary value={travelDetails} />
      <Text style={s.label}>추가 요청</Text><Text style={s.subtitle}>{requestSummary(request)}</Text>
      <Text style={s.mutedSmall}>편집을 시작하면 최신 여행 정보를 불러옵니다.</Text>
      <Pressable accessibilityRole="button" disabled={editor.active&&!editor.mine} style={[s.primary,(editor.active&&!editor.mine)&&s.disabled]} onPress={()=>{void startEditing();}}><Text style={s.primaryText}>최신 정보로 편집 시작</Text></Pressable>
    </ScrollView>
  </SafeAreaView>;

  return (
    <SafeAreaView style={s.safe}>
      {dialog}
      <ScreenHeader title={EDIT_FIELDS.find(([id]) => id === field)?.[1] ?? '여행 정보 수정'}
        onBack={field ? closeField : undefined} />
      <ScrollView key={field ?? 'menu'} contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <DraftEditNotice editingKey={editor.active&&!editor.mine?`${id}:${editor.editorId}`:undefined} editorName={editor.editorName} message={editor.message} blocked={editor.blocked} onRetry={editor.refresh} />
        {field === null && <>
          <Text accessibilityRole="header" style={s.title}>어떤 정보를 수정할까요?</Text>
          <Text style={s.subtitle}>수정할 항목을 선택한 뒤 목록에서 변경사항을 저장하세요.</Text>
          {EDIT_FIELDS.map(([id, label]) => (
            <Pressable key={id} accessibilityRole="button" style={s.tripCard} onPress={() => setField(id)}>
              <Text style={[s.tripTitle, { flex: 1 }]}>{label}</Text>
              <Text style={s.chevron}>›</Text>
            </Pressable>
          ))}
          <Pressable accessibilityRole="button" style={[s.primary, { marginTop: 16 }]} onPress={save}>
            <Text style={s.primaryText}>변경사항 저장</Text>
          </Pressable>
        </>}
        {field === 'title' && <TextInput accessibilityLabel="여행 이름" value={title} onChangeText={setTitle} style={s.input} maxLength={100} />}
        {field === 'destination' && <DestinationInput value={destination} onChange={setDestination} />}
        {field === 'period' && <TripPeriodInput start={startDate} end={endDate} onChange={(start, end) => { setStartDate(start); setEndDate(end); }} />}
        {(field === 'movement' || field === 'stays' || field === 'transport') && (
          <TravelDetailsInput section={field} destination={destination} value={travelDetails} onChange={setTravelDetails}
            startDate={startDate} days={tripDayCount(startDate, endDate)} />
        )}
        {field === 'styles' && <TravelStyleInput value={styles} onChange={setStyles} />}
        {field === 'request' && <RequestPreferencesInput value={request} onChange={setRequest} days={tripDayCount(startDate, endDate)} destination={destination} />}
        {field !== null && <Pressable accessibilityRole="button" style={[s.primary, { marginTop: 24 }]} onPress={closeField}>
          <Text style={s.primaryText}>항목 선택으로 돌아가기</Text>
        </Pressable>}
      </ScrollView>
    </SafeAreaView>
  );
}
