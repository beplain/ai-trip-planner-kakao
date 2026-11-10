import { useState, type ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { C, s } from '@/lib/styles';

type Props = { title: string; color?: string; disabled?: boolean; children: ReactNode };

export function ItineraryDay({ title, color = C.primary, disabled, children }: Props) {
  const [expanded, setExpanded] = useState(true);
  return <View>
    <Pressable accessibilityRole="button" accessibilityLabel={`${title} 일정`}
      accessibilityState={{ expanded, disabled: !!disabled }} disabled={disabled}
      accessibilityHint={expanded ? '누르면 이 날짜의 일정을 모두 접어요' : '누르면 이 날짜의 일정을 펼쳐요'}
      onPress={() => setExpanded((value) => !value)}
      style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 44 }}>
      <Text style={[s.dayLabel, { color, flex: 1 }]}>{title}</Text>
      <Text accessible={false} style={{ color, paddingHorizontal: 8, fontSize: 18 }}>{expanded ? '▴' : '▾'}</Text>
    </Pressable>
    {/* Keep each place's expanded state when its whole day is hidden. */}
    <View style={expanded ? undefined : { display: 'none' }}>{children}</View>
  </View>;
}
