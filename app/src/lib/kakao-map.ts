import { Linking } from 'react-native';

import type { Place } from '../../services/trips';

/** 카카오맵 장소 상세 페이지 (영업시간·메뉴·예약 정보). 미확인 장소는 이름으로 검색 */
export function placePageUrl(p: Place) {
  if (p.placeUrl) return p.placeUrl.replace(/^http:\/\//, 'https://');
  return `https://map.kakao.com/link/search/${encodeURIComponent(p.address ? `${p.name} ${p.address}` : p.name)}`;
}

function hasCoords(p: Place): p is Place & { latitude: number; longitude: number } {
  return typeof p.latitude === 'number' && typeof p.longitude === 'number';
}

/**
 * 길찾기: 카카오맵 앱(kakaomap://)으로 열고, 앱이 없으면 웹 카카오맵으로.
 * 교통 상황·경로 계산은 카카오맵 앱에서 확인한다.
 */
export async function openDirections(p: Place) {
  if (!hasCoords(p)) {
    await Linking.openURL(placePageUrl(p)).catch(() => undefined);
    return;
  }
  const app = `kakaomap://route?ep=${p.latitude},${p.longitude}&by=PUBLICTRANSIT`;
  const web = `https://map.kakao.com/link/to/${encodeURIComponent(p.name)},${p.latitude},${p.longitude}`;
  try {
    await Linking.openURL(app);
  } catch {
    await Linking.openURL(web).catch(() => undefined);
  }
}

export function openPlacePage(p: Place) {
  Linking.openURL(placePageUrl(p)).catch(() => undefined);
}

/** WebView 안에서 카카오맵 JavaScript SDK로 지도를 그리는 HTML */
export function buildKakaoMapHtml(jsKey: string) {
  return `<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  html,body,#map{margin:0;padding:0;width:100%;height:100%;}
  .pin{width:26px;height:26px;border-radius:13px;color:#fff;font:700 12px sans-serif;display:flex;
       align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35);}
  .pin.sel{transform:scale(1.25);}
</style>
</head><body>
<div id="map"></div>
<script>
  function post(m){ if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify(m)); }
  window.onerror = function(msg){ post({ type: 'error', message: String(msg) }); };
</script>
<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(jsKey)}&autoload=false"
        onerror="post({type:'error',message:'sdk-load'})"></script>
<script>
  var map = null, objs = [], pins = {}, userPin = null, userAccuracy = null;
  var latestGroups = [], latestRoutes = [], lineEntries = [], renderQueued = false;
  function frame(fn){ (window.requestAnimationFrame || function(cb){ return setTimeout(cb, 16); })(fn); }
  function refreshLines(){ lineEntries.forEach(function(entry){ entry.line.setPath(entry.path); }); }
  function scheduleRender(){
    if (!map || renderQueued) return;
    renderQueued = true;
    frame(function(){ frame(function(){
      renderQueued = false;
      var container = document.getElementById('map');
      if (!container.clientWidth || !container.clientHeight) return;
      map.relayout();
      drawGroups(latestGroups);
      drawRoutes();
      frame(refreshLines);
    }); });
  }
  window.showUserLocation = function(p){
    if (!map || !Number.isFinite(p.latitude) || !Number.isFinite(p.longitude)) return;
    var pos = new kakao.maps.LatLng(p.latitude, p.longitude);
    if (!userPin) {
      var el = document.createElement('div');
      el.textContent = '● 내 위치';
      el.style.cssText = 'background:#fff;color:#1677ff;border:2px solid #1677ff;border-radius:16px;padding:6px 10px;font:bold 13px sans-serif;white-space:nowrap;box-shadow:0 2px 6px #0003';
      userPin = new kakao.maps.CustomOverlay({position:pos,content:el,xAnchor:0.5,yAnchor:0.5,zIndex:10});
      userPin.setMap(map);
      userAccuracy = new kakao.maps.Circle({center:pos,radius:0,strokeWeight:1,strokeColor:'#1677ff',strokeOpacity:0.4,fillColor:'#1677ff',fillOpacity:0.12});
      userAccuracy.setMap(map);
    }
    userPin.setPosition(pos);
    userAccuracy.setPosition(pos);
    userAccuracy.setRadius(Number.isFinite(p.accuracy) ? Math.max(0,p.accuracy) : 0);
    map.setLevel(4);
    map.setCenter(pos);
  };
  function clearAll(){ objs.forEach(function(o){ o.setMap(null); }); objs = []; pins = {}; }
  window.selectPin = function(key){
    Object.keys(pins).forEach(function(k){ pins[k].className = 'pin' + (k === key ? ' sel' : ''); });
  };
  window.renderPlaces = function(groups){
    latestGroups = groups || [];
    scheduleRender();
  };
  window.renderRoutes = function(routes){ latestRoutes = routes || []; if (map) drawRoutes(); };
  function drawRoutes(){
    lineEntries.forEach(function(entry){ entry.line.setMap(null); }); lineEntries = [];
    var bounds = new kakao.maps.LatLngBounds(), count = 0;
    latestGroups.forEach(function(g){ g.places.forEach(function(p){ bounds.extend(new kakao.maps.LatLng(p.lat,p.lng)); }); });
    latestRoutes.forEach(function(segment){
      if (!segment.points || segment.points.length < 2) return;
      var path = segment.points.map(function(p){ var pos = new kakao.maps.LatLng(p.latitude, p.longitude); bounds.extend(pos); count++; return pos; });
      var color = segment.mode === 'BUS' ? '#2563eb' : segment.mode === 'SUBWAY' ? '#16a34a' : segment.mode === 'WALKING' ? '#64748b' : segment.color;
      var line = new kakao.maps.Polyline({path:path,strokeWeight:4,strokeColor:color || '#4169E1',strokeOpacity:0.85,strokeStyle:segment.mode === 'WALKING' ? 'shortdash' : 'solid'});
      line.setMap(map); lineEntries.push({line:line,path:path});
    });
    if (count > 1) map.setBounds(bounds, 60, 40, 220, 40);
    refreshLines();
  }
  function drawGroups(groups){
    clearAll();
    var bounds = new kakao.maps.LatLngBounds(), n = 0, last = null;
    groups.forEach(function(g){
      var path = [];
      g.places.forEach(function(p){
        var pos = new kakao.maps.LatLng(p.lat, p.lng);
        path.push(pos); bounds.extend(pos); n++; last = pos;
        var el = document.createElement('div');
        el.className = 'pin'; el.style.background = g.color; el.textContent = p.label;
        el.addEventListener('click', function(){ window.selectPin(p.key); post({ type: 'select', key: p.key }); });
        pins[p.key] = el;
        var ov = new kakao.maps.CustomOverlay({ position: pos, content: el, xAnchor: 0.5, yAnchor: 0.5, clickable: true });
        ov.setMap(map); objs.push(ov);
      });

    });
    if (n === 1) { map.setLevel(4); map.setCenter(last); }
    else if (n > 1) { map.setBounds(bounds, 60, 40, 140, 40); }
  }
  if (typeof kakao === 'undefined') {
    post({ type: 'error', message: 'sdk-load' });
  } else {
    kakao.maps.load(function(){
      map = new kakao.maps.Map(document.getElementById('map'), { center: new kakao.maps.LatLng(36.5, 127.8), level: 12 });
      kakao.maps.event.addListener(map, 'idle', refreshLines);
      kakao.maps.event.addListener(map, 'tilesloaded', refreshLines);
      window.addEventListener('resize', scheduleRender);
      if (window.ResizeObserver) new window.ResizeObserver(scheduleRender).observe(document.getElementById('map'));
      scheduleRender();
      post({ type: 'ready' });
    });
  }
</script>
</body></html>`;
}
