import { useEffect, useRef, type CSSProperties } from 'react';
import { StyleSheet } from 'react-native';
import type { SingleLineTextProps } from './single-line-text';

/** Measure the browser's actual font instead of relying on native-only auto fitting. */
export function SingleLineText({ children, style, fontSize, minimumFontScale = 0.55 }: SingleLineTextProps) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = element.current;
    if (!node) return;
    let disposed = false;
    const probe = document.createElement('span');
    probe.setAttribute('aria-hidden', 'true');
    document.body.appendChild(probe);
    function fit() {
      if (disposed || !node) return;
      const width = node.getBoundingClientRect().width;
      if (width <= 0) return;
      const computed = getComputedStyle(node);
      Object.assign(probe.style, {
        position: 'fixed', left: '-10000px', top: '0', visibility: 'hidden',
        whiteSpace: 'nowrap', width: 'max-content', fontFamily: computed.fontFamily,
        fontWeight: computed.fontWeight, fontStyle: computed.fontStyle,
        letterSpacing: computed.letterSpacing, textTransform: computed.textTransform,
      });
      probe.textContent = children;
      let low = fontSize * minimumFontScale, high = fontSize;
      probe.style.fontSize = `${high}px`;
      if (probe.getBoundingClientRect().width > width - 1) {
        for (let i = 0; i < 14; i++) {
          const mid = (low + high) / 2;
          probe.style.fontSize = `${mid}px`;
          if (probe.getBoundingClientRect().width <= width - 1) low = mid;
          else high = mid;
        }
        node.style.fontSize = `${Math.floor(low * 100) / 100}px`;
      } else node.style.fontSize = `${fontSize}px`;
    }
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(node);
    window.addEventListener('resize', fit);
    document.fonts?.addEventListener('loadingdone', fit);
    void document.fonts?.ready.then(() => { if (!disposed) fit(); });
    return () => {
      disposed = true; observer.disconnect(); probe.remove();
      window.removeEventListener('resize', fit);
      document.fonts?.removeEventListener('loadingdone', fit);
    };
  }, [children, fontSize, minimumFontScale, style]);
  const flattened = StyleSheet.flatten(style);
  return <div ref={element} style={{
    ...flattened as CSSProperties,
    // React Native lineHeight is a pixel value; CSS numeric lineHeight is a multiplier.
    lineHeight: flattened?.lineHeight ? `${flattened.lineHeight}px` : undefined,
    fontSize, minWidth: 0, display: 'block', whiteSpace: 'nowrap',
    overflow: 'hidden', textOverflow: 'ellipsis',
  }}>{children}</div>;
}
