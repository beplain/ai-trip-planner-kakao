import { useState } from 'react';
import { Keyboard, Pressable, Text, TextInput, View } from 'react-native';

import { DestinationMapPicker } from '@/components/destination-map-picker';
import { DESTINATION_REGIONS, destinationRegion } from '@/lib/destination-region';
import { s } from '@/lib/styles';

export function DestinationInput({ value, onChange }: { value: string; onChange: (destination: string) => void }) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState<string | undefined>();
  const [invalid, setInvalid] = useState(false);
  const query = input?.trim() ?? '';
  const matches = DESTINATION_REGIONS.filter(({ name, aliases }) => invalid || !query || name.includes(query) || aliases.some((alias) => alias.includes(query)) || destinationRegion(query) === name);
  return (
    <>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <TextInput accessibilityLabel="여행 목적지 시·도" value={input ?? value}
          onFocus={() => { setInput(value); setInvalid(false); }}
          onChangeText={(text) => { setInput(text); setInvalid(false); onChange(destinationRegion(text) ?? ''); }}
          onBlur={() => { const region = destinationRegion(input ?? value); setInvalid(!!input?.trim() && !region); if (region) setInput(region); }}
          placeholder="예: 경기도, 서울특별시" style={[s.input, { flex: 1 }]} maxLength={100} />
        <Pressable accessibilityRole="button" onPress={() => { Keyboard.dismiss(); setOpen(true); }} style={[s.smallButton, { justifyContent: 'center', paddingHorizontal: 12 }]}>
          <Text style={s.smallButtonText}>지도에서 선택</Text>
        </Pressable>
      </View>
      <Text style={s.mutedSmall}>{invalid ? '아래 목록에서 여행할 시·도를 선택해 주세요.' : '시·도 단위로 선택해 주세요. 구·동 등 상세 주소는 포함하지 않아요.'}</Text>
      {input !== undefined && <View style={[s.chips, { marginTop: 8 }]}>{matches.map(({ name }) => <Pressable key={name} accessibilityRole="button"
        onPress={() => { onChange(name); setInput(undefined); setInvalid(false); Keyboard.dismiss(); }} style={[s.chip, value === name && s.chipActive]}>
        <Text style={[s.chipText, value === name && s.chipTextActive]}>{name}</Text>
      </Pressable>)}</View>}
      <DestinationMapPicker visible={open} initialDestination={value} onClose={() => setOpen(false)} onSelect={(destination) => { onChange(destinationRegion(destination) ?? ''); setInput(undefined); setInvalid(false); setOpen(false); }} />
    </>
  );
}
