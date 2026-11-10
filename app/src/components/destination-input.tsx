import { useState } from 'react';
import { Keyboard, Pressable, Text, TextInput, View } from 'react-native';

import { DestinationMapPicker } from '@/components/destination-map-picker';
import { destinationCandidates, destinationRegion, mapDestinationRegion } from '@/lib/destination-region';
import { s } from '@/lib/styles';

export function DestinationInput({ value, onChange }: { value: string; onChange: (destination: string) => void }) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState<string | undefined>();
  const [invalid, setInvalid] = useState(false);
  const query = input?.trim() ?? '';
  const matches = destinationCandidates(query);
  return (
    <>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <View style={{ flex: 1 }}>
          <TextInput accessibilityLabel="여행 목적지" value={input ?? value}
            onFocus={() => { setInput(value); setInvalid(false); }}
            onChangeText={(text) => { setInput(text); setInvalid(false); onChange(destinationRegion(text) ?? ''); }}
            onBlur={() => { const region = destinationRegion(input ?? value); setInvalid(!!input?.trim() && !region); if (region) { setInput(region); onChange(region); } }}
            placeholder="예: 경주, 양양, 서울" style={[s.input, { paddingRight: 44 }]} maxLength={100} />
          {!!(input ?? value) && <Pressable accessibilityRole="button" accessibilityLabel="목적지 입력 지우기"
            onPress={() => { setInput(''); setInvalid(false); onChange(''); }}
            style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 44, alignItems: 'center', justifyContent: 'center' }}>
            <View style={{ width: 20, height: 20, borderRadius: 10, backgroundColor: '#E8EBF0', alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: '#657087', fontSize: 16, lineHeight: 20 }}>×</Text>
            </View>
          </Pressable>}
        </View>
        <Pressable accessibilityRole="button" onPress={() => { Keyboard.dismiss(); setOpen(true); }} style={[s.smallButton, { justifyContent: 'center', paddingHorizontal: 12 }]}>
          <Text style={s.smallButtonText}>지도에서 선택</Text>
        </Pressable>
      </View>
      <Text style={s.mutedSmall}>{invalid ? '아래 목록에서 여행할 지역을 선택해 주세요.' : '지역명을 입력하면 정식 이름으로 바뀌어요. 같은 이름은 아래에서 선택해 주세요.'}</Text>
      {input !== undefined && <View style={[s.chips, { marginTop: 8 }]}>{matches.map((name) => <Pressable key={name} accessibilityRole="button"
        onPress={() => { onChange(name); setInput(undefined); setInvalid(false); Keyboard.dismiss(); }} style={[s.chip, value === name && s.chipActive]}>
        <Text style={[s.chipText, value === name && s.chipTextActive]}>{name}</Text>
      </Pressable>)}</View>}
      <DestinationMapPicker visible={open} initialDestination={value} onClose={() => setOpen(false)} onSelect={(destination) => { onChange(mapDestinationRegion(destination) ?? ''); setInput(undefined); setInvalid(false); setOpen(false); }} />
    </>
  );
}
