import type { Day, Place, TravelDetails } from '../../services/trips';
const minute=(v?:string)=>{if(!v||!/^([01]\d|2[0-3]):[0-5]\d$/.test(v))throw new Error('방문 시각을 선택해 주세요.');return Number(v.slice(0,2))*60+Number(v.slice(3));};
function duration(p:Place,d?:TravelDetails){if(!p.fixedEventId)return p.durationMinutes===45?45:60;if(p.fixedEventId==='transport:arrival')return d?.transport?.mode==='PLANE'?60:30;return p.fixedEventId.startsWith('transport:')?0:15;}
export function moveVisit(days:Day[],source:number,index:number,target:number,time:string,details?:TravelDetails):Day[]{
 const place=days[source]?.places[index];if(!place||!days[target])throw new Error('방문일이나 장소를 찾지 못했어요.');
 if(place.fixedEventId)throw new Error('숙소·교통 고정 일정은 여행 정보에서 변경해 주세요.');
 const start=minute(time),end=start+duration(place,details),gap=details?.movementMode==='CAR'?30:details?.movementMode==='WALK'?20:45;
 if(end>1440)throw new Error('방문이 자정을 넘지 않는 시각을 선택해 주세요.');
 for(let i=0;i<days[target].places.length;i++){if(source===target&&i===index)continue;const other=days[target].places[i],t=minute(other.visitTime);if(!(end+gap<=t||t+duration(other,details)+gap<=start))throw new Error(`${other.name} 일정과 체류·이동 시간이 겹칩니다. 다른 시각을 선택해 주세요.`);}
 return days.map((d,di)=>{let places=d.places.filter((_,pi)=>!(di===source&&pi===index));if(di===target)places=[...places,{...place,visitTime:time}].sort((a,b)=>minute(a.visitTime)-minute(b.visitTime));return {...d,places};});
}
