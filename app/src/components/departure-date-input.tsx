import { useEffect, useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { clampDateDay, isValidDate } from '@/lib/format';
import { C, s } from '@/lib/styles';

const ROW = 44;

function Wheel({ values, value, unit, onChange }: {
  values: number[]; value: number; unit: string; onChange: (value: number) => void;
}) {
  const ref = useRef<ScrollView>(null);
  const moving = useRef(false);
  const index = Math.max(0, values.indexOf(value));
  useEffect(() => {
    if (!moving.current) ref.current?.scrollTo({ y: index * ROW, animated: false });
  }, [index, values.length]);
  return (
    <View style={{ flex: 1, height: ROW * 5 }}>
      <View pointerEvents="none" style={{ position: 'absolute', top: ROW * 2, left: 2, right: 2, height: ROW, borderRadius: 10, backgroundColor: '#eef2ff' }} />
      <ScrollView
        ref={ref}
        showsVerticalScrollIndicator={false}
        snapToInterval={ROW}
        decelerationRate="fast"
        bounces={false}
        contentContainerStyle={{ paddingVertical: ROW * 2 }}
        contentOffset={{ x: 0, y: index * ROW }}
        onScrollBeginDrag={() => { moving.current = true; }}
        onScrollEndDrag={() => { moving.current = false; }}
        onMomentumScrollBegin={() => { moving.current = true; }}
        onScroll={(event) => {
          const next = Math.max(0, Math.min(values.length - 1, Math.round(event.nativeEvent.contentOffset.y / ROW)));
          if (moving.current && values[next] !== value) onChange(values[next]);
        }}
        scrollEventThrottle={16}
        onMomentumScrollEnd={(event) => {
          moving.current = false;
          const next = Math.max(0, Math.min(values.length - 1, Math.round(event.nativeEvent.contentOffset.y / ROW)));
          onChange(values[next]);
        }}
      >
        {values.map((item) => (
          <Pressable key={item} accessibilityRole="button" accessibilityLabel={`${item}${unit}`} accessibilityState={{ selected: item === value }}
            onPress={() => { moving.current = false; onChange(item); ref.current?.scrollTo({ y: values.indexOf(item) * ROW, animated: true }); }}
            style={{ height: ROW, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ fontSize: item === value ? 21 : 17, fontWeight: item === value ? '700' : '400', color: item === value ? C.primary : '#8b93a4' }}>{item}{unit}</Text>
          </Pressable>
        ))}
      </ScrollView>
    </View>
  );
}

function DateSheet({ value, onCancel, onSave }: { value: string; onCancel: () => void; onSave: (value: string) => void }) {
  const today = new Date();
  const initial = isValidDate(value) ? value.split('-').map(Number) : [today.getFullYear(), today.getMonth() + 1, today.getDate()];
  const [year, setYear] = useState(initial[0]);
  const [month, setMonth] = useState(initial[1]);
  const [day, setDay] = useState(initial[2]);
  const actualDay = clampDateDay(year, month, day);
  const firstYear = Math.min(today.getFullYear() - 1, initial[0]);
  const lastYear = Math.max(today.getFullYear() + 20, initial[0]);
  const years = Array.from({ length: lastYear - firstYear + 1 }, (_, i) => firstYear + i);
  function changeYear(next: number) { setYear(next); setDay(clampDateDay(next, month, actualDay)); }
  function changeMonth(next: number) { setMonth(next); setDay(clampDateDay(year, next, actualDay)); }
  return (
    <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: '#0006' }}>
      <Pressable accessibilityLabel="날짜 선택 취소" onPress={onCancel} style={{ flex: 1 }} />
      <SafeAreaView edges={['bottom']} style={{ backgroundColor: '#fff', borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 20 }}>
        <Text style={s.sectionTitle}>출발일 선택</Text>
        <Text style={s.subtitle}>년·월·일을 위아래로 밀어 선택해 주세요.</Text>
        <View style={{ flexDirection: 'row', marginVertical: 12 }}>
          <Wheel values={years} value={year} unit="년" onChange={changeYear} />
          <Wheel values={Array.from({ length: 12 }, (_, i) => i + 1)} value={month} unit="월" onChange={changeMonth} />
          <Wheel values={Array.from({ length: new Date(year, month, 0).getDate() }, (_, i) => i + 1)} value={actualDay} unit="일" onChange={setDay} />
        </View>
        <View style={s.row}>
          <Pressable style={[s.outlineButton, { flex: 1, marginTop: 0 }]} onPress={onCancel}><Text style={s.outlineText}>취소</Text></Pressable>
          <Pressable style={[s.primary, { flex: 1 }]} onPress={() => onSave(`${year}-${String(month).padStart(2, '0')}-${String(actualDay).padStart(2, '0')}`)}><Text style={s.primaryText}>선택 완료</Text></Pressable>
        </View>
      </SafeAreaView>
    </View>
  );
}

export function DepartureDateInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel={value ? `출발일 ${value} 변경` : '출발일 선택'} onPress={() => setOpen(true)} style={[s.input, { justifyContent: 'center' }]}>
        <Text style={{ fontSize: 15, color: value ? '#172033' : '#8b93a4' }}>{value ? value.replace('-', '년 ').replace('-', '월 ') + '일' : '날짜 선택'}</Text>
      </Pressable>
      {!!value && <Pressable accessibilityRole="button" style={{ minHeight: 32, justifyContent: 'center' }} onPress={() => onChange('')}><Text style={{ color: C.primary, fontSize: 13 }}>날짜 선택 해제</Text></Pressable>}
      <Modal transparent animationType="slide" visible={open} onRequestClose={() => setOpen(false)}>
        {open && <DateSheet value={value} onCancel={() => setOpen(false)} onSave={(next) => { onChange(next); setOpen(false); }} />}
      </Modal>
    </>
  );
}
