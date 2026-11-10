import { router, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, Pressable, RefreshControl, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../services/api';
import { draftsApi, type DraftSummary } from '../../services/drafts';
import { tripsApi, type TripSummary } from '../../services/trips';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { useAuth } from '@/lib/auth-context';
import { ROLE_LABEL, dateRange, formatBudget, parseInviteToken } from '@/lib/format';
import { s } from '@/lib/styles';

export default function HomeRoute() {
  return <RequireAuth><HomeScreen /></RequireAuth>;
}

function HomeScreen() {
  const [tab, setTab] = useState<'trips' | 'me'>('trips');
  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right']}>
      {tab === 'trips' ? <TripsTab /> : <MeTab />}
      <View style={s.tabBar}>
        <Pressable style={s.tab} onPress={() => setTab('trips')}>
          <Text style={tab === 'trips' ? s.tabGlyph : s.tabGlyphOff}>⌂</Text>
          <Text style={tab === 'trips' ? s.tabActiveText : s.tabText}>여행</Text>
        </Pressable>
        <Pressable style={s.tab} onPress={() => setTab('me')}>
          <Text style={tab === 'me' ? s.tabGlyph : s.tabGlyphOff}>○</Text>
          <Text style={tab === 'me' ? s.tabActiveText : s.tabText}>마이</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

function TripsTab() {
  const [trips, setTrips] = useState<TripSummary[]>([]);
  const [drafts, setDrafts] = useState<DraftSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [saved, pending] = await Promise.all([tripsApi.list(), draftsApi.listSummaries()]);
      setTrips(saved); setDrafts(pending);
      setError(null);
    } catch (e) {
      setError(apiError(e));
    } finally {
      setLoading(false);
      setLoaded(true);
    }
  }, []);

  // 화면에 돌아올 때마다 목록 갱신 (생성/삭제/초대 수락 반영)
  useFocusEffect(useCallback(() => { void refresh(); }, [refresh]));

  const q = query.trim().toLowerCase();
  const visible = trips.filter((t) => `${t.title} ${t.destination}`.toLowerCase().includes(q));

  return (
    <>
      <ScreenHeader title="여행 플래너" back={false} />
      <ScrollView contentContainerStyle={s.content} refreshControl={<RefreshControl refreshing={loading} onRefresh={refresh} />}>
        <View style={s.welcome}>
          <Text style={s.eyebrowLight}>AI와 함께 계획하는 여행</Text>
          <Text style={s.welcomeTitle}>다음 여행은{'\n'}어디로 갈까요?</Text>
          <Text style={s.welcomeSub}>목적지와 취향을 알려주면 AI가 일정 초안을 만들어 드려요.</Text>
        </View>
        <Pressable style={s.primary} onPress={() => router.push('/create')}>
          <Text style={s.primaryText}>＋  AI로 새 여행 만들기</Text>
        </Pressable>

        {drafts.length > 0 && <View style={{ marginTop: 24 }}>
          <Text style={s.sectionTitle}>함께 편집 중인 초안</Text>
          <Text style={s.mutedSmall}>눌러서 여행 계획을 이어서 편집하세요</Text>
          {drafts.map((room) => <Pressable key={room.id} style={s.tripCard} onPress={() => router.push(`/draft/${room.id}`)}>
            <View style={s.tripInfo}><Text style={s.tripTitle}>{room.title}</Text><Text style={s.tripMeta}>{room.destination} · 동행자 {room.companionCount}명</Text></View><Text style={s.chevron}>›</Text>
          </Pressable>)}
        </View>}
        <View style={s.sectionHead}>
          <Text style={s.sectionTitle}>내 여행</Text>
          <Text style={s.sectionHint}>{trips.length}개 · 아래로 당겨 새로고침</Text>
        </View>
        <TextInput value={query} onChangeText={setQuery} placeholder="목적지나 여행 이름 검색" style={s.search} />

        {error && (
          <View style={s.warnBox}>
            <Text style={s.warnTitle}>목록을 불러오지 못했어요</Text>
            <Text style={s.warnText}>{error}</Text>
            <Pressable onPress={refresh}><Text style={[s.linkText, { marginTop: 8 }]}>다시 시도</Text></Pressable>
          </View>
        )}

        {loaded && !error && visible.length === 0 ? (
          <View style={s.emptyCard}>
            <Text style={s.emptyTitle}>{trips.length ? '검색 결과가 없어요' : '아직 여행이 없어요'}</Text>
            <Text style={s.empty}>새 여행을 만들거나 초대 링크로 참여해 보세요.</Text>
          </View>
        ) : visible.map((trip) => (
          <Pressable key={trip.id} onPress={() => router.push(`/trip/${trip.id}`)} style={s.tripCard}>
            <View style={s.tripIcon}><Text style={s.tripEmoji}>✦</Text></View>
            <View style={s.tripInfo}>
              <Text style={s.tripTitle} numberOfLines={1}>{trip.title}</Text>
              <Text style={s.tripMeta}>
                {[trip.destination, `${trip.dayCount}일`, dateRange(trip.startDate, trip.endDate), trip.budget ? `예산 ${formatBudget(trip.budget)}` : ''].filter(Boolean).join(' · ')}
              </Text>
              <Text style={s.tripStyles}>
                장소 {trip.placeCount}곳 · 동행 {Math.max(0, trip.memberCount - 1)}명 · {ROLE_LABEL[trip.myRole]}
              </Text>
            </View>
            <Text style={s.chevron}>›</Text>
          </Pressable>
        ))}
      </ScrollView>
    </>
  );
}

function MeTab() {
  const { user, logout } = useAuth();
  const [code, setCode] = useState('');

  function join() {
    const token = parseInviteToken(code);
    if (!token) return Alert.alert('초대 코드를 확인해 주세요', '받은 초대 링크 전체나 코드를 붙여 넣어 주세요.');
    setCode('');
    router.push(`/invite/${token}`);
  }

  return (
    <>
      <ScreenHeader title="마이" back={false} />
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <View style={s.memberBox}>
          <Text style={s.sectionTitleSmall}>{user?.name}</Text>
        </View>

        <Text style={s.label}>초대 링크로 참여</Text>
        <TextInput value={code} onChangeText={setCode} placeholder="tripplanner://invite/... 또는 초대 코드" style={s.input} autoCapitalize="none" />
        <Pressable style={[s.outlineButton, !code.trim() && s.disabled]} disabled={!code.trim()} onPress={join}>
          <Text style={s.outlineText}>초대 확인하기</Text>
        </Pressable>

        <Pressable
          style={[s.outlineButton, { marginTop: 28 }]}
          onPress={() => Alert.alert('로그아웃', '로그아웃할까요?', [
            { text: '취소', style: 'cancel' },
            { text: '로그아웃', style: 'destructive', onPress: () => { void logout(); } },
          ])}>
          <Text style={s.outlineText}>로그아웃</Text>
        </Pressable>
      </ScrollView>
    </>
  );
}
