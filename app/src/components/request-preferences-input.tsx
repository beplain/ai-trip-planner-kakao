import { AppTextInput as TextInput } from '@/components/app-text-input';
import { useState } from 'react';
import { Pressable, Text, View, StyleSheet } from 'react-native';
import { C, s } from '@/lib/styles';
import { addRequestRule, countRule, placeRule, readRequest, VISIT_TARGETS, writeRequest } from '@/lib/request-preferences';
import { DestinationMapPicker } from './destination-map-picker';
import type { LodgingPlace } from '@/lib/lodging-map';

export function RequestPreferencesInput({ value, onChange, days, destination = '' }: { value: string; onChange: (value: string) => void; days: number; destination?: string }) {
  const [open, setOpen] = useState<'include' | 'exclude' | 'count' | null>(null);
  const [name, setName] = useState('');
  const [target, setTarget] = useState<string>('카페');
  const [scope, setScope] = useState(-1);
  const [mode, setMode] = useState<'EXACT' | 'INCLUDE' | 'LIMIT'>('EXACT');
  const [count, setCount] = useState(1);
  const [error, setError] = useState('');
  const [customTarget,setCustomTarget] = useState('');
  const [mapFor,setMapFor] = useState<'include'|'exclude'|null>(null);
  const [chosen,setChosen] = useState<LodgingPlace|null>(null);
  const parsed = readRequest(value);
  function add() {
    if (open !== 'count' && !name.trim()) { setError('장소나 브랜드 이름을 입력해 주세요.'); return; }
    const actualTarget = target==='기타'?customTarget.trim():target;
    if(open==='count'&&!actualTarget){setError('횟수를 정할 음식이나 장소 종류를 입력해 주세요.');return;}
    const rule = open === 'count' ? countRule(actualTarget, scope, mode, count) : placeRule(open === 'include' ? 'INCLUDE' : 'EXCLUDE', name,chosen?.placeId,chosen?.category);
    if (scope > days && open === 'count') { setError('여행 기간 안의 일차를 선택해 주세요.'); return; }
    const result = addRequestRule(value, rule);
    setError(result.error ?? '');
    if (!result.error) { onChange(result.value); setName(''); setChosen(null); setOpen(null); }
  }
  const chip = (label: string, active: boolean, onPress: () => void) => <Pressable key={label} accessibilityRole="button"
    accessibilityState={{ selected: active }} onPress={onPress} style={[styles.choice, active && s.chipActive]}>
    <Text style={[s.chipText, active && s.chipTextActive]}>{label}</Text></Pressable>;
  return <View style={{ gap: 12 }}>
    <Text style={s.mutedSmall}>필요한 조건만 추가하세요. 아무것도 선택하지 않아도 됩니다.</Text>
    {parsed.legacy ? <View style={s.warnBox}>
      <Text style={s.warnTitle}>이전 추가 요청</Text><Text style={s.warnText}>{parsed.legacy}</Text>
      <Text style={s.mutedSmall}>기존 요청은 유지됩니다. 새 선택 방식으로 바꾸려면 삭제해 주세요.</Text>
      <Pressable accessibilityRole="button" style={styles.removeLegacy} onPress={() => { onChange(''); setError(''); }}><Text style={{ color: C.danger }}>이전 요청 삭제</Text></Pressable>
    </View> : null}
    {(['include', 'exclude', 'count'] as const).map(section => {
      const title = section === 'include' ? '포함할 장소' : section === 'exclude' ? '제외할 장소·브랜드' : '방문 횟수';
      const rules = parsed.rules.map((rule, index) => ({ rule, index })).filter(({ rule }) => section === 'exclude' ? rule.kind === 'EXCLUDE'
        : section === 'include' ? rule.kind === 'INCLUDE' && rule.meal!=='COUNT' && (!rule.category||rule.meal==='PLACE') && !rule.daily && !rule.day && rule.value === 1
          : !(rule.kind === 'EXCLUDE' || rule.kind === 'INCLUDE' && rule.meal!=='COUNT' && (!rule.category||rule.meal==='PLACE') && !rule.daily && !rule.day && rule.value === 1));
      return <View key={section} style={styles.card}>
        <View style={styles.heading}><Text style={styles.title}>{title}</Text>
          <Pressable accessibilityRole="button" accessibilityLabel={`${title} 추가`} disabled={!!parsed.legacy} hitSlop={8}
            onPress={() => { setOpen(open === section ? null : section); setName(''); setChosen(null); setError(''); }}>
            <Text style={{ color: C.primary, fontWeight: '700' }}>{open === section ? '접기' : '+ 추가'}</Text></Pressable></View>
        {!rules.length && open !== section && <Text style={s.mutedSmall}>{section === 'include' ? '꼭 가고 싶은 장소' : section === 'exclude' ? '방문하지 않을 장소나 브랜드' : '대상·기간·횟수를 선택해요'}</Text>}
        {rules.map(({ rule, index }) => <View key={index} style={styles.rule}><Text style={{ flex: 1, minWidth: 0, color: C.text, lineHeight: 20 }}>{rule.source}</Text>
          <Pressable accessibilityRole="button" accessibilityLabel={`${rule.source} 삭제`} hitSlop={8} onPress={() => { onChange(writeRequest(parsed.rules.filter((_, i) => i !== index))); setError(''); }}><Text style={{ color: C.sub }}>삭제</Text></Pressable></View>)}
        {open === section && <View style={{ gap: 10, marginTop: 8 }}>
          {section === 'count' ? <>
            <Text style={styles.label}>대상</Text><View style={s.chips}>{VISIT_TARGETS.map(t => chip(t, target === t, () => setTarget(t)))}</View>
            {target==='기타'&&<><TextInput accessibilityLabel="방문 횟수 기타 대상" value={customTarget} onChangeText={setCustomTarget} maxLength={50} placeholder="예: 돼지국밥" style={s.input}/><Text style={s.mutedSmall}>돼지국밥·회·냉면 등을 한 종류씩 입력하세요. 장소명·업종으로 확인 가능한 대상을 입력해 주세요.</Text></>}
            <Text style={styles.label}>기간</Text><View style={s.chips}>{[[-1, '매일'], [0, '여행 전체'], ...Array.from({ length: Math.min(7, Math.max(1, days)) }, (_, i) => [i + 1, `${i + 1}일차`])].map(([v, text]) => chip(String(text), scope === v, () => setScope(Number(v))))}</View>
            <Text style={styles.label}>횟수</Text><View style={s.chips}>{(['EXACT', 'LIMIT', 'INCLUDE'] as const).map(m => chip(m === 'EXACT' ? '정확히' : m === 'LIMIT' ? '최대' : '최소', mode === m, () => { setMode(m); if (!count && m !== 'LIMIT') setCount(1); }))}</View>
            <View style={styles.stepper}><Pressable accessibilityRole="button" accessibilityLabel="방문 횟수 줄이기" disabled={count <= (mode === 'LIMIT' ? 0 : 1)} style={styles.step} onPress={() => setCount(count - 1)}><Text style={styles.title}>−</Text></Pressable>
              <Text style={styles.title}>{count}곳</Text><Pressable accessibilityRole="button" accessibilityLabel="방문 횟수 늘리기" disabled={count >= 20} style={styles.step} onPress={() => setCount(count + 1)}><Text style={styles.title}>+</Text></Pressable></View>
            <Text style={s.mutedSmall}>여행 유형의 하루 방문 수와 함께 적용해요. 실제 메뉴 제공 여부는 장소 정보에서 확인해 주세요.</Text>
          </> : <><TextInput accessibilityLabel={title} value={name} onChangeText={text=>{setName(text);setChosen(null);}} style={s.input} maxLength={100}
            placeholder={section === 'include' ? '예: 정동문화사' : '예: 다이소'} returnKeyType="done" onSubmitEditing={add} />
            <Pressable accessibilityRole="button" onPress={()=>setMapFor(section)} style={[s.outlineButton,{marginTop:0}]}><Text style={s.outlineText}>지도에서 선택</Text></Pressable>
            {chosen&&<Text style={s.mutedSmall}>지도에서 선택한 지점 · {chosen.address}</Text>}
            <Text style={s.mutedSmall}>{section === 'include' ? '직접 입력하거나 지도에서 한 장소씩 선택해 주세요.' : chosen?'선택한 지점만 제외합니다.':'브랜드 이름을 입력하면 전체 지점, 지도에서 선택하면 해당 지점을 제외해요.'}</Text></>}
          <Pressable accessibilityRole="button" style={styles.add} onPress={add}><Text style={s.primaryText}>조건 추가</Text></Pressable>
        </View>}
      </View>;
    })}
    {!!error && <Text accessibilityRole="alert" style={s.warnText}>{error}</Text>}
    <DestinationMapPicker visible={!!mapFor} placeKind="visit" pickerTitle={mapFor==='include'?'포함할 장소 선택':'제외할 장소 선택'} initialDestination={destination} initialRegion={destination}
      onSelect={()=>undefined} onClose={()=>setMapFor(null)} onPlaceSelect={place=>{setChosen(place);setName(place.name);setMapFor(null);}} />
  </View>;
}
const styles = StyleSheet.create({
  card: { backgroundColor: '#fff', borderRadius: 16, borderWidth: 1, borderColor: C.border, padding: 16, gap: 8, minWidth: 0 },
  heading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { color: C.text, fontSize: 15, fontWeight: '800' }, label: { color: C.sub, fontSize: 12, marginTop: 4 },
  rule: { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: '#F3F6FF', padding: 10, borderRadius: 10 },
  choice: { paddingHorizontal: 12, paddingVertical: 10, borderRadius: 16, borderWidth: 1, borderColor: C.border },
  stepper: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 24 },
  step: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 12, backgroundColor: '#F3F6FF' },
  add: { minHeight: 44, alignItems: 'center', justifyContent: 'center', backgroundColor: C.primary, borderRadius: 12 },
  removeLegacy: { paddingVertical: 12 },
});
