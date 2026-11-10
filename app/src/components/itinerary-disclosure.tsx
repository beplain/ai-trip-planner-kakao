import { useState, type ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { C, s } from '@/lib/styles';

type Props = { name: string; summary: ReactNode; children: ReactNode };

/** Each visit owns its expanded state; detail actions are outside the toggle target. */
export function ItineraryDisclosure({ name, summary, children }: Props) {
  const [expanded, setExpanded] = useState(false);
  return <View style={s.placeBody}>
    <Pressable accessibilityRole="button" accessibilityLabel={`${name} 상세`}
      accessibilityState={{ expanded }} accessibilityHint={expanded ? '누르면 상세 내용을 접어요' : '누르면 상세 내용을 펼쳐요'}
      onPress={() => setExpanded((value) => !value)} style={{ flexDirection: 'row', minHeight: 44 }}>
      <View style={{ flex: 1 }}>{summary}</View>
      <Text accessible={false} style={{ color: C.sub, marginLeft: 8, fontSize: 18 }}>{expanded ? '▴' : '▾'}</Text>
    </Pressable>
    {expanded && <View style={{ marginTop: 8 }}>{children}</View>}
  </View>;
}
