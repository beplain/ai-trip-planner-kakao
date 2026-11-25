import type { Place } from '../../services/trips';
export type BlurbInput = Pick<Place, 'name' | 'category' | 'categoryPath' | 'reason' | 'fixedEventId'>;
const GENERIC = '카카오맵에서 확인한 장소로 이동 가능한 시간대에 배치했습니다.';
const STOCK = new Set([
  '한 상 차림이 중심인 식당입니다.', '집밥이 차려지는 식당입니다.', '여행 중 든든하게 한 끼를 챙길 수 있어요.',
  '다음 일정 전에 여유롭게 한 끼 할 자리.', '국과 반찬이 함께 상에 오릅니다.', '앉으면 따뜻한 밥이 나옵니다.',
  '밥과 국이 기본으로 놓입니다.', '이 집의 상은 집밥으로 채워집니다.',
  '커피나 디저트와 함께 잠시 쉬어 가세요.', '여행지의 풍경과 분위기를 둘러보세요.',
  '여행 중에 들러 상품을 둘러보세요.', '자연 풍경을 감상하며 여유를 즐겨 보세요.',
  '여행지의 문화와 이야기를 만나 보세요.', '여행지에서 새로운 활동을 경험해 보세요.',
]);
function unwrap(value: string) {
  let text=value;
  for(let i=0;i<4;i++) {
    const marker=/^식사 일정 · (?:아침|점심|저녁) · ([^·]+?)(?: · (.*))?$/.exec(text);
    if(marker){text=(marker[2]??'').trim();continue;}
    const request=/^(?:아침|점심|저녁) 식사 요청: [^·]+ · (.*)$/.exec(text);
    if(request){text=request[1].trim();continue;}
    break;
  }
  return text;
}
export function endingKey(line: string) { return line.replace(/\s+/g,'').replace(/[.!?]+$/,''); }
/** Display only an existing recommendation. Map selection provides no evidence for a new one. */
export function placeRecommendation(place: BlurbInput): string {
  let line=unwrap((place.reason??'').replace(GENERIC,'').trim());
  const name=place.name?.trim();
  if(name){
    const escaped=name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    line=line.replace(new RegExp(`\\s*(?:[·|—–-]\\s*)?${escaped}[.!?]?\\s*$`),'').trim();
  }
  if(!line||STOCK.has(line)||line===`${place.category} 방문`||line==='장소 방문'||line==='대체 장소'
    || /^(아침|점심|저녁)(\s*식사)?(로|으로)/.test(line) || /나는\s*집/.test(line)
    || ['맛집','쇼핑','카페','관광','자연','문화','체험','식당','힐링','야경','기타','추천'].includes(line)) return '';
  return line;
}
export function placeRecommendations(places: readonly BlurbInput[]) {
  const seen=new Set<string>();
  return places.map(place=>{const line=placeRecommendation(place),key=endingKey(line);if(!line||seen.has(key))return '';seen.add(key);return line;});
}
export function itineraryRecommendations<T extends BlurbInput>(days: readonly {places:readonly T[]}[]) {
  const places=days.flatMap(day=>day.places),lines=placeRecommendations(places);
  return new Map(places.map((place,index)=>[place,lines[index]]));
}
