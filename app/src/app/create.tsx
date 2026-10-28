import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../services/api';
import type { TravelDetails } from '../../services/trips';
import { TravelDetailsInput, DEFAULT_TRAVEL_DETAILS, travelDetailsError } from '@/components/travel-details-input';
import { tripsApi } from '../../services/trips';
import { draftsApi } from '../../services/drafts';
import { BudgetInput } from '@/components/budget-input';
import { TripPeriodInput } from '@/components/trip-period-input';
import { DestinationInput } from '@/components/destination-input';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { STYLE_OPTIONS, formatBudget, tripDayCount } from '@/lib/format';
import { C, s } from '@/lib/styles';

export default function CreateRoute() {
  return <RequireAuth><CreateScreen /></RequireAuth>;
}


function CreateScreen() {
  const [travelDetails, setTravelDetails] = useState<TravelDetails>(DEFAULT_TRAVEL_DETAILS);
  const [title, setTitle] = useState('');
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [budget, setBudget] = useState('');
  const [styles, setStyles] = useState<string[]>([]);
  const [request, setRequest] = useState('');
  const [busy, setBusy] = useState<'generate' | null>(null);
  const lock = useRef(false);

  function toggleStyle(v: string) {
    setStyles((cur) => (cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v]));
  }

  async function generate() {
    const dest = destination.trim();
    const dayCount = tripDayCount(startDate, endDate);
    if (!dest) return Alert.alert('목적지를 입력해 주세요', '예: 부산, 제주, 경주');
    if (dayCount < 1 || dayCount > 7) return Alert.alert('여행 기간을 선택해 주세요', '캘린더에서 시작일과 종료일을 선택해 주세요. 최대 7일까지 가능합니다.');
    const detailsError = travelDetailsError(travelDetails, dayCount);
    if (detailsError) return Alert.alert('숙소·교통 정보를 확인해 주세요', detailsError);
    if (lock.current) return;
    lock.current = true; setBusy('generate');
    try {
      const result = await tripsApi.generate({
        destination: dest,
        startDate: startDate.trim() || undefined,
        days: dayCount,
        budget: formatBudget(budget) || undefined,
        styles, travelDetails,
        request: request.trim() || undefined,
      });
      const room = await draftsApi.create({ ...result, title: title.trim() || result.title });
      router.replace(`/draft/${room.id}`);
    } catch (e) {
      Alert.alert('일정 생성 실패', apiError(e));
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
          <Text style={[s.sectionTitle, { marginTop: 18 }]}>AI가 일정을 짜고 있어요</Text>
          <Text style={[s.subtitle, { textAlign: 'center' }]}>장소 추천 후 지도에서 실제 장소인지 확인합니다.{'\n'}20~60초 정도 걸릴 수 있어요.</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="새 여행 만들기" />
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <Text style={s.eyebrow}>여행 계획의 첫 단계</Text>
        <Text style={s.title}>어디로 떠나볼까요?</Text>
        <Text style={s.subtitle}>조건을 입력하면 AI가 날짜별 일정 초안을 만들어 드려요.</Text>

        <Text style={s.label}>여행 이름</Text>
        <TextInput value={title} onChangeText={setTitle} placeholder="예: 친구들과 떠나는 부산 여행" style={s.input} maxLength={100} />
        <Text style={s.mutedSmall}>입력하지 않으면 여행 이름을 자동으로 만들어요.</Text>

        <Text style={s.label}>목적지 <Text style={s.required}>*</Text></Text>
        <DestinationInput value={destination} onChange={setDestination} />

        <Text style={s.label}>여행 기간 <Text style={s.required}>*</Text></Text>
        <TripPeriodInput start={startDate} end={endDate} onChange={(start, end) => { setStartDate(start); setEndDate(end); }} />

        <TravelDetailsInput destination={destination} value={travelDetails} onChange={setTravelDetails} startDate={startDate} days={tripDayCount(startDate, endDate)} />

        <Text style={s.label}>예산</Text>
        <BudgetInput value={budget} onChange={setBudget} />

        <Text style={s.label}>여행 스타일</Text>
        <View style={s.chips}>
          {STYLE_OPTIONS.map((item) => (
            <Pressable key={item} onPress={() => toggleStyle(item)} style={[s.chip, styles.includes(item) && s.chipActive]}>
              <Text style={[s.chipText, styles.includes(item) && s.chipTextActive]}>{item}</Text>
            </Pressable>
          ))}
        </View>

        <Text style={s.label}>추가 요청</Text>
        <TextInput value={request} onChangeText={setRequest} placeholder="예: 부모님과 함께라 많이 걷지 않게" style={[s.input, s.inputMulti]} multiline maxLength={200} />

        <View style={s.infoBox}>
          <Text style={s.infoTitle}>이렇게 만들어져요</Text>
          <Text style={s.infoText}>AI가 장소를 추천하면 서버가 카카오맵에서 실제 장소와 좌표를 확인합니다. 결과를 검토·수정한 뒤 저장할 수 있어요.</Text>
        </View>
        <Pressable disabled={!!busy} style={[s.primary, !!busy && s.disabled]} onPress={generate}>
          <Text style={s.primaryText}>AI 일정 초안 만들기</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}
