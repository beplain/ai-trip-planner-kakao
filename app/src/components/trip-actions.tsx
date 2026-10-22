import { Pressable, Text, View } from 'react-native';
import { s } from '@/lib/styles';

export function TripActions({ onMap, onShare, onInvite, disabled = false }: {
  onMap: () => void; onShare: () => void; onInvite: () => void; disabled?: boolean;
}) {
  return <View style={s.actionRow}>
    {[{ label: '지도 보기', icon: '⌖', action: onMap }, { label: '일정 공유', icon: '↗', action: onShare }, { label: '동행자 초대', icon: '＋', action: onInvite }].map((item) => (
      <Pressable accessibilityRole="button" key={item.label} disabled={disabled} style={[s.actionButton, disabled && s.disabled]} onPress={item.action}>
        <Text style={s.actionIcon}>{item.icon}</Text><Text style={s.actionText}>{item.label}</Text>
      </Pressable>
    ))}
  </View>;
}
