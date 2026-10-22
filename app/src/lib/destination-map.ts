/** The picker resolves a map point to a domestic destination area. */
export function buildDestinationMapHtml(jsKey: string) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>html,body,#map{margin:0;padding:0;width:100%;height:100%}</style></head><body>
<div id="map"></div><script>
function post(m){if(window.ReactNativeWebView)window.ReactNativeWebView.postMessage(JSON.stringify(m));}
window.onerror=function(){post({type:'error',message:'지도를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'});};
</script>
<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(jsKey)}&libraries=services&autoload=false"
onerror="post({type:'error',message:'카카오 지도 키와 JavaScript SDK 도메인(http://localhost), 카카오맵 사용 설정을 확인해 주세요.'})"></script>
<script>
var map, marker, geocoder, places, revision=0, userPin=null, userAccuracy=null;
window.showDestinationUserLocation=function(p){
  if(!map || !Number.isFinite(p.latitude) || !Number.isFinite(p.longitude))return;
  var pos=new kakao.maps.LatLng(p.latitude,p.longitude);
  if(!userPin){
    var label=document.createElement('div');label.textContent='● 내 위치';
    label.style.cssText='background:#fff;color:#1677ff;border:2px solid #1677ff;border-radius:16px;padding:6px 10px;font:bold 13px sans-serif;white-space:nowrap;box-shadow:0 2px 6px #0003';
    userPin=new kakao.maps.CustomOverlay({position:pos,content:label,xAnchor:0.5,yAnchor:1.5,zIndex:10});userPin.setMap(map);
    userAccuracy=new kakao.maps.Circle({center:pos,radius:0,strokeWeight:1,strokeColor:'#1677ff',strokeOpacity:0.4,fillColor:'#1677ff',fillOpacity:0.12});userAccuracy.setMap(map);
  }
  userPin.setPosition(pos);userAccuracy.setPosition(pos);
  userAccuracy.setRadius(Number.isFinite(p.accuracy)?Math.max(0,p.accuracy):0);
  map.setLevel(4);map.setCenter(pos);selectPoint(pos);
};
function selectPoint(pos){
  var current=++revision;
  marker.setPosition(pos);marker.setMap(map);
  post({type:'resolving'});
  geocoder.coord2RegionCode(pos.getLng(),pos.getLat(),function(result,status){
    if(current!==revision)return;
    if(status!==kakao.maps.services.Status.OK || !result || !result.length){
      marker.setMap(null);
      post({type:'selection-error',message:'이 지점의 국내 지역을 확인하지 못했어요. 육지의 다른 지점을 선택해 주세요.'});return;
    }
    var area=result.find(function(r){return r.region_type==='B';}) || result[0];
    var destination=[area.region_1depth_name,area.region_2depth_name,area.region_3depth_name].filter(Boolean).join(' ');
    if(!destination){post({type:'selection-error',message:'지역명을 확인하지 못했어요. 다른 지점을 선택해 주세요.'});return;}
    post({type:'selected',destination:destination,latitude:pos.getLat(),longitude:pos.getLng()});
  });
}
window.searchDestination=function(query){
  if(!map || !query.trim())return;
  var current=++revision;
  marker.setMap(null);post({type:'resolving'});
  places.keywordSearch(query,function(result,status){
    if(current!==revision)return;
    if(status!==kakao.maps.services.Status.OK || !result || !result.length){
      post({type:'selection-error',message:'검색 결과가 없어요. 지역명을 바꾸거나 지도에서 직접 선택해 주세요.'});return;
    }
    var pos=new kakao.maps.LatLng(Number(result[0].y),Number(result[0].x));
    map.setLevel(5);map.setCenter(pos);selectPoint(pos);
  });
};
if(typeof kakao==='undefined'){
  post({type:'error',message:'카카오 지도 키와 JavaScript SDK 도메인(http://localhost), 카카오맵 사용 설정을 확인해 주세요.'});
}else{
  kakao.maps.load(function(){
    map=new kakao.maps.Map(document.getElementById('map'),{center:new kakao.maps.LatLng(36.5,127.8),level:13});
    marker=new kakao.maps.Marker();geocoder=new kakao.maps.services.Geocoder();places=new kakao.maps.services.Places();
    map.addControl(new kakao.maps.ZoomControl(),kakao.maps.ControlPosition.RIGHT);
    kakao.maps.event.addListener(map,'click',function(e){selectPoint(e.latLng);});
    post({type:'ready'});
  });
}
</script></body></html>`;
}
