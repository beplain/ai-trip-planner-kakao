export type LodgingPlace = {
  placeId: string; name: string; address: string; latitude: number; longitude: number; placeUrl: string;
};

/** Validate messages crossing the WebView boundary; only actual lodging search results are selectable. */
export function parseLodgingPlace(value: unknown): LodgingPlace | null {
  if (!value || typeof value !== 'object') return null;
  const p = value as Record<string, unknown>;
  if (typeof p.placeId !== 'string' || !/^\d{1,30}$/.test(p.placeId)
    || typeof p.name !== 'string' || !p.name.trim() || p.name.trim().length > 100
    || typeof p.address !== 'string' || !p.address.trim() || p.address.trim().length > 200
    || typeof p.latitude !== 'number' || !Number.isFinite(p.latitude) || Math.abs(p.latitude) > 90
    || typeof p.longitude !== 'number' || !Number.isFinite(p.longitude) || Math.abs(p.longitude) > 180) return null;
  return { placeId: p.placeId, name: p.name.trim(), address: p.address.trim(), latitude: p.latitude, longitude: p.longitude,
    placeUrl: `https://place.map.kakao.com/${p.placeId}` };
}

export function buildLodgingMapHtml(jsKey: string, kind: 'lodging' | 'transport' = 'lodging') {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>html,body,#map{margin:0;padding:0;width:100%;height:100%}</style></head><body><div id="map"></div>
<script>
function post(m){if(window.ReactNativeWebView)window.ReactNativeWebView.postMessage(JSON.stringify(m));}
window.onerror=function(){post({type:'error',message:'장소 지도를 불러오지 못했어요. 잠시 후 다시 열어 주세요.'});};
</script>
<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(jsKey)}&libraries=services&autoload=false"
onerror="post({type:'error',message:'카카오 지도 키와 SDK 도메인(http://localhost) 설정을 확인해 주세요.'})"></script>
<script>
var lodgingOnly=${JSON.stringify(kind === 'lodging')};
var map,places,markers=[],results=[],revision=0,info,userPin;
function clearResults(){markers.forEach(function(m){m.setMap(null);});markers=[];results=[];if(info)info.close();}
function selectLodging(id){
 var p=results.find(function(r){return r.placeId===String(id);});if(!p)return;
 var position=new kakao.maps.LatLng(p.latitude,p.longitude);map.panTo(position);
 var label=document.createElement('div');label.textContent=p.name;label.style.cssText='padding:8px;font-size:13px;white-space:nowrap';
 info.setContent(label);info.setPosition(position);info.open(map);
 post({type:'lodging-selected',place:p});
}
window.selectLodging=selectLodging;
function search(query,nearby){
 if(!map||!query.trim())return;
 var current=++revision;clearResults();post({type:'resolving'});
 var options={size:15};if(lodgingOnly)options.category_group_code='AD5';
 if(nearby){options.location=map.getCenter();options.radius=20000;options.sort=kakao.maps.services.SortBy.DISTANCE;}
 places.keywordSearch(query,function(data,status){
  if(current!==revision)return;
  if(status!==kakao.maps.services.Status.OK||!data||!data.length){post({type:'selection-error',message:'검색 결과가 없어요. 지역과 장소 이름을 구체적으로 입력해 주세요.'});return;}
  results=data.filter(function(p){return (!lodgingOnly||p.category_group_code==='AD5')&&p.id&&p.place_name&&(p.road_address_name||p.address_name)&&Number.isFinite(Number(p.y))&&Number.isFinite(Number(p.x));})
    .map(function(p){return {placeId:p.id,name:p.place_name,address:p.road_address_name||p.address_name,latitude:Number(p.y),longitude:Number(p.x),placeUrl:'https://place.map.kakao.com/'+p.id};});
  if(!results.length){post({type:'selection-error',message:'선택할 수 있는 장소가 없어요. 다른 검색어를 입력해 주세요.'});return;}
  var bounds=new kakao.maps.LatLngBounds();
  results.forEach(function(p){var pos=new kakao.maps.LatLng(p.latitude,p.longitude);bounds.extend(pos);var marker=new kakao.maps.Marker({map:map,position:pos,title:p.name});markers.push(marker);kakao.maps.event.addListener(marker,'click',function(){selectLodging(p.placeId);});});
  if(results.length===1){map.setCenter(new kakao.maps.LatLng(results[0].latitude,results[0].longitude));map.setLevel(4);}else{map.setBounds(bounds);}
  post({type:'lodging-results',places:results});
 },options);
}
window.searchDestination=function(query){search(query,false);};
window.showDestinationUserLocation=function(p){
 if(!map||!Number.isFinite(p.latitude)||!Number.isFinite(p.longitude))return;
 var pos=new kakao.maps.LatLng(p.latitude,p.longitude);map.setCenter(pos);map.setLevel(5);
 if(userPin)userPin.setMap(null);userPin=new kakao.maps.Marker({map:map,position:pos,title:'내 위치'});search(lodgingOnly?'숙소':'역',true);
};
if(typeof kakao==='undefined'){post({type:'error',message:'카카오 지도 설정을 확인해 주세요.'});}
else{kakao.maps.load(function(){
 map=new kakao.maps.Map(document.getElementById('map'),{center:new kakao.maps.LatLng(36.5,127.8),level:13});
 places=new kakao.maps.services.Places();info=new kakao.maps.InfoWindow();
 map.addControl(new kakao.maps.ZoomControl(),kakao.maps.ControlPosition.RIGHT);post({type:'ready'});
});}
</script></body></html>`;
}
