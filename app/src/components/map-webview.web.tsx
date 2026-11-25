import { forwardRef, useEffect, useId, useImperativeHandle, useRef, useState, type ReactElement, type Ref } from 'react';
import { View } from 'react-native';
import type { WebViewProps } from 'react-native-webview';
export type { WebViewMessageEvent } from 'react-native-webview';

export interface MapWebViewHandle<T = unknown> {
  injectJavaScript: (script: string) => void;
  readonly documentType?: T;
}

const BrowserMap = forwardRef<MapWebViewHandle, WebViewProps>(function BrowserMap(
  { source, style, onMessage, onLoadStart, onLoadEnd, onError, onTouchStart }, ref,
) {
  const iframe = useRef<HTMLIFrameElement>(null);
  const channel = useId();
  const callbacks = useRef({ onMessage, onLoadStart, onLoadEnd, onError });
  useEffect(() => { callbacks.current = { onMessage, onLoadStart, onLoadEnd, onError }; });
  const [documentHtml, setDocumentHtml] = useState('');
  const html = source && 'html' in source ? source.html : '';
  useImperativeHandle(ref, () => ({
    injectJavaScript(script: string) {
      iframe.current?.contentWindow?.postMessage({ channel, command: script }, window.location.origin);
    },
  }), [channel]);

  useEffect(() => {
    const origin = window.location.origin;
    // Use a real same-origin frame URL so Kakao Places also sees the registered domain.
    const bridge = '<script>(function(){const channel=' + JSON.stringify(channel) + ',origin=' + JSON.stringify(origin)
      + ';window.ReactNativeWebView={postMessage:function(data){parent.postMessage({channel:channel,data:String(data)},origin);}};'
      + 'window.addEventListener("message",function(e){if(e.source===parent&&e.origin===origin&&e.data&&e.data.channel===channel&&typeof e.data.command==="string"){(0,eval)(e.data.command);}});})();<\/script>';
    const document = html.includes('<head>') ? html.replace('<head>', '<head>' + bridge) : bridge + html;
    function receive(event: MessageEvent) {
      if (event.source !== iframe.current?.contentWindow || event.origin !== origin || event.data?.channel !== channel) return;
      if (event.data.type === 'map-frame-ready') {
        iframe.current?.contentWindow?.postMessage({channel,html:document},origin); return;
      }
      if (typeof event.data.data !== 'string') return;
      callbacks.current.onMessage?.({nativeEvent:{data:event.data.data}} as Parameters<NonNullable<WebViewProps['onMessage']>>[0]);
    }
    window.addEventListener('message',receive);
    callbacks.current.onLoadStart?.({nativeEvent:{}} as Parameters<NonNullable<WebViewProps['onLoadStart']>>[0]);
    setDocumentHtml(document);
    return () => window.removeEventListener('message', receive);
  }, [html, channel]);

  return <View style={[{ flex: 1, minHeight: 0, position: 'relative' }, style]} onTouchStart={onTouchStart}>
    {documentHtml ? <iframe ref={iframe} key={documentHtml} title="여행 지도" src={'/map-frame.html#' + encodeURIComponent(channel)}
      allow="geolocation" sandbox="allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox"
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', border: 0, display: 'block' }}
      onLoad={() => callbacks.current.onLoadEnd?.({ nativeEvent: {} } as Parameters<NonNullable<WebViewProps['onLoadEnd']>>[0])}
      onError={() => callbacks.current.onError?.({ nativeEvent: { description: '지도를 불러오지 못했습니다.' } } as Parameters<NonNullable<WebViewProps['onError']>>[0])} /> : null}
  </View>;
});

export const WebView = BrowserMap as <T = unknown>(props: WebViewProps & { ref?: Ref<MapWebViewHandle<T>> }) => ReactElement;
