import { useEffect, useMemo, useRef, useState, type ComponentRef } from 'react';
import { View } from 'react-native';
import type { DragScroll } from './use-drag-scroll';
type Position={day:number;index:number};
type Box=Position&{top:number;height:number;dayBox:boolean};
type Actions={start:()=>Promise<boolean>;drop:(source:Position,target:Position)=>void|Promise<void>;cancel:()=>void;error:(message:string)=>void};
export function useCrossDayDrag(scroll:DragScroll,actions:Actions){
 const callbacks=useRef(actions);useEffect(()=>{callbacks.current=actions;});
 const views=useRef(new Map<string,{day:number;index:number;dayBox:boolean;node:ComponentRef<typeof View>}>());
 const running=useRef(false),generation=useRef(0),pointer=useRef(0);
 const live=useRef<{source:Position;target:Position;boxes:Box[];offset:number;origin:number}|null>(null);
 const [state,setState]=useState<{source:Position;target:Position;delta:number}|null>(null);
 const controller=useMemo(()=>{
  const recalc=()=>{const active=live.current;if(!active)return;
   const y=pointer.current+scroll.getOffset()-active.offset;
   const days=active.boxes.filter(b=>b.dayBox);if(!days.length)return;
   const day=days.reduce((a,b)=>Math.abs(y-Math.max(a.top,Math.min(y,a.top+a.height)))<=Math.abs(y-Math.max(b.top,Math.min(y,b.top+b.height)))?a:b);
   const rows=active.boxes.filter(b=>!b.dayBox&&b.day===day.day).sort((a,b)=>a.index-b.index);
   const next=rows.find(b=>y<b.top+b.height/2);
   active.target={day:day.day,index:next?.index??(rows.length?rows[rows.length-1].index+1:0)};
   setState({source:active.source,target:active.target,delta:y-active.origin});
  };
  return {
   bind:(day:number,index:number,dayBox=false)=>(node:ComponentRef<typeof View>|null)=>{const key=`${day}:${dayBox?'day':index}`;if(node)views.current.set(key,{day,index,dayBox,node});else views.current.delete(key);},
   start:async(day:number,index:number,y:number)=>{
    if(running.current||live.current)return;running.current=true;const attempt=++generation.current;pointer.current=y;
    let allowed=false;
    try{
     allowed=await callbacks.current.start();if(!allowed)return;
     if(attempt!==generation.current){callbacks.current.cancel();return;}
     const boxes=(await Promise.all([...views.current.values()].map(v=>new Promise<Box|null>(resolve=>{
      const timer=setTimeout(()=>resolve(null),250);
      v.node.measureInWindow((_x,top,_w,height)=>{clearTimeout(timer);resolve(height>0?{day:v.day,index:v.index,dayBox:v.dayBox,top,height}:null);});
     })))).filter((b):b is Box=>b!==null);
     if(attempt!==generation.current||!boxes.some(b=>b.dayBox)){callbacks.current.cancel();return;}
     live.current={source:{day,index},target:{day,index},boxes,offset:scroll.getOffset(),origin:y};
     scroll.begin(pointer.current,recalc);recalc();
    }catch(error){live.current=null;scroll.end();setState(null);if(allowed)callbacks.current.cancel();callbacks.current.error(error instanceof Error?error.message:'이동을 시작하지 못했어요.');}
    finally{running.current=false;}
   },
   move:(y:number)=>{pointer.current=y;scroll.move(y);recalc();},
   end:(success:boolean)=>{
    generation.current++;const active=live.current;live.current=null;scroll.end();setState(null);
    if(!active)return;
    void(async()=>{try{if(success)await callbacks.current.drop(active.source,active.target);}catch(error){callbacks.current.error(error instanceof Error?error.message:'일정을 옮기지 못했어요.');}finally{callbacks.current.cancel();}})();
   },
   dispose:()=>{generation.current++;if(live.current){live.current=null;scroll.end();callbacks.current.cancel();}}
  };
 },[scroll]);
 useEffect(()=>()=>controller.dispose(),[controller]);
 return {...controller,state};
}
export type CrossDayDrag=ReturnType<typeof useCrossDayDrag>;
