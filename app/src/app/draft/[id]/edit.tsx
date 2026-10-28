import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError, isConflict } from '../../../../services/api';
import { draftsApi, type DraftRoom } from '../../../../services/drafts';
import type { TravelDetails } from '../../../../services/trips';
import { TravelDetailsInput, travelDetailsError } from '@/components/travel-details-input';
import { BudgetInput } from '@/components/budget-input';
import { TripPeriodInput } from '@/components/trip-period-input';
import { DestinationInput } from '@/components/destination-input';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { STYLE_OPTIONS, formatBudget, tripDayCount, tripEndDate } from '@/lib/format';
import { C, s } from '@/lib/styles';

export default function EditDraftRoute() {
  return <RequireAuth><EditDraftScreen /></RequireAuth>;
}

function EditDraftScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const lock = useRef(false);
  const [room, setRoom] = useState<DraftRoom | null>(null);
  const [travelDetails, setTravelDetails] = useState<TravelDetails | undefined>();
  const [title, setTitle] = useState('');
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [budget, setBudget] = useState('');
  const [styles, setStyles] = useState<string[]>([]);
  const [request, setRequest] = useState('');
  const [busy, setBusy] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const original = room?.draft;
  const conditionsChanged = !!original && (
    destination.trim() !== original.destination.trim()
    || startDate !== (original.startDate ?? '')
    || endDate !== (original.endDate ?? tripEndDate(original.startDate ?? '', original.days.length))
    || formatBudget(budget) !== formatBudget(original.budget)
    || JSON.stringify([...styles].sort()) !== JSON.stringify([...(original.styles ?? [])].sort())
    || request.trim() !== (original.request ?? '').trim()
    || JSON.stringify(travelDetails ?? null) !== JSON.stringify(original.travelDetails ?? null)
  );

  useEffect(() => {
    let active = true;
    draftsApi.get(id).then((r) => {
      if (!active) return;
      if (r.savedTripId) { router.replace(`/trip/${r.savedTripId}`); return; }
      if (r.myRole === 'VIEWER') { Alert.alert('보기 전용', '여행 정보를 수정할 권한이 없습니다.', [{ text: '확인', onPress: () => router.back() }]); return; }
      const t = r.draft; setRoom(r); setTravelDetails(t.travelDetails);
      setTitle(t.title); setDestination(t.destination); setStartDate(t.startDate ?? '');
      setEndDate(t.endDate ?? tripEndDate(t.startDate ?? '', t.days.length)); setBudget(t.budget ?? ''); setStyles(t.styles ?? []); setRequest(t.request ?? '');
    }).catch((e) => { if (active) Alert.alert('불러오기 실패', apiError(e), [{ text: '확인', onPress: () => router.back() }]); });
    return () => { active = false; };
  }, [id]);

  function save() {
    if (!room || lock.current) return;
    if (!title.trim()) return Alert.alert('여행 이름을 입력해 주세요');
    if (!conditionsChanged) {
      if (title.trim() === room.draft.title.trim()) { router.back(); return; }
      void rename();
      return;
    }
    const days = tripDayCount(startDate, endDate);
    if (!title.trim() || !destination.trim()) return Alert.alert('이름과 목적지를 입력해 주세요');
    if (destination.trim().length > 50) return Alert.alert('목적지는 50자 이내로 입력해 주세요');
    if (days < 1 || days > 7) return Alert.alert('캘린더에서 1~7일의 여행 기간을 선택해 주세요');
    const detailsError = travelDetailsError(travelDetails, days);
    if (detailsError) return Alert.alert('숙소·교통 정보를 확인해 주세요', detailsError);
    Alert.alert('새 초안으로 다시 만들까요?', '수정한 조건으로 AI가 일정을 처음부터 생성합니다. 기존 장소·순서·시간·메모는 새 일정으로 교체되며, 동행자와 초대 링크는 유지됩니다. AI 모드에서는 API 사용량이 발생합니다.', [
      { text: '취소', style: 'cancel' },
      { text: '새 초안 만들기', onPress: () => { void regenerate(); } },
    ]);
  }

  async function rename() {
    if (!room || lock.current) return;
    lock.current = true; setRenaming(true); setBusy(true);
    try {
      await draftsApi.rename(id, title.trim(), room.version);
      router.back();
    } catch (e) {
      Alert.alert(isConflict(e) ? '초안이 변경되었어요' : '이름을 저장하지 못했어요', apiError(e));
      if (isConflict(e)) router.back();
    } finally { lock.current = false; setBusy(false); setRenaming(false); }
  }

  async function regenerate() {
    if (!room || lock.current) return;
    lock.current = true; setBusy(true);
    try {
      await draftsApi.regenerate(id, {
        destination: destination.trim(), startDate: startDate || undefined,
        days: tripDayCount(startDate, endDate), budget: formatBudget(budget) || undefined,
        styles, travelDetails, request: request.trim() || undefined,
      }, title.trim(), room.version);
      router.back();
    } catch (e) {
      Alert.alert(isConflict(e) ? '초안이 변경되었어요' : '다시 생성하지 못했어요', apiError(e));
      if (isConflict(e)) router.back();
    } finally { lock.current = false; setBusy(false); }
  }

  if (busy) return <SafeAreaView style={s.safe}>
    <ScreenHeader title={renaming ? '여행 이름 저장 중' : '초안 다시 만드는 중'} back={false} />
    <View style={s.center}><ActivityIndicator color={C.primary} size="large" /><Text style={[s.sectionTitle, { marginTop: 18 }]}>{renaming ? '여행 이름을 저장하고 있어요' : '수정한 조건으로 새 일정을 만들고 있어요'}</Text>{!renaming && <Text style={[s.subtitle, { textAlign: 'center' }]}>완료되면 새 초안으로 교체합니다. 생성에 실패하면 기존 초안이 유지됩니다.</Text>}</View>
  </SafeAreaView>;

  if (!room) {
    return <SafeAreaView style={s.safe}><ScreenHeader title="여행 정보 수정" /><View style={s.center}><ActivityIndicator color={C.primary} size="large" /></View></SafeAreaView>;
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="여행 정보 수정" />
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <Text style={s.label}>여행 이름</Text>
        <TextInput value={title} onChangeText={setTitle} style={s.input} maxLength={100} />
        <Text style={s.label}>목적지</Text>
        <DestinationInput value={destination} onChange={setDestination} />
        <Text style={s.label}>여행 기간 <Text style={s.required}>*</Text></Text>
        <TripPeriodInput start={startDate} end={endDate} onChange={(start, end) => { setStartDate(start); setEndDate(end); }} />
        <TravelDetailsInput destination={destination} value={travelDetails} onChange={setTravelDetails} startDate={startDate} days={tripDayCount(startDate, endDate)} />
        <Text style={s.label}>예산</Text>
        <BudgetInput value={budget} onChange={setBudget} />
        <Text style={s.label}>여행 스타일</Text>
        <View style={s.chips}>
          {STYLE_OPTIONS.map((item) => (
            <Pressable key={item} onPress={() => setStyles((c) => (c.includes(item) ? c.filter((x) => x !== item) : [...c, item]))} style={[s.chip, styles.includes(item) && s.chipActive]}>
              <Text style={[s.chipText, styles.includes(item) && s.chipTextActive]}>{item}</Text>
            </Pressable>
          ))}
        </View>
        <Text style={s.label}>추가 요청</Text>
        <TextInput value={request} onChangeText={setRequest} style={[s.input, s.inputMulti]} multiline maxLength={200} placeholder="예: 매일 쇼핑 위주로, 많이 걷지 않게" />
        <View style={s.infoBox}>
          <Text style={s.infoText}>여행 이름만 변경하면 기존 일정은 유지됩니다. 목적지·기간·숙소·교통·예산·스타일·추가 요청을 변경하면 모든 날짜의 장소·순서·시간·메모를 새로 생성합니다.</Text>
        </View>
        <Pressable disabled={busy} style={[s.primary, busy && s.disabled]} onPress={save}>
          <Text style={s.primaryText}>{conditionsChanged ? '수정 내용 저장' : '여행 이름 저장'}</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}
