import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { AppModal } from './app-modal';
import { TimeWheelInput } from './time-wheel-input';
import type { Day } from '../../services/trips';
import { s } from '@/lib/styles';
type Props={name:string;days:Day[];source:number;time?:string;disabled?:boolean;onCancel:()=>void;onMove:(day:number,time:string)=>Promise<string|null>};
export function MoveVisitDialog(props:Props){return <AppModal visible transparent animationType="fade" onRequestClose={props.onCancel}><MoveForm {...props}/></AppModal>;}
function MoveForm({name,days,source,time,disabled,onCancel,onMove}:Props){
 const [day,setDay]=useState(source),[visitTime,setTime]=useState(time??'12:00'),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 async function submit(){if(busy||disabled)return;setBusy(true);setError('');try{setError(await onMove(day,visitTime)??'');}catch(e){setError(e instanceof Error?e.message:'이동하지 못했어요.');}finally{setBusy(false);}}
 return <View style={{flex:1,justifyContent:'center',padding:20,backgroundColor:'rgba(0,0,0,0.35)'}}><View style={{backgroundColor:'#fff',borderRadius:20,padding:20,maxHeight:'90%'}}><ScrollView keyboardShouldPersistTaps="handled">
  <Text style={s.sectionTitle}>방문 날짜·시간 변경</Text><Text style={s.label}>{name}</Text>
  <Text style={s.label}>방문일</Text><View style={s.chips}>{days.map((d,i)=><Pressable key={d.dayNumber} disabled={busy||disabled} accessibilityRole="button" accessibilityState={{selected:day===i}} onPress={()=>setDay(i)} style={[s.chip,day===i&&s.chipActive]}><Text style={[s.chipText,day===i&&s.chipTextActive]}>DAY {d.dayNumber}{d.date?` · ${d.date.slice(5).replace('-', '/')}`:''}</Text></Pressable>)}</View>
  <TimeWheelInput label="방문 시각" value={visitTime} onChange={setTime} disabled={busy||disabled} minuteInterval={1}/>
  <Text style={s.subtitle}>DAY {days[source]?.dayNumber} · {time||'미지정'} → DAY {days[day]?.dayNumber} · {visitTime}</Text>
  <Text style={s.mutedSmall}>시간순 위치로 이동합니다. 운영시간·휴무일은 서버에 저장할 때 다시 확인합니다.</Text>
  {!!error&&<Text accessibilityRole="alert" style={s.warnText}>{error}</Text>}
  <Pressable disabled={busy||disabled} style={[s.primary,{marginTop:16},(busy||disabled)&&s.disabled]} onPress={()=>{void submit();}}><Text style={s.primaryText}>{busy?'확인 중…':'이동'}</Text></Pressable>
  <Pressable disabled={busy} style={s.outlineButton} onPress={onCancel}><Text style={s.outlineText}>취소</Text></Pressable>
 </ScrollView></View></View>;
}
