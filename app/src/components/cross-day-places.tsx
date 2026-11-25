import { Text, View } from 'react-native';
import type { Place } from '../../services/trips';
import type { CrossDayDrag } from '@/hooks/use-cross-day-drag';
import { placeRecommendations } from '@/lib/place-blurb';
import { C,s } from '@/lib/styles';
import { DragHandle } from './drag-handle';
import { PlaceCard } from './place-card';
type Props={places:Place[];dayIndex:number;crossDay:CrossDayDrag;blurbs?:ReadonlyMap<Place,string>;visitDate?:string;color:string;editable:boolean;busy:boolean;onDelete:(p:Place,i:number)=>void;onEditMemo:(p:Place,i:number)=>void;onReplace?:(p:Place,i:number)=>void};
export function CrossDayPlaces({places,dayIndex,crossDay,blurbs,visitDate,color,editable,busy,onDelete,onEditMemo,onReplace}:Props){
 const lines=blurbs?places.map(p=>blurbs.get(p)):placeRecommendations(places);
 const insertion=(index:number)=>crossDay.state?.target.day===dayIndex&&crossDay.state.target.index===index?<View style={{position:'absolute',top:0,left:0,right:0,zIndex:30,borderTopWidth:3,borderColor:color,paddingVertical:3,backgroundColor:'#fff'}}><Text style={{color,fontWeight:'700',fontSize:12}}>DAY {dayIndex+1} · 여기에 놓기</Text></View>:null;
 return <View>{!places.length&&<Text style={s.empty}>이 DAY로 장소를 옮길 수 있어요.</Text>}
  {places.map((p,i)=>{const active=crossDay.state?.source.day===dayIndex&&crossDay.state.source.index===i;return <View key={p.id??`${p.fixedEventId??p.placeId??p.name}:${i}`}>
   {insertion(i)}<View collapsable={false} ref={crossDay.bind(dayIndex,i)} style={{opacity:active?0.55:1}}>
    <DragHandle disabled={!editable||!!p.fixedEventId||busy||!!crossDay.state&&!active} active={active} onStart={y=>{void crossDay.start(dayIndex,i,y);}} onMove={crossDay.move} onEnd={crossDay.end}>
     {editable&&!p.fixedEventId&&<Text style={[s.mutedSmall,{paddingLeft:26,paddingVertical:5,color:active?C.primary:'#74809A'}]}>{active?'이동 중 · 원하는 DAY나 위치에 놓기':'☰  길게 눌러 이동'}</Text>}
     <View pointerEvents={crossDay.state?'none':'auto'}><PlaceCard place={p} visitDate={visitDate} index={i} isLast={i===places.length-1} color={color} editable={editable} busy={busy||!!crossDay.state} blurb={lines[i]} onDelete={()=>onDelete(p,i)} onEditMemo={()=>onEditMemo(p,i)} onReplace={onReplace?()=>onReplace(p,i):undefined}/></View>
    </DragHandle>
   </View></View>;})}
  <View style={{position:'relative'}}>{insertion(places.length)}</View>
  {!!crossDay.state&&crossDay.state.target.day===dayIndex&&<Text style={{color:C.sub,fontSize:12}}>놓으면 방문 시각을 자동 조정합니다.</Text>}
 </View>;
}
