import { useCallback, useRef, useState } from 'react';
import { useFocusEffect, router } from 'expo-router';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { recoveryApi, type TrashItem, type RecoveryKind } from '../../services/recovery';
import { apiError } from '../../services/api';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { useScreenAlert } from '@/components/use-screen-alert';
import { C, s } from '@/lib/styles';

type Entry = TrashItem & { kind: RecoveryKind };
export default function TrashRoute() { return <RequireAuth><TrashScreen /></RequireAuth>; }
function TrashScreen() {
  const { alert, dialog } = useScreenAlert();
  const [entries,setEntries] = useState<Entry[]>([]);
  const [loading,setLoading] = useState(true);
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState('');
  const [now,setNow] = useState(() => Date.now());
  const lock = useRef(false);
  const request = useRef(0);
  const load = useCallback(async () => {
    const attempt=++request.current;
    try {
      const [trips,drafts]=await Promise.all([recoveryApi.trash('trips'),recoveryApi.trash('drafts')]);
      if(attempt!==request.current)return;
      setEntries([...trips.map(t=>({...t,kind:'trips' as const})),...drafts.map(d=>({...d,kind:'drafts' as const}))]
        .sort((a,b)=>b.deletedAt.localeCompare(a.deletedAt)));setError('');setNow(Date.now());
    }catch(e){if(attempt===request.current)setError(apiError(e));}
    finally{if(attempt===request.current)setLoading(false);}
  },[]);
  useFocusEffect(useCallback(()=>{void load();return()=>{request.current++;};},[load]));
  async function act(entry:Entry,permanent:boolean) {
    if(lock.current)return;lock.current=true;setBusy(true);
    try {
      if(permanent){await recoveryApi.permanent(entry.kind,entry.id);await load();}
      else {await recoveryApi.restore(entry.kind,entry.id);router.replace(entry.kind==='trips'?`/trip/${entry.id}`:`/draft/${entry.id}`);}
    }catch(e){alert(permanent?'영구 삭제 실패':'복원 실패',apiError(e));await load();}
    finally{lock.current=false;setBusy(false);}
  }
  return <SafeAreaView style={s.safe}>{dialog}<ScreenHeader title="휴지통" />
    <ScrollView contentContainerStyle={s.content}>
      <Text style={s.subtitle}>내가 만든 여행과 초안을 삭제 후 30일 동안 보관합니다. 복원하면 기존 일정·메모·동행자 권한이 돌아옵니다.</Text>
      {!!error&&<View accessibilityRole="alert"><Text style={s.warnText}>{error}</Text><Pressable onPress={()=>{void load();}} style={s.outlineButton}><Text style={s.outlineText}>다시 불러오기</Text></Pressable></View>}
      {loading?<ActivityIndicator color={C.primary}/>:!error&&entries.length===0?<Text style={s.empty}>휴지통이 비어 있어요.</Text>:null}
      {entries.map(entry=><View key={`${entry.kind}:${entry.id}`} style={[s.memberBox,{marginBottom:16}]}>
        <Text style={s.eyebrow}>{entry.kind==='trips'?'확정 여행':'초안'} · {entry.destination}</Text>
        <Text style={s.sectionTitle}>{entry.title}</Text>
        <Text style={s.mutedSmall}>삭제 {new Date(entry.deletedAt).toLocaleDateString('ko-KR')} · {Math.max(0,Math.ceil((Date.parse(entry.expiresAt)-now)/86400000))}일 남음</Text>
        <Pressable disabled={busy} style={s.primary} onPress={()=>{void act(entry,false);}}><Text style={s.primaryText}>복원</Text></Pressable>
        <Pressable disabled={busy} style={s.outlineButton} onPress={()=>alert('영구 삭제할까요?',`'${entry.title}'을 영구 삭제하면 다시 복원할 수 없습니다.`,[
          {text:'취소',style:'cancel'},{text:'영구 삭제',style:'destructive',onPress:()=>{void act(entry,true);}},
        ])}><Text style={[s.outlineText,{color:C.danger}]}>영구 삭제</Text></Pressable>
      </View>)}
    </ScrollView>
  </SafeAreaView>;
}
