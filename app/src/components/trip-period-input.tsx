import { useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import { dateRange, isValidDate, tripDayCount } from '@/lib/format';
import { C, s } from '@/lib/styles';

type PeriodProps = { start: string; end: string; onChange: (start: string, end: string) => void };
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const MAX_DAYS = 7;
const ROW_HEIGHT = 44;

function MonthWheel({ values, value, suffix, onChange }: {
  values: number[]; value: number; suffix: string; onChange: (value: number) => void;
}) {
  const scroll = useRef<ScrollView>(null);
  const [initialIndex] = useState(() => Math.max(0, values.indexOf(value)));
  function select(index: number) {
    const bounded = Math.max(0, Math.min(values.length - 1, index));
    onChange(values[bounded]);
    scroll.current?.scrollTo({ y: bounded * ROW_HEIGHT, animated: true });
  }
  return (
    <View style={{ flex: 1, height: ROW_HEIGHT * 5 }}>
      <View pointerEvents="none" style={{ position: 'absolute', top: ROW_HEIGHT * 2, left: 4, right: 4, height: ROW_HEIGHT, borderRadius: 10, backgroundColor: '#eaf0ff' }} />
      <ScrollView ref={scroll} nestedScrollEnabled showsVerticalScrollIndicator={false} contentInsetAdjustmentBehavior="never"
        contentOffset={{ x: 0, y: initialIndex * ROW_HEIGHT }}
        contentContainerStyle={{ paddingVertical: ROW_HEIGHT * 2 }}
        snapToInterval={ROW_HEIGHT} decelerationRate="fast" scrollEventThrottle={16}
        onScroll={event => {
          const index = Math.max(0, Math.min(values.length - 1, Math.round(event.nativeEvent.contentOffset.y / ROW_HEIGHT)));
          if (values[index] !== value) onChange(values[index]);
        }}>
        {values.map((item, index) => (
          <Pressable key={item} accessibilityRole="button" accessibilityLabel={`${item}${suffix}`} accessibilityState={{ selected: item === value }}
            onPress={() => select(index)} style={{ height: ROW_HEIGHT, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ fontSize: 20, color: item === value ? C.primary : '#657087', fontWeight: item === value ? '700' : '400' }}>{item}{suffix}</Text>
          </Pressable>
        ))}
      </ScrollView>
    </View>
  );
}

function PeriodCalendar({ start, end, onSave, onClose }: {
  start: string; end: string; onSave: (start: string, end: string) => void; onClose: () => void;
}) {
  const today = new Date();
  const minYear = today.getFullYear() - 10;
  const maxYear = today.getFullYear() + 10;
  const initial = isValidDate(start) ? start.split('-').map(Number) : [today.getFullYear(), today.getMonth() + 1];
  const [year, setYear] = useState(Math.min(maxYear, Math.max(minYear, initial[0])));
  const [month, setMonth] = useState(initial[1]);
  const [first, setFirst] = useState(start);
  const [last, setLast] = useState(end);
  const [notice, setNotice] = useState('');
  const [choosingMonth, setChoosingMonth] = useState(false);
  const [pendingYear, setPendingYear] = useState(year);
  const [pendingMonth, setPendingMonth] = useState(month);
  const count = tripDayCount(first, last);
  const offset = new Date(year, month - 1, 1).getDay();
  const length = new Date(year, month, 0).getDate();
  const cells = Array.from({ length: 42 }, (_, i) => i >= offset && i < offset + length ? i - offset + 1 : null);

  function moveMonth(delta: number) {
    const next = new Date(year, month - 1 + delta, 1);
    if (next.getFullYear() < minYear || next.getFullYear() > maxYear) return;
    setChoosingMonth(false);
    setYear(next.getFullYear()); setMonth(next.getMonth() + 1);
  }
  function choose(iso: string) {
    setNotice('');
    if (!first || last || iso < first) { setFirst(iso); setLast(''); return; }
    if (tripDayCount(first, iso) > MAX_DAYS) {
      setNotice('여행 기간은 시작일과 종료일을 포함해 최대 7일입니다. 종료일을 다시 선택해 주세요.');
      return;
    }
    setLast(iso);
  }
  return (
    <SafeAreaView style={s.safe}>
      <View style={{ padding: 20, flexDirection: 'row', alignItems: 'center' }}>
        <Text style={[s.sectionTitle, { flex: 1 }]}>여행 기간 선택</Text>
        <Pressable accessibilityRole="button" onPress={onClose} hitSlop={8} style={{ padding: 12, minHeight: 44, justifyContent: 'center' }}><Text style={{ color: C.primary }}>취소</Text></Pressable>
      </View>
      <ScrollView scrollEnabled={!choosingMonth} contentContainerStyle={{ padding: 20, gap: 20 }}>
        <View style={s.infoBox}>
          <Text style={s.infoTitle}>{!first || last ? '출발할 날짜를 선택해 주세요' : '여행을 마칠 날짜를 선택해 주세요'}</Text>
          <Text style={s.infoText}>최대 7일까지 선택할 수 있어요. 당일 여행은 같은 날짜를 두 번 눌러 주세요.</Text>
        </View>
        <View style={{ backgroundColor: '#fff', borderRadius: 20, padding: 10, width: '100%', maxWidth: 480, alignSelf: 'center' }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 12 }}>
            <Pressable accessibilityRole="button" accessibilityLabel="이전 달" disabled={year === minYear && month === 1} onPress={() => moveMonth(-1)} style={{ padding: 16, opacity: year === minYear && month === 1 ? 0.3 : 1 }}><Text style={{ fontSize: 22, color: C.primary }}>‹</Text></Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel={`${year}년 ${month}월, 년 월 선택`} accessibilityState={{ expanded: choosingMonth }}
              onPress={() => {
                setPendingYear(year); setPendingMonth(month); setChoosingMonth(!choosingMonth);
              }} style={{ flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={s.sectionTitleSmall}>{year}년 {month}월 <Text style={{ color: C.primary }}>⌄</Text></Text>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel="다음 달" disabled={year === maxYear && month === 12} onPress={() => moveMonth(1)} style={{ padding: 16, opacity: year === maxYear && month === 12 ? 0.3 : 1 }}><Text style={{ fontSize: 22, color: C.primary }}>›</Text></Pressable>
          </View>
          {choosingMonth ? <View>
            <Text style={[s.mutedSmall, { textAlign: 'center', marginBottom: 12 }]}>년과 월을 위아래로 스크롤해 선택해 주세요</Text>
            <View style={{ flexDirection: 'row', gap: 12 }}>
              <MonthWheel values={Array.from({ length: maxYear - minYear + 1 }, (_, i) => minYear + i)} value={pendingYear} suffix="년" onChange={setPendingYear} />
              <MonthWheel values={Array.from({ length: 12 }, (_, i) => i + 1)} value={pendingMonth} suffix="월" onChange={setPendingMonth} />
            </View>
            <View style={{ flexDirection: 'row', gap: 12, marginTop: 16 }}>
              <Pressable accessibilityRole="button" onPress={() => setChoosingMonth(false)} style={{ flex: 1, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: C.primary }}>취소</Text></Pressable>
              <Pressable accessibilityRole="button" onPress={() => { setYear(pendingYear); setMonth(pendingMonth); setChoosingMonth(false); }} style={[s.primary, { flex: 1 }]}><Text style={s.primaryText}>이 달로 이동</Text></Pressable>
            </View>
          </View> : <>
          <View style={{ flexDirection: 'row' }}>{WEEKDAYS.map((label, i) => <Text key={label} style={{ width: `${100 / 7}%`, textAlign: 'center', paddingBottom: 12, color: i === 0 ? '#d04b5d' : '#657087' }}>{label}</Text>)}</View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
            {cells.map((day, i) => {
              const iso = day ? `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}` : '';
              const edge = !!iso && (iso === first || iso === last);
              const inside = !!iso && !!first && !!last && iso > first && iso < last;
              const tooFar = !!iso && !!first && !last && iso > first && tripDayCount(first, iso) > MAX_DAYS;
              return (
                <View key={i} style={{ width: `${100 / 7}%`, height: 44, marginVertical: 2 }}>
                  {day && <Pressable accessibilityRole="button" accessibilityLabel={`${year}년 ${month}월 ${day}일${edge ? ' 선택됨' : ''}`} accessibilityState={{ selected: edge || inside }}
                    onPress={() => choose(iso)} style={{ flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: edge ? 22 : 0, backgroundColor: edge ? C.primary : inside ? '#eaf0ff' : 'transparent' }}>
                    <Text style={{ fontSize: 16, fontWeight: edge ? '700' : '400', color: edge ? '#fff' : tooFar ? '#b9bfcb' : i % 7 === 0 ? '#d04b5d' : '#303a50' }}>{day}</Text>
                  </Pressable>}
                </View>
              );
            })}
          </View>
          </>}
        </View>
        {!!notice && <Text accessibilityLiveRegion="polite" style={s.warnText}>{notice}</Text>}
        <Text accessibilityLiveRegion="polite" style={s.sectionTitleSmall}>{last ? dateRange(first, last) : first ? `${dateRange(first)} ~ 종료일 선택` : '여행 기간을 선택해 주세요'}{count ? ` · ${count}일` : ''}</Text>
      </ScrollView>
      <View style={{ padding: 20 }}>
        <Pressable accessibilityRole="button" disabled={choosingMonth || !count || count > MAX_DAYS} onPress={() => onSave(first, last)} style={[s.primary, (choosingMonth || !count || count > MAX_DAYS) && s.disabled]}>
          <Text style={s.primaryText}>이 기간으로 선택</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

export function TripPeriodInput({ start, end, onChange }: PeriodProps) {
  const [open, setOpen] = useState(false);
  const count = tripDayCount(start, end);
  return (
    <>
      <Pressable accessibilityRole="button" accessibilityLabel={`여행 기간 ${dateRange(start, end) || '선택'}`} onPress={() => setOpen(true)} style={[s.input, { justifyContent: 'center' }]}>
        <Text style={{ fontSize: 15, color: count ? '#172033' : '#8b93a4' }}>{count ? dateRange(start, end) : '캘린더에서 여행 기간 선택'}</Text>
      </Pressable>
      {!!count && <Text style={[s.mutedSmall, { marginTop: 8 }]}>총 {count}일 여행</Text>}
      <Modal visible={open} animationType="slide" presentationStyle="fullScreen" onRequestClose={() => setOpen(false)}>
        {/* A native modal needs its own safe-area measurement, including on the first opening. */}
        <SafeAreaProvider>
          {open && <PeriodCalendar start={start} end={end} onClose={() => setOpen(false)} onSave={(first, last) => { onChange(first, last); setOpen(false); }} />}
        </SafeAreaProvider>
      </Modal>
    </>
  );
}
