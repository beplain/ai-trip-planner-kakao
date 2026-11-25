import { FitText } from './fit-text';
import { ItineraryDisclosure } from './itinerary-disclosure';
import { userMessage } from '../../services/user-message';
import { Linking, Pressable, Text, View } from 'react-native';

import type { Place } from '../../services/trips';
import { businessHoursNotice } from '../../services/business-hours';
import { openDirections, openPlacePage } from '@/lib/kakao-map';
import { placeRecommendation } from '@/lib/place-blurb';
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
  onMoveVisit?: () => void;
  onReplace?: () => void;
  blurb?: string;
};

const CLOSURE_NOTICE = '임시휴무는 방문 전 확인해 주세요.';

export function PlaceCard({ place, visitDate, index, isLast, color = C.primary, editable, busy, onDelete, onEditMemo, onMoveVisit, onReplace, blurb }: Props) {
  const hours = businessHoursNotice(place, visitDate);
  const note = place.note?.trim();
  const hasUserNote = note && !/^임시\s*휴무는\s*방문\s*전\s*확인\s*해\s*주세요[.!]?$/.test(note);
  const recommendation = blurb !== undefined ? blurb : placeRecommendation(place);
  const transportEvent = place.fixedEventId?.startsWith('transport:');
  return (
    <View style={s.placeCard}>
      <View style={s.timeline}>
        <View style={[s.dot, { backgroundColor: color }]} />
        {!isLast && <View style={s.line} />}
      </View>
      <ItineraryDisclosure key={`${place.fixedEventId ?? place.placeId ?? place.id ?? place.name}`} name={place.name}
        action={editable && !place.fixedEventId && onReplace ? <Pressable testID="place-replace" accessibilityRole="button" accessibilityLabel={`${place.name} 장소 교체`} disabled={busy} onPress={onReplace} style={[s.smallButton,{minHeight:44,justifyContent:'center'}]}><Text style={s.smallButtonText}>교체</Text></Pressable> : undefined} summary={<>
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
        {!!recommendation && <Text style={s.placeNote}>{transportEvent ? '' : '💡 '}{recommendation}</Text>}
        {hasUserNote && <Text style={s.placeNote}>{transportEvent ? '' : '📝 '}{note.startsWith('샘플 일정입니다. AI 키') ? userMessage(note) : note}</Text>}

        {editable && !place.fixedEventId && (
          <View testID="place-actions" style={[s.placeActions,{flexWrap:'wrap'}]}>
            {onEditMemo && <Pressable disabled={busy} onPress={onEditMemo} style={s.smallButton}><Text style={s.smallButtonText}>메모·시간</Text></Pressable>}
            {onMoveVisit && <Pressable disabled={busy} onPress={onMoveVisit} style={s.smallButton}><Text style={s.smallButtonText}>방문 날짜·시간 변경</Text></Pressable>}
            {onDelete && <Pressable disabled={busy} onPress={onDelete} style={s.deleteButton}><Text style={s.deleteText}>삭제</Text></Pressable>}
          </View>
        )}

        <View testID="place-links" style={s.linkRow}>
          <Pressable onPress={() => openPlacePage(place)}>
            <Text style={s.linkText}>정보</Text>
          </Pressable>
          <Pressable onPress={() => { void openDirections(place); }}><Text style={s.linkText}>길찾기</Text></Pressable>
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
