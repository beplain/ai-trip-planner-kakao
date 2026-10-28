import { useEffect, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import type { Place } from '../../services/trips';
import type { DragScroll } from '@/hooks/use-drag-scroll';
import { dropIndex, type RowBounds } from '@/lib/reorder';
import { C, s } from '@/lib/styles';
import { PlaceCard } from './place-card';

type Props = {
  places: Place[]; color: string; editable: boolean; busy: boolean; scroll: DragScroll;
  onReorder: (from: number, to: number) => void;
  onDelete: (place: Place, index: number) => void;
  onEditMemo: (place: Place, index: number) => void;
};
type Drag = { from: number; to: number; delta: number; height: number };

export function DraggablePlaces({ places, color, editable, busy, scroll, onReorder, onDelete, onEditMemo }: Props) {
  const bounds = useRef<RowBounds[]>([]);
  const session = useRef<{ from: number; startScroll: number; dy: number; to: number } | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const end = scroll.end;
  useEffect(() => () => { if (session.current) end(); }, [end]);

  function recalculate() {
    const current = session.current;
    if (!current) return;
    const rows = bounds.current.slice(0, places.length);
    const row = rows[current.from];
    if (!row || rows.length !== places.length) return;
    const total = rows.at(-1)!;
    const delta = Math.max(-row.y, Math.min(total.y + total.height - row.y - row.height, current.dy + scroll.getOffset() - current.startScroll));
    const target = dropIndex(rows, current.from, delta);
    // A general place may only move inside the same segment between fixed commitments.
    const low = places.slice(0, current.from).findLastIndex((p) => !!p.fixedEventId) + 1;
    const next = places.findIndex((p, i) => i > current.from && !!p.fixedEventId);
    const high = next < 0 ? places.length - 1 : next - 1;
    current.to = Math.max(low, Math.min(high, target));
    setDrag({ from: current.from, to: current.to, delta, height: row.height });
  }
  function finish(success: boolean) {
    const current = session.current;
    if (!current) return;
    session.current = null; scroll.end(); setDrag(null);
    if (success && current.to !== current.from) onReorder(current.from, current.to);
  }
  return <View>
    {!places.length && <Text style={s.empty}>장소가 없습니다.</Text>}
    {/* eslint-disable-next-line react-hooks/refs -- Gesture.Pan registers these touch callbacks; it does not execute them during render. */}
    {places.map((place, index) => {
      const gesture = Gesture.Pan().enabled(editable && !place.fixedEventId && !busy && places.length > 1).activateAfterLongPress(350).maxPointers(1).runOnJS(true)
        .onStart((e) => {
          if (scroll.isDragging() || bounds.current.slice(0, places.length).filter(Boolean).length !== places.length) return;
          session.current = { from: index, to: index, startScroll: scroll.getOffset(), dy: 0 };
          scroll.begin(e.absoluteY, recalculate); recalculate();
        })
        .onUpdate((e) => {
          if (session.current?.from !== index) return;
          session.current.dy = e.translationY; scroll.move(e.absoluteY); recalculate();
        })
        .onEnd((_e, success) => { if (session.current?.from === index) finish(success); })
        .onFinalize((_e, success) => { if (!success && session.current?.from === index) finish(false); });
      const active = drag?.from === index;
      const shift = !drag ? 0 : active ? drag.delta : drag.from < drag.to && index > drag.from && index <= drag.to ? -drag.height
        : drag.from > drag.to && index >= drag.to && index < drag.from ? drag.height : 0;
      return <GestureDetector key={place.id ?? `${index}-${place.placeId ?? place.name}`} gesture={gesture}>
        <View collapsable={false} onLayout={(e) => { bounds.current[index] = e.nativeEvent.layout; }}
          style={{ transform: [{ translateY: shift }], zIndex: active ? 20 : 0, elevation: active ? 8 : 0, opacity: active ? 0.92 : 1 }}>
          <View pointerEvents={drag ? 'none' : 'auto'}>
            {editable && !place.fixedEventId && places.length > 1 && <Text style={[s.mutedSmall, { paddingLeft: 26, paddingVertical: 5, color: active ? C.primary : '#74809A' }]}>{active ? `이동 중 · 장소 ${drag.to + 1} 위치에 놓기` : '☰  길게 눌러 이동'}</Text>}
            <PlaceCard place={place} index={index} isLast={index === places.length - 1} color={color} editable={editable} busy={busy || !!drag}
              onEditMemo={() => onEditMemo(place, index)} onDelete={() => onDelete(place, index)} />
          </View>
        </View>
      </GestureDetector>;
    })}
  </View>;
}
