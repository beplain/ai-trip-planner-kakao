import { FitText } from './fit-text';
import { ItineraryDisclosure } from './itinerary-disclosure';
import { userMessage } from '../../services/user-message';
import { Linking, Pressable, Text, View } from 'react-native';

import type { Place } from '../../services/trips';
import { businessHoursNotice } from '../../services/business-hours';
import { openDirections, openPlacePage } from '@/lib/kakao-map';
import { C, s } from '@/lib/styles';

type Props = {
  place: Place;
  visitDate?: string;
  index: number;
  isLast: boolean;
  color?: string;
  editable: boolean;
  busy?: boolean;
  onDelete?: () => void;
  onEditMemo?: () => void;
};

const GENERIC_REASON = '카카오맵에서 확인한 장소로 이동 가능한 시간대에 배치했습니다.';
const CLOSURE_NOTICE = '임시휴무는 방문 전 확인해 주세요.';
const CATEGORY_REASONS: Record<string, string> = {
  관광: '여행지의 풍경과 분위기를 둘러보세요.',
  식당: '이곳에서 여행 중 식사를 즐겨 보세요.',
  카페: '커피나 디저트와 함께 잠시 쉬어 가세요.',
  쇼핑: '여행 중에 들러 상품을 둘러보세요.',
  자연: '자연 풍경을 감상하며 여유를 즐겨 보세요.',
  문화: '여행지의 문화와 이야기를 만나 보세요.',
  체험: '여행지에서 새로운 활동을 경험해 보세요.',
};

export function PlaceCard({ place, visitDate, index, isLast, color = C.primary, editable, busy, onDelete, onEditMemo }: Props) {
  const hours = businessHoursNotice(place, visitDate);
  const reason = place.reason?.replace(GENERIC_REASON, '').replace(/\s*·\s*$/, '').trim();
  const note = place.note?.trim();
  const hasUserNote = note && !/^임시\s*휴무는\s*방문\s*전\s*확인\s*해\s*주세요[.!]?$/.test(note);
  const meal = /^식사 일정 · (아침|점심|저녁) · (.+)$/.exec(reason ?? '');
  const category = place.category ?? '';
  const recommendation = meal ? `${meal[1]} 식사로 ${meal[2]} 메뉴를 즐겨 보세요.`
    : reason && reason !== `${category} 방문` ? reason
      : !place.fixedEventId && (CATEGORY_REASONS[category] ?? '여행 중에 들러 둘러보세요.');
  return (
    <View style={s.placeCard}>
      <View style={s.timeline}>
        <View style={[s.dot, { backgroundColor: color }]} />
        {!isLast && <View style={s.line} />}
      </View>
      <ItineraryDisclosure key={`${place.fixedEventId ?? place.placeId ?? place.id ?? place.name}`} name={place.name} summary={<>
        <Text style={[s.placeTime, { color }]}>{place.visitTime ? `${place.visitTime} · ` : ''}장소 {index + 1}{place.category ? ` · ${place.category}` : ''}</Text>
        {!!place.fixedEventId && !place.fixedEventId.startsWith('stay:') && <Text style={[s.badgeText, { color: C.primary }]}>고정 일정 · 여행 정보에서 변경</Text>}
        <FitText fontSize={16} style={s.placeName}>{place.name}</FitText>
        {!!place.address && <Text style={s.placeAddr}>{place.address}</Text>}
        </>}>
        <View style={[s.badge, { backgroundColor: place.verified ? '#E7F6EE' : C.warnBg }]}>
          <Text style={[s.badgeText, { color: place.verified ? C.ok : C.warnText }]}>
            {place.fixedEventId ? place.verified ? '카카오맵에서 선택한 장소' : '사용자가 지정한 고정 일정' : place.verified ? '카카오맵에서 확인된 장소' : '지도 미확인 · 방문 전 확인 필요'}
          </Text>
        </View>
        {!place.fixedEventId && <View style={{ marginTop: 6 }}>
          <Text style={[s.mutedSmall, { color: hours.verified ? C.ok : C.warnText }]}>{hours.label}</Text>
        </View>}
        {!place.fixedEventId && place.verified && <Text style={s.placeNote}>{CLOSURE_NOTICE}</Text>}
        {!!recommendation && <Text style={s.placeNote}>💡 {recommendation}</Text>}
        {hasUserNote && <Text style={s.placeNote}>💡 {note.startsWith('샘플 일정입니다. AI 키') ? userMessage(note) : note}</Text>}

        {editable && !place.fixedEventId && (
          <View style={s.placeActions}>
            {onEditMemo && <Pressable disabled={busy} onPress={onEditMemo} style={s.smallButton}><Text style={s.smallButtonText}>메모·시간</Text></Pressable>}
            {onDelete && <Pressable disabled={busy} onPress={onDelete} style={s.deleteButton}><Text style={s.deleteText}>삭제</Text></Pressable>}
          </View>
        )}

        <View style={s.linkRow}>
          <Pressable onPress={() => { void openDirections(place); }}><Text style={s.linkText}>길찾기</Text></Pressable>
          <Pressable onPress={() => openPlacePage(place)}>
            <Text style={s.linkText}>{place.placeUrl ? '영업시간·예약 (카카오맵)' : '카카오맵에서 검색'}</Text>
          </Pressable>
          {!!place.phone && (
            <Pressable onPress={() => Linking.openURL(`tel:${place.phone!.replace(/[^0-9+]/g, '')}`).catch(() => undefined)}>
              <Text style={s.linkText}>전화</Text>
            </Pressable>
          )}
        </View>
      </ItineraryDisclosure>
    </View>
  );
}
