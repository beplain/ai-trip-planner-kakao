import type { ComponentRef } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, type NativeScrollEvent, type NativeSyntheticEvent } from 'react-native';

/** One scroll owner for all the day's reorder lists, with edge scrolling during a drag. */
export function useDragScroll() {
  const ref = useRef<ComponentRef<typeof ScrollView>>(null);
  const offset = useRef(0);
  const contentHeight = useRef(0);
  const viewport = useRef({ top: 0, height: 0 });
  const draggingRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const pointer = useRef(0);
  const frame = useRef<(() => void) | null>(null);
  const begin = useCallback((absoluteY: number, recalculate: () => void) => {
    pointer.current = absoluteY; frame.current = recalculate; draggingRef.current = true; setDragging(true);
    ref.current?.getNativeScrollRef()?.measureInWindow((_x, y, _width, height) => { viewport.current = { top: y, height }; });
  }, []);
  const end = useCallback(() => {
    draggingRef.current = false; frame.current = null; setDragging(false);
  }, []);
  const move = useCallback((absoluteY: number) => { pointer.current = absoluteY; }, []);
  const onScroll = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    offset.current = e.nativeEvent.contentOffset.y;
    viewport.current.height = e.nativeEvent.layoutMeasurement.height;
    frame.current?.();
  }, []);
  useEffect(() => {
    const timer = setInterval(() => {
      if (!draggingRef.current) return;
      const { top, height } = viewport.current;
      const y = pointer.current - top;
      const speed = y < 80 ? -Math.min(18, (80 - y) / 3) : y > height - 80 ? Math.min(18, (y - height + 80) / 3) : 0;
      if (!speed || height <= 0) return;
      const next = Math.max(0, Math.min(contentHeight.current - height, offset.current + speed));
      if (next === offset.current) return;
      offset.current = next; ref.current?.scrollTo({ y: next, animated: false }); frame.current?.();
    }, 32);
    return () => { clearInterval(timer); draggingRef.current = false; frame.current = null; };
  }, []);
  const onContentSizeChange = useCallback((_width: number, height: number) => { contentHeight.current = height; }, []);
  const attach = useCallback((node: ComponentRef<typeof ScrollView> | null) => { ref.current = node; }, []);
  const getOffset = useCallback(() => offset.current, []);
  const isDragging = useCallback(() => draggingRef.current, []);
  const controller = useMemo(() => ({ getOffset, isDragging, begin, end, move }), [getOffset, isDragging, begin, end, move]);
  return { attach, dragging, onScroll, onContentSizeChange, controller };
}
export type DragScroll = ReturnType<typeof useDragScroll>['controller'];
