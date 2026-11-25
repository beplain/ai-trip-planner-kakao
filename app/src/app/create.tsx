import { TravelStyleInput } from '@/components/travel-style-input';
import { AppTextInput as TextInput } from '@/components/app-text-input';
import { RequestPreferencesInput } from '@/components/request-preferences-input';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, Alert, BackHandler, Keyboard, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../services/api';
import type { TravelDetails, TripDraft } from '../../services/trips';
import { TravelDetailsInput, DEFAULT_TRAVEL_DETAILS, travelDetailsError } from '@/components/travel-details-input';
import { tripsApi, combineDraftOptions } from '../../services/trips';
import { draftsApi } from '../../services/drafts';
import { TripPeriodInput } from '@/components/trip-period-input';
import { DestinationInput } from '@/components/destination-input';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { tripDayCount } from '@/lib/format';
import { destinationRegion } from '@/lib/destination-region';
import { C, s } from '@/lib/styles';

export default function CreateRoute() {
  return <RequireAuth><CreateScreen /></RequireAuth>;
}


const STEPS = [
  { id: 'title', heading: '여행 이름을 지어볼까요?', hint: '비워두면 목적지와 여행 기간에 맞춰 이름을 만들어요.' },
  { id: 'destination', heading: '어디로 떠나시나요?', hint: '지역 이름을 검색하거나 지도에서 선택하세요.' },
  { id: 'period', heading: '언제 떠나시나요?', hint: '캘린더에서 시작일과 종료일을 선택하세요. 최대 7일까지 가능해요.' },
  { id: 'movement', heading: '여행지에서 어떻게 이동하나요?', hint: '이동 방식에 맞춰 방문 순서를 계획해요.' },
  { id: 'stays', heading: '어디에서 묵으시나요?', hint: '숙소가 정해지면 체크인·체크아웃 시각을 일정에 반영해요.' },
  { id: 'transport', heading: '어떻게 오가시나요?', hint: '가는 편과 오는 편을 추가하면 도착과 출발 시각을 일정에 반영해요.' },
  { id: 'styles', heading: '어떤 여행을 하고 싶으세요?', hint: '원하는 여행 스타일을 여러 개 선택할 수 있어요.' },
  { id: 'request', heading: '더 반영할 내용이 있나요?', hint: '포함·제외할 장소와 방문 횟수를 간단히 선택해 주세요.' },
] as const;

