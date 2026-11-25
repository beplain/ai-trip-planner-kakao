import { View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import type { ReactNode } from 'react';
export type DragHandleProps={disabled?:boolean;active?:boolean;onStart:(y:number)=>void;onMove:(y:number)=>void;onEnd:(success:boolean)=>void;children?:ReactNode};
export function DragHandle({disabled,onStart,onMove,onEnd,children}:DragHandleProps){
 const gesture=Gesture.Pan().enabled(!disabled).activateAfterLongPress(900).failOffsetY([-8,8]).failOffsetX([-8,8]).maxPointers(1).runOnJS(true)
  .onStart(e=>onStart(e.absoluteY)).onUpdate(e=>onMove(e.absoluteY)).onEnd((_e,success)=>onEnd(success)).onFinalize((_e,success)=>{if(!success)onEnd(false);});
 return <GestureDetector gesture={gesture}><View collapsable={false}>{children}</View></GestureDetector>;
}
