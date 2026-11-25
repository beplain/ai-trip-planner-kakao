import { useCallback, useEffect, useRef, useState } from 'react';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { ActivityIndicator, BackHandler, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { tripsApi, type Trip, type Place, type SelectedPlace } from '../../../../services/trips';
import { apiError } from '../../../../services/api';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { DraftEditNotice } from '@/components/draft-edit-notice';
import { DraggablePlaces } from '@/components/draggable-places';
import { MemoModal } from '@/components/memo-modal';
import { DestinationMapPicker } from '@/components/destination-map-picker';
import { useScreenAlert } from '@/components/use-screen-alert';
import { useDraftEdit } from '@/hooks/use-draft-edit';
import { useDragScroll } from '@/hooks/use-drag-scroll';
import { reorderItems } from '@/lib/reorder';
import { DAY_COLORS, s } from '@/lib/styles';
import { visitMapCenter } from '@/lib/visit-map-center';
import { useCrossDayDrag } from '@/hooks/use-cross-day-drag';
import { dragVisit } from '@/lib/drag-visit';

export default function EditTripRoute(){return <RequireAuth><EditConfirmedTrip /></RequireAuth>;}
function EditConfirmedTrip(){
  const {id}=useLocalSearchParams<{id:string}>();
  const editor=useDraftEdit(`trip:${id}`,undefined,true);
  const {alert,dialog}=useScreenAlert();
  const [plan,setPlan]=useState<Trip|null>(null);
  const [busy,setBusy]=useState(false);
  const saving=useRef(false);
  const [error,setError]=useState('');
  const [memo,setMemo]=useState<{day:number;index:number}|null>(null);
  const [pick,setPick]=useState<{day:number;index?:number}|null>(null);
  const {attach,dragging,onScroll,onContentSizeChange,controller}=useDragScroll();
  const begin=editor.begin,release=editor.release;
  useEffect(()=>{
    if(!editor.mine)return;
    let active=true;
    void (async()=>{try {const value=await tripsApi.get(id);if(active){setPlan(value);setError('');}}catch(e){if(active){setError(apiError(e));void release();}}})();
    return()=>{active=false;};
  },[id,editor.mine,release]);
  const cancel=useCallback(()=>{
    if(saving.current)return;
    alert('수정을 취소할까요?','저장하지 않은 변경사항은 사라지고 기존 확정 일정은 유지됩니다.',[
      {text:'계속 수정',style:'cancel'}, {text:'수정 취소',onPress:()=>{void release();router.back();}},
    ]);
  },[alert,release]);
  useFocusEffect(useCallback(()=>{const sub=BackHandler.addEventListener('hardwareBackPress',()=>{cancel();return true;});return()=>sub.remove();},[cancel]));
  function change(day:number,transform:(places:Place[])=>Place[]){setPlan(p=>p?{...p,days:p.days.map((d,i)=>i===day?{...d,places:transform(d.places)}:d)}:p);}
  async function save(){
    if(!plan||saving.current||editor.blocked||!editor.mine)return;
    saving.current=true;setBusy(true);
    try {await tripsApi.savePlan(plan.id,plan.days,plan.version);await release();router.replace(`/trip/${id}`);}
    catch(e){alert('저장하지 못했어요',apiError(e));}
    finally{saving.current=false;setBusy(false);}
  }
  const disabled=busy||editor.blocked||!editor.mine;
  const crossDrag=useCrossDayDrag(controller,{
    start:async()=>!disabled,cancel:()=>{},error:message=>alert('일정 이동',message),
    drop:(source,target)=>{if(plan)setPlan({...plan,days:dragVisit(plan.days,source.day,source.index,target.day,target.index,plan.travelDetails)});},
  });
  const target=memo&&plan?plan.days[memo.day].places[memo.index]:undefined;
  function selected(place:SelectedPlace){
    if(!pick)return;const selection=pick;
    change(selection.day,places=>{
      if(selection.index!==undefined)return places.map((p,i)=>i===selection.index?{...p,...place,id:undefined,verified:true,reason:undefined,note:undefined,category:place.category??'관광',categoryPath:place.categoryPath,businessHours:undefined}:p);
      return [...places,{...place,verified:true,visitTime:'14:00',category:place.category??'관광',note:''}].sort((a,b)=>(a.visitTime??'').localeCompare(b.visitTime??''));
    });setPick(null);
  }
  return <SafeAreaView style={s.safe}>{dialog}
    <ScreenHeader title="확정 일정 수정" onBack={cancel} />
    <DraftEditNotice editingKey={editor.active&&!editor.mine?`${id}:${editor.editorId}`:undefined} editorName={editor.editorName} message={editor.message} blocked={editor.blocked} onRetry={editor.refresh}/>
    {!plan?<View style={s.center}>{error?<Text style={s.warnText}>{error}</Text>:<Text style={s.subtitle}>편집 권한을 확인하고 있습니다.</Text>}
      <Pressable style={s.outlineButton} onPress={()=>{void begin().then(async allowed=>{if(allowed)setPlan(await tripsApi.get(id));}).catch(e=>setError(apiError(e)));}}><Text style={s.outlineText}>편집 다시 시작</Text></Pressable></View>:
    <ScrollView ref={attach} scrollEnabled={!dragging} onScroll={onScroll} scrollEventThrottle={16} onContentSizeChange={onContentSizeChange} contentContainerStyle={s.content}>
      <Text style={s.sectionTitle}>{plan.title}</Text>
      <Text style={s.subtitle}>변경사항 저장을 눌러야 확정 일정에 반영됩니다. 숙소·교통 고정 일정은 유지됩니다.</Text>
      {!editor.mine&&!editor.active&&<Pressable disabled={busy} style={s.outlineButton} onPress={()=>alert('최신 일정으로 다시 시작할까요?','입력 중인 변경사항을 버리고 최신 일정을 불러옵니다.',[{text:'취소',style:'cancel'},{text:'다시 시작',onPress:()=>{void begin().then(async allowed=>{if(allowed){setPlan(await tripsApi.get(id));setMemo(null);setPick(null);}}).catch(e=>alert('불러오기 실패',apiError(e)));}}])}><Text style={s.outlineText}>최신 일정으로 편집 다시 시작</Text></Pressable>}
      {plan.days.map((day,di)=><View key={day.dayNumber} ref={crossDrag.bind(di,-1,true)} collapsable={false}>
        <Text style={[s.dayLabel,{color:DAY_COLORS[di%DAY_COLORS.length]}]}>DAY {day.dayNumber}</Text>
        <DraggablePlaces crossDay={crossDrag} dayIndex={di} places={day.places} visitDate={day.date} color={DAY_COLORS[di%DAY_COLORS.length]} editable={!disabled} busy={busy} scroll={controller}
          onReplace={(_p,index)=>setPick({day:di,index})}

          onReorder={(from,to)=>change(di,places=>{const times=places.map(p=>p.visitTime);return reorderItems(places,from,to).map((p,i)=>({...p,visitTime:times[i]}));})}
          onDelete={(_p,index)=>alert('장소 삭제','변경사항을 저장하면 삭제됩니다.',[{text:'취소',style:'cancel'},{text:'삭제',onPress:()=>change(di,places=>places.filter((_,i)=>i!==index))}])}
          onEditMemo={(_p,index)=>setMemo({day:di,index})}/>
        <Pressable disabled={disabled} style={s.outlineButton} onPress={()=>setPick({day:di})}><Text style={s.outlineText}>＋ 장소 추가</Text></Pressable>
      </View>)}
      <Pressable disabled={disabled} style={[s.primary,disabled&&s.disabled]} onPress={()=>{void save();}}>{busy?<ActivityIndicator color="#fff"/>:<Text style={s.primaryText}>변경사항 저장</Text>}</Pressable>
      <Pressable disabled={busy} style={s.outlineButton} onPress={cancel}><Text style={s.outlineText}>수정 취소</Text></Pressable>
    </ScrollView>}
    <MemoModal visible={!!memo} title={target?.name??''} initialTime={target?.visitTime} initialNote={target?.note} disabled={disabled} notice={disabled?editor.message:undefined}
      onCancel={()=>setMemo(null)} onSave={(visitTime,note)=>{if(memo&&!disabled)change(memo.day,places=>places.map((p,i)=>i===memo.index?{...p,visitTime,note}:p).sort((a,b)=>(a.visitTime??'').localeCompare(b.visitTime??'')));setMemo(null);}}/>
    <DestinationMapPicker visible={!!pick&&!disabled} pickerTitle={pick?.index===undefined?'장소 추가':'장소 교체'} initialDestination={plan?.destination??''} initialRegion={plan?.destination} initialCenter={visitMapCenter(plan?.travelDetails,pick&&plan?plan.days[pick.day]:undefined)} placeKind="visit" onSelect={()=>undefined} onClose={()=>setPick(null)} onPlaceSelect={selected}/>
 
  </SafeAreaView>;
}

