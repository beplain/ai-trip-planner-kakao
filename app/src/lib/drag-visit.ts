import type { Day, Place, TravelDetails } from '../../services/trips';
const minute=(v?:string)=>{if(!v||!/^([01]\d|2[0-3]):[0-5]\d$/.test(v))throw new Error('방문 시각을 확인해 주세요.');return +v.slice(0,2)*60 + +v.slice(3);};
const clock=(v:number)=>`${String(Math.floor(v/60)).padStart(2,'0')}:${String(v%60).padStart(2,'0')}`;
const stay=(p:Place)=>p.durationMinutes===45?45:60;
/** Reflow ordinary visits in the requested order; never edit fixed commitments. */
export function arrangeDay(places:Place[],day:number,count:number,details?:TravelDetails):Place[]{
 const gap=details?.movementMode==='CAR'?30:details?.movementMode==='WALK'?20:45;
 const transport=details?.transport;
 const start=day===1&&transport?minute(transport.arrivalTime)+(transport.mode==='PLANE'?60:30):480;
 const finish=day===count&&transport?minute((transport.mode==='KTX'&&transport.returnArrivalAuto!==false?transport.returnTrain?.departureTime:undefined)??transport.departureTime)-(transport.mode==='PLANE'?120:transport.mode==='CAR'?15:30):1320;
 const regular=places.map((p,i)=>({p,i})).filter(v=>!v.p.fixedEventId);
 const limits=regular.map(({p,i})=>{
   let low=start,high=finish-stay(p)-gap;
   places.forEach((event,j)=>{if(!event.fixedEventId)return;const t=minute(event.visitTime);
    if(j<i)low=Math.max(low,t+1);else high=Math.min(high,t-1);
    if(event.fixedEventId==='transport:arrival'||event.fixedEventId==='transport:departure')return;
    if(j<i)low=Math.max(low,t+15+gap);else high=Math.min(high,t-stay(p)-gap);
   });
   return {low:Math.ceil(low/5)*5,high:Math.floor(high/5)*5};
 });
 for(let i=limits.length-2;i>=0;i--)limits[i].high=Math.min(limits[i].high,limits[i+1].high-stay(regular[i].p)-gap);
 let previous=start;const assigned=new Map<number,Place>();
 regular.forEach(({p,i},n)=>{
  const low=Math.max(limits[n].low,previous),high=limits[n].high;
  if(low>high)throw new Error(`DAY ${day}에 장소를 모두 배치할 시간이 부족해요. 다른 위치나 DAY로 옮겨 주세요.`);
  let desired=low;try{desired=minute(p.visitTime);}catch{}
  const time=Math.max(low,Math.min(high,desired));previous=time+stay(p)+gap;
  const visitTime=clock(time);assigned.set(i,visitTime===p.visitTime?p:{...p,visitTime});
 });
 return places.map((p,i)=>assigned.get(i)??p);
}
/** targetIndex is an insertion boundary in the original target day's list. */
export function dragVisit(days:Day[],source:number,index:number,target:number,targetIndex:number,details?:TravelDetails):Day[]{
 const moving=days[source]?.places[index];if(!moving||!days[target])throw new Error('이동할 장소를 찾지 못했어요.');
 if(moving.fixedEventId)throw new Error('숙소·교통 고정 일정은 여행 정보에서 변경해 주세요.');
 const insert=Math.max(0,Math.min(days[target].places.length,targetIndex));
 if(source===target&&(insert===index||insert===index+1))return days;
 const base=days.map(d=>({...d,places:[...d.places]}));base[source].places.splice(index,1);
 const preferred=source===target&&insert>index?insert-1:insert;
 const slots=Array.from({length:base[target].places.length+1},(_,i)=>i).sort((a,b)=>Math.abs(a-preferred)-Math.abs(b-preferred));
 for(const slot of slots){
  const result=base.map(d=>({...d,places:[...d.places]}));result[target].places.splice(slot,0,moving);
  try{for(const di of new Set([source,target]))result[di].places=arrangeDay(result[di].places,di+1,days.length,details);return result;}catch{}
 }
 throw new Error(`DAY ${target+1}에 장소를 모두 배치할 시간이 부족해요. 다른 DAY로 옮겨 주세요.`);
}
