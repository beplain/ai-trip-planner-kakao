import { Linking, Pressable, Text, View } from 'react-native';

import type { Place } from '../../services/trips';
import { openDirections, openPlacePage } from '@/lib/kakao-map';
import { C, s } from '@/lib/styles';

type Props = {
  place: Place;
  index: number;
  isLast: boolean;
  color?: string;
  editable: boolean;
  busy?: boolean;
  onDelete?: () => void;
  onEditMemo?: () => void;
};

export function PlaceCard({ place, index, isLast, color = C.primary, editable, busy, onDelete, onEditMemo }: Props) {
  return (
    <View style={s.placeCard}>
      <View style={s.timeline}>
        <View style={[s.dot, { backgroundColor: color }]} />
        {!isLast && <View style={s.line} />}
      </View>
      <View style={s.placeBody}>
        <Text style={[s.placeTime, { color }]}>{place.visitTime ? `${place.visitTime} · ` : ''}장소 {index + 1}{place.category ? ` · ${place.category}` : ''}</Text>
        <Text style={s.placeName}>{place.name}</Text>
        {!!place.address && <Text style={s.placeAddr}>{place.address}</Text>}
        <View style={[s.badge, { backgroundColor: place.verified ? '#E7F6EE' : C.warnBg }]}>
          <Text style={[s.badgeText, { color: place.verified ? C.ok : C.warnText }]}>
            {place.verified ? '카카오맵에서 확인된 장소' : '지도 미확인 · 방문 전 확인 필요'}
          </Text>
        </View>
        {!!place.reason && <Text style={s.placeNote}>{place.reason}</Text>}
        {!!place.note && <Text style={s.placeNote}>💡 {place.note}</Text>}

        {editable && (
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
        <Text style={[s.mutedSmall, { marginTop: 6 }]}>영업시간·휴무·예약은 카카오맵 상세에서 방문 전 확인해 주세요.</Text>
      </View>
    </View>
  );
}
