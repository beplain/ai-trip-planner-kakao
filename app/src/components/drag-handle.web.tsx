import { useEffect, useRef } from 'react';
import type { DragHandleProps } from './drag-handle';
/** Do not cancel a touch until a stationary 900ms hold. Swipes before that remain browser scrolling. */
export function DragHandle({disabled,onStart,onMove,onEnd,children}:DragHandleProps){
 const suppressClick=useRef(false);
 const cleanup=useRef<(()=>void)|null>(null);
 useEffect(()=>()=>cleanup.current?.(),[]);
 return <div
  onClickCapture={e=>{if(suppressClick.current){suppressClick.current=false;e.preventDefault();e.stopPropagation();}}}
  onContextMenu={e=>e.preventDefault()}
  onPointerDown={e=>{
   suppressClick.current=false;
   const target=e.target instanceof Element?e.target:null;
   if(disabled||e.button!==0||target?.closest('[data-testid="place-actions"],[data-testid="place-links"],[data-testid="place-replace"],input,textarea,a,[contenteditable="true"]'))return;
   cleanup.current?.();const id=e.pointerId,x=e.clientX,y=e.clientY;let held=false,ended=false;
   const timer=setTimeout(()=>{held=true;suppressClick.current=true;onStart(y);},900);
   const stop=(success:boolean)=>{if(ended)return;ended=true;clearTimeout(timer);document.removeEventListener('pointermove',move);document.removeEventListener('pointerup',up);document.removeEventListener('pointercancel',cancel);document.removeEventListener('touchmove',touch);window.removeEventListener('blur',cancel);if(held)onEnd(success);cleanup.current=null;};
   const move=(ev:PointerEvent)=>{if(ev.pointerId!==id)return;if(!held&&Math.hypot(ev.clientX-x,ev.clientY-y)>8){stop(false);return;}if(held){if(ev.cancelable)ev.preventDefault();onMove(ev.clientY);}};
   const touch=(ev:TouchEvent)=>{if(ev.touches.length>1){stop(false);return;}if(held&&ev.cancelable)ev.preventDefault();};
   const up=(ev:PointerEvent)=>{if(ev.pointerId===id)stop(true);};const cancel=()=>stop(false);
   document.addEventListener('pointermove',move,{passive:false});document.addEventListener('pointerup',up);document.addEventListener('pointercancel',cancel);document.addEventListener('touchmove',touch,{passive:false});window.addEventListener('blur',cancel);cleanup.current=cancel;
  }}
  style={{touchAction:'pan-y',userSelect:'none',WebkitTouchCallout:'none'}}>
   {children}
 </div>;
}
