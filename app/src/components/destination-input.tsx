import { useState } from 'react';
import { Keyboard, Pressable, Text, TextInput, View } from 'react-native';

import { DestinationMapPicker } from '@/components/destination-map-picker';
import { s } from '@/lib/styles';

export function DestinationInput({ value, onChange }: { value: string; onChange: (destination: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <TextInput accessibilityLabel="여행 목적지" value={value} onChangeText={onChange} placeholder="예: 부산" style={[s.input, { flex: 1 }]} maxLength={50} />
        <Pressable accessibilityRole="button" onPress={() => { Keyboard.dismiss(); setOpen(true); }} style={[s.smallButton, { justifyContent: 'center', paddingHorizontal: 12 }]}>
          <Text style={s.smallButtonText}>지도에서 선택</Text>
        </Pressable>
      </View>
      <DestinationMapPicker visible={open} initialDestination={value} onClose={() => setOpen(false)} onSelect={(destination) => { onChange(destination); setOpen(false); }} />
    </>
  );
}
