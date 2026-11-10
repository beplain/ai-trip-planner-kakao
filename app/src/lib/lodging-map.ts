export type LodgingPlace = {
  placeId: string; name: string; address: string; latitude: number; longitude: number; placeUrl: string;
};

/** Validate messages crossing the WebView boundary; lodging uses Kakao place IDs; transport also accepts explicit map points. */
export function parseLodgingPlace(value: unknown, allowMapPoint = false): LodgingPlace | null {
  if (!value || typeof value !== 'object') return null;
  const p = value as Record<string, unknown>;
  if (typeof p.placeId !== 'string' || !(/^\d{1,30}$/.test(p.placeId) || (allowMapPoint && /^point:-?\d{1,2}\.\d{6},-?\d{1,3}\.\d{6}$/.test(p.placeId)))
    || typeof p.name !== 'string' || !p.name.trim() || p.name.trim().length > 100
    || typeof p.address !== 'string' || !p.address.trim() || p.address.trim().length > 200
    || typeof p.latitude !== 'number' || !Number.isFinite(p.latitude) || Math.abs(p.latitude) > 90
    || typeof p.longitude !== 'number' || !Number.isFinite(p.longitude) || Math.abs(p.longitude) > 180) return null;
  return { placeId: p.placeId, name: p.name.trim(), address: p.address.trim(), latitude: p.latitude, longitude: p.longitude,
    placeUrl: p.placeId.startsWith('point:') ? `https://map.kakao.com/link/map/${encodeURIComponent(p.name.trim())},${p.latitude},${p.longitude}` : `https://place.map.kakao.com/${p.placeId}` };
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
onerror="post({type:'error',message:'지도를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'})"></script>
<script>
var lodgingOnly=${JSON.stringify(kind === 'lodging')};
var map,places,geocoder,markers=[],results=[],revision=0,info,userPin;
function clearResults(){markers.forEach(function(m){m.setMap(null);});markers=[];results=[];if(info)info.close();}
function selectLodging(id){
 var p=results.find(function(r){return r.placeId===String(id);});if(!p)return;
 var position=new kakao.maps.LatLng(p.latitude,p.longitude);map.panTo(position);
 var label=document.createElement('div');label.textContent=p.name;label.style.cssText='padding:8px;font-size:13px;white-space:nowrap';
 info.setContent(label);info.setPosition(position);info.open(map);
 post({type:'lodging-selected',place:p});
}
window.selectLodging=selectLodging;
function renderResults(data,fit,point){
 results=data.filter(function(p){return (!lodgingOnly||p.category_group_code==='AD5')&&p.id&&p.place_name&&(p.road_address_name||p.address_name)&&Number.isFinite(Number(p.y))&&Number.isFinite(Number(p.x));})
    .map(function(p){return {placeId:p.id,name:p.place_name,address:p.road_address_name||p.address_name,latitude:Number(p.y),longitude:Number(p.x),placeUrl:'https://place.map.kakao.com/'+p.id};});
 if(!results.length){post({type:'selection-error',message:'선택할 수 있는 장소가 없어요. 다른 검색어를 입력해 주세요.'});return;}
 if(point)results=results.filter(function(p){return distance(point,p.latitude,p.longitude)<=50;}).sort(function(a,b){return distance(point,a.latitude,a.longitude)-distance(point,b.latitude,b.longitude);}).slice(0,15);
 if(!results.length){post({type:'selection-error',message:'누른 지점 50m 안에 확인된 장소가 없어요. 건물 가까이 다시 누르거나 이름으로 검색해 주세요.'});return;}
 var bounds=new kakao.maps.LatLngBounds();
 results.forEach(function(p){var pos=new kakao.maps.LatLng(p.latitude,p.longitude);bounds.extend(pos);var marker=new kakao.maps.Marker({map:map,position:pos,title:p.name});markers.push(marker);kakao.maps.event.addListener(marker,'click',function(){selectLodging(p.placeId);});});
 if(fit && results.length===1){map.setCenter(new kakao.maps.LatLng(results[0].latitude,results[0].longitude));map.setLevel(4);}else if(fit){map.setBounds(bounds);}
 post({type:'lodging-results',places:results});
}
function distance(point,lat,lng){
 var rad=Math.PI/180,a=(lat-point.getLat())*rad,b=(lng-point.getLng())*rad;
 var h=Math.sin(a/2)*Math.sin(a/2)+Math.cos(point.getLat()*rad)*Math.cos(lat*rad)*Math.sin(b/2)*Math.sin(b/2);
 return 12742000*Math.asin(Math.sqrt(Math.min(1,h)));
}
function nearbyCategories(point,current){
 var codes=['MT1','CS2','PS3','SC4','AC5','PK6','OL7','SW8','BK9','CT1','AG2','PO3','AT4','AD5','FD6','CE7','HP8','PM9'];
 var remaining=codes.length,found={};
 codes.forEach(function(code){places.categorySearch(code,function(data,status){
  if(current!==revision)return;
  if(status===kakao.maps.services.Status.OK && Array.isArray(data))data.forEach(function(p){if(p.id)found[p.id]=p;});
  if(--remaining===0)renderResults(Object.keys(found).map(function(id){return found[id];}),false,point);
 },{location:point,radius:50,size:15,sort:kakao.maps.services.SortBy.DISTANCE});});
}
function selectPoint(point){
 if(lodgingOnly||!map)return;
 var current=++revision;clearResults();post({type:'resolving'});
 map.panTo(point);if(map.getLevel()>4)map.setLevel(4);
 geocoder.coord2Address(point.getLng(),point.getLat(),function(data,status){
  if(current!==revision)return;
  var address=status===kakao.maps.services.Status.OK && data && data[0];
  var road=address && address.road_address;
  var query=road && (road.building_name || road.address_name) || address && address.address && address.address.address_name;
  if(!query){nearbyCategories(point,current);return;}
  places.keywordSearch(query,function(hits,searchStatus){
   if(current!==revision)return;
   if(searchStatus===kakao.maps.services.Status.OK && Array.isArray(hits) && hits.some(function(p){return p.id && p.place_name && (p.road_address_name||p.address_name) && Number.isFinite(Number(p.y)) && Number.isFinite(Number(p.x)) && distance(point,Number(p.y),Number(p.x))<=50;}))renderResults(hits,false,point);
   else nearbyCategories(point,current);
  },{location:point,radius:50,size:15,sort:kakao.maps.services.SortBy.DISTANCE});
 });
}
function search(query,nearby){
 if(!map||!query.trim())return;
 var current=++revision;clearResults();post({type:'resolving'});
 var options={size:15};if(lodgingOnly)options.category_group_code='AD5';
 if(nearby){options.location=map.getCenter();options.radius=20000;options.sort=kakao.maps.services.SortBy.DISTANCE;}
 places.keywordSearch(query,function(data,status){
  if(current!==revision)return;
  if(status!==kakao.maps.services.Status.OK||!data||!data.length){post({type:'selection-error',message:'검색 결과가 없어요. 지역과 장소 이름을 구체적으로 입력해 주세요.'});return;}
  renderResults(data,true);
 },options);
}
window.searchDestination=function(query){search(query,false);};
window.showDestinationUserLocation=function(p){
 if(!map||!Number.isFinite(p.latitude)||!Number.isFinite(p.longitude))return;
 var pos=new kakao.maps.LatLng(p.latitude,p.longitude);map.setCenter(pos);map.setLevel(5);
 if(userPin)userPin.setMap(null);userPin=new kakao.maps.Marker({map:map,position:pos,title:'내 위치'});
 if(lodgingOnly)search('숙소',true);else selectPoint(pos);
};
if(typeof kakao==='undefined'){post({type:'error',message:'지도를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'});}
else{kakao.maps.load(function(){
 map=new kakao.maps.Map(document.getElementById('map'),{center:new kakao.maps.LatLng(36.5,127.8),level:13});
 places=new kakao.maps.services.Places();geocoder=new kakao.maps.services.Geocoder();info=new kakao.maps.InfoWindow();
 if(!lodgingOnly)kakao.maps.event.addListener(map,'click',function(event){selectPoint(event.latLng);});
 map.addControl(new kakao.maps.ZoomControl(),kakao.maps.ControlPosition.RIGHT);post({type:'ready'});
});}
</script></body></html>`;
}
