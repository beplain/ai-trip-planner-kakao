import type { Dispatch, SetStateAction } from 'react';
import { Pressable, Text, View } from 'react-native';
import { styleLabel, STYLE_OPTIONS } from '@/lib/format';
import { C } from '@/lib/styles';

const NO_RETENTION = { top: 0, bottom: 0, left: 0, right: 0 };

export function TravelStyleInput({ value, onChange }: {
  value: string[]; onChange: Dispatch<SetStateAction<string[]>>;
}) {
  const rows = Array.from({ length: Math.ceil(STYLE_OPTIONS.length / 2) }, (_, i) => STYLE_OPTIONS.slice(i * 2, i * 2 + 2));
  return <View style={{ gap: 12 }}>
    {rows.map((row, index) => <View key={index} style={{ flexDirection: 'row', gap: 12 }}>
      {row.map(item => {
        const selected = value.includes(item);
        return <Pressable key={item} accessibilityRole="checkbox" accessibilityLabel={styleLabel(item)}
          accessibilityState={{ checked: selected, selected }} hitSlop={0} pressRetentionOffset={NO_RETENTION}
          onPress={() => onChange(current => current.includes(item) ? current.filter(v => v !== item) : [...current, item])}
          style={{ flex: 1, minWidth: 0, minHeight: 52, paddingHorizontal: 14, paddingVertical: 14,
            alignItems: 'center', justifyContent: 'center', borderRadius: 16, borderWidth: 1,
            borderColor: selected ? C.primary : C.border, backgroundColor: selected ? '#EAF0FF' : '#fff' }}>
          <Text style={{ color: selected ? C.primary : C.sub, fontSize: 15, fontWeight: '700' }}>{styleLabel(item)}</Text>
        </Pressable>;
      })}
      {row.length === 1 && <View style={{ flex: 1 }} />}
    </View>)}
  </View>;
}