function CreateScreen() {
  const [travelDetails, setTravelDetails] = useState<TravelDetails>(DEFAULT_TRAVEL_DETAILS);
  const [title, setTitle] = useState('');
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [styles, setStyles] = useState<string[]>([]);
  const [request, setRequest] = useState('');
  const [busy, setBusy] = useState<'generate' | null>(null);
  const lock = useRef(false);
  const pendingDraft = useRef<{ key: string; draft: TripDraft } | null>(null);
  const [generationError, setGenerationError] = useState<{ message: string; savedResult: boolean } | null>(null);
  const [stepIndex, setStepIndex] = useState(0);
  const step = STEPS[stepIndex];
  const lastStep = stepIndex === STEPS.length - 1;
  const canSkip = (step.id === 'title' && !title.trim())
    || (step.id === 'stays' && !travelDetails.stays?.length)
    || (step.id === 'transport' && !travelDetails.transport);
  const days = tripDayCount(startDate, endDate);

  const previousStep = useCallback(() => {
    Keyboard.dismiss();
    setStepIndex((index) => Math.max(0, index - 1));
  }, []);

  useFocusEffect(useCallback(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (lock.current) return true;
      if (stepIndex === 0) return false;
      previousStep();
      return true;
    });
    return () => subscription.remove();
  }, [previousStep, stepIndex]));

  function nextStep() {
    if (step.id === 'destination' && !destinationRegion(destination)) {
      return Alert.alert('목적지를 선택해 주세요', '예: 부산, 경주, 대전');
    }
    if (step.id === 'period' && !(days >= 1 && days <= 7)) {
      return Alert.alert('여행 기간을 선택해 주세요', step.hint);
    }
    if (step.id === 'stays' || step.id === 'transport') {
      const details = step.id === 'stays' ? { ...travelDetails, transport: undefined } : { ...travelDetails, stays: [] };
      const error = travelDetailsError(details, days, startDate);
      if (error) return Alert.alert('입력한 정보를 확인해 주세요', error);
    }
    Keyboard.dismiss();
    setStepIndex((index) => Math.min(index + 1, STEPS.length - 1));
  }

  async function generate() {
    const dest = destinationRegion(destination);
    const dayCount = tripDayCount(startDate, endDate);
    if (!dest) return Alert.alert('목적지를 선택해 주세요', '예: 부산, 경주, 대전');
    if (dayCount < 1 || dayCount > 7) return Alert.alert('여행 기간을 선택해 주세요', '캘린더에서 시작일과 종료일을 선택해 주세요. 최대 7일까지 가능합니다.');
    const detailsError = travelDetailsError(travelDetails, dayCount, startDate);
    if (detailsError) return Alert.alert('숙소·교통 정보를 확인해 주세요', detailsError);
    if (lock.current) return;
    Keyboard.dismiss();
    lock.current = true; setGenerationError(null); setBusy('generate');
    try {
      const input = {
        destination: dest,
        startDate: startDate.trim() || undefined,
        days: dayCount,
        styles, travelDetails,
        request: request.trim() || undefined,
      };
      const key = JSON.stringify({ input, title });
      if (pendingDraft.current?.key !== key) {
        pendingDraft.current = null;
        const result = await tripsApi.generateOptions(input);
        pendingDraft.current = { key, draft: combineDraftOptions(result, title) };
      }
      const room = await draftsApi.create(pendingDraft.current!.draft);
      router.replace(`/draft/${room.id}`);
    } catch (e) {
      setGenerationError({ message: apiError(e), savedResult: pendingDraft.current !== null });
    } finally {
      lock.current = false; setBusy(null);
    }
  }

  if (busy === 'generate') {
    return (
      <SafeAreaView style={s.safe}>
        <ScreenHeader title="일정 만드는 중" back={false} />
        <View style={s.center}>
          <ActivityIndicator size="large" color={C.primary} />
          <Text style={[s.sectionTitle, { marginTop: 18 }]}>선택할 초안을 만들고 있어요</Text>
          <Text style={[s.subtitle, { textAlign: 'center' }]}>AI가 추천한 장소를 지도에서 확인하고 최대 3가지 초안으로 구성합니다.{'\n'}여행 기간과 지도 조회 상황에 따라 시간이 걸릴 수 있어요.</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="새 여행 만들기" onBack={stepIndex > 0 ? previousStep : undefined} />
      <ScrollView key={step.id} style={{ flex: 1 }} contentContainerStyle={[s.content, { flexGrow: 1 }]} keyboardShouldPersistTaps="handled">
        <Text style={s.eyebrow}>{stepIndex + 1} / {STEPS.length}</Text>
        <View style={{ flexDirection: 'row', gap: 4, marginBottom: 28 }} aria-hidden>
          {STEPS.map(({ id }, index) => (
            <View key={id} style={{ flex: 1, height: 4, borderRadius: 2, backgroundColor: index <= stepIndex ? C.primary : C.border }} />
          ))}
        </View>
        <Text accessibilityRole="header" style={s.title}>{step.heading}</Text>
        <Text style={s.subtitle}>{step.hint}</Text>

        {step.id === 'title' && <TextInput accessibilityLabel="여행 이름" value={title} onChangeText={setTitle}
          placeholder="예: 친구들과 떠나는 부산 여행" style={s.input} maxLength={100} />}
        {step.id === 'destination' && <DestinationInput value={destination} onChange={setDestination} />}
        {step.id === 'period' && <TripPeriodInput start={startDate} end={endDate} onChange={(start, end) => { setStartDate(start); setEndDate(end); }} />}
        {(step.id === 'movement' || step.id === 'stays' || step.id === 'transport') && (
          <TravelDetailsInput section={step.id} destination={destination} value={travelDetails} onChange={setTravelDetails} startDate={startDate} days={days} />
        )}
        {step.id === 'styles' && <TravelStyleInput value={styles} onChange={setStyles} />}
        {step.id === 'request' && <RequestPreferencesInput value={request} onChange={setRequest} days={days} destination={destination} />}

        <View style={{ flex: 1, minHeight: 28 }} />
        {generationError && <View accessibilityRole="alert" style={{ marginBottom: 16, padding: 16, borderRadius: 12, backgroundColor: '#fff' }}>
          <Text style={s.sectionTitle}>{generationError.savedResult ? '초안을 저장하지 못했어요' : '일정을 생성하지 못했어요'}</Text>
          <Text style={s.subtitle}>{generationError.message}</Text>
          <Text style={s.subtitle}>{generationError.savedResult ? '생성된 초안은 유지하고 있어요. 입력을 변경하지 않고 다시 누르면 AI 생성 없이 저장을 재시도합니다.' : '입력한 내용은 유지됩니다. 아래 버튼으로 다시 시도하실 수 있어요.'}</Text>
        </View>}
        <Pressable accessibilityRole="button" style={[s.primary, { marginBottom: 0 }]} onPress={lastStep ? generate : nextStep}>
          <Text style={s.primaryText}>{lastStep ? 'AI 초안 3가지 비교하기' : canSkip ? '건너뛰기' : '다음'}</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}
