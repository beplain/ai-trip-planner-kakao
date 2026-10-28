import type { ComponentRef } from 'react';
import { useRef, useState } from 'react';
import { Keyboard, Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { formatWheelTime, TIME_HOURS, TIME_MINUTES, wheelTimeParts } from '@/lib/time-wheel';
import { C, s } from '@/lib/styles';

const HEIGHT = 44;
function Wheel({ values, value, suffix, onChange }: { values: number[]; value: number; suffix: string; onChange: (value: number) => void }) {
  const ref = useRef<ComponentRef<typeof ScrollView>>(null);
  const [initial] = useState(() => Math.max(0, values.indexOf(value)));
  return <View style={{ flex: 1, height: HEIGHT * 5 }}>
    <View pointerEvents="none" style={{ position: 'absolute', top: HEIGHT * 2, left: 4, right: 4, height: HEIGHT, borderRadius: 10, backgroundColor: '#edf2ff' }} />
    <ScrollView ref={ref} nestedScrollEnabled showsVerticalScrollIndicator={false} contentInsetAdjustmentBehavior="never"
      contentOffset={{ x: 0, y: initial * HEIGHT }} contentContainerStyle={{ paddingVertical: HEIGHT * 2 }}
      snapToInterval={HEIGHT} decelerationRate="fast" scrollEventThrottle={16}
      onScroll={(event) => {
        const index = Math.max(0, Math.min(values.length - 1, Math.round(event.nativeEvent.contentOffset.y / HEIGHT)));
        if (values[index] !== value) onChange(values[index]);
      }}>
      {values.map((item, index) => <Pressable key={item} accessibilityRole="button" accessibilityLabel={`${item}${suffix}`}
        accessibilityState={{ selected: value === item }} onPress={() => { onChange(item); ref.current?.scrollTo({ y: index * HEIGHT, animated: true }); }}
        style={{ height: HEIGHT, justifyContent: 'center', alignItems: 'center' }}>
        <Text style={{ fontSize: 22, color: item === value ? C.primary : '#657087', fontWeight: item === value ? '700' : '400' }}>{String(item).padStart(2, '0')}{suffix}</Text>
      </Pressable>)}
    </ScrollView>
  </View>;
}
function TimeDialog({ value, label, onSave, onClose }: { value: string; label: string; onSave: (time: string) => void; onClose: () => void }) {
  const [hour, setHour] = useState(() => wheelTimeParts(value).hour);
  const [minute, setMinute] = useState(() => wheelTimeParts(value).minute);
  return <SafeAreaView style={{ flex: 1, backgroundColor: '#0007', justifyContent: 'center', padding: 20 }}>
    <View style={{ padding: 20, backgroundColor: '#fff', borderRadius: 24 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}><Text style={[s.sectionTitleSmall, { flex: 1 }]}>{label}</Text>
        <Pressable accessibilityRole="button" onPress={onClose} style={{ minHeight: 44, padding: 12, justifyContent: 'center' }}><Text style={{ color: C.primary }}>취소</Text></Pressable>
      </View>
      <Text style={s.mutedSmall}>시와 분을 위아래로 스크롤해 선택하세요.</Text>
      <View style={{ flexDirection: 'row', gap: 12, marginVertical: 16 }}>
        <Wheel values={TIME_HOURS} value={hour} suffix="시" onChange={setHour} />
        <Wheel values={TIME_MINUTES} value={minute} suffix="분" onChange={setMinute} />
      </View>
      <Pressable accessibilityRole="button" style={s.primary} onPress={() => onSave(formatWheelTime(hour, minute))}>
        <Text style={s.primaryText}>{formatWheelTime(hour, minute)} 선택</Text>
      </Pressable>
    </View>
  </SafeAreaView>;
}
export function TimeWheelInput({ label, value, onChange }: { label: string; value: string; onChange: (time: string) => void }) {
  const [open, setOpen] = useState(false);
  return <View style={{ flex: 1, gap: 6 }}><Text style={s.mutedSmall}>{label}</Text>
    <Pressable accessibilityRole="button" accessibilityLabel={`${label} ${value}, 시간 선택`} onPress={() => { Keyboard.dismiss(); setOpen(true); }} style={[s.input, { justifyContent: 'center' }]}>
      <Text style={{ color: '#172033', fontSize: 16 }}>{value || '시간 선택'} ⌄</Text>
    </Pressable>
    <Modal visible={open} transparent animationType="fade" onRequestClose={() => setOpen(false)}>
      <SafeAreaProvider>{open && <TimeDialog value={value} label={label} onClose={() => setOpen(false)} onSave={(time) => { onChange(time); setOpen(false); }} />}</SafeAreaProvider>
    </Modal>
  </View>;
}
