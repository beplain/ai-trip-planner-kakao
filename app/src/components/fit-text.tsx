import { StyleSheet, Text, useWindowDimensions, type TextProps } from 'react-native';

type Props = TextProps & { fontSize: number };

export function FitText({ fontSize, style, ...props }: Props) {
  const { width, fontScale } = useWindowDimensions();
  const scale = Math.min(1.1, Math.max(0.9, width / 390));
  const allowFitting = fontScale <= 1;
  const lineHeight = StyleSheet.flatten(style)?.lineHeight;
  return (
    <Text {...props}
      style={[style, { fontSize: fontSize * scale, ...(lineHeight !== undefined && { lineHeight: lineHeight * scale }) }]}
      numberOfLines={allowFitting ? 2 : undefined}
      adjustsFontSizeToFit={allowFitting}
      minimumFontScale={0.9}
      textBreakStrategy="balanced"
      lineBreakStrategyIOS="hangul-word"
    />
  );
}
