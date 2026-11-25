import { Text, type StyleProp, type TextStyle } from 'react-native';

export type SingleLineTextProps = { children: string; style?: StyleProp<TextStyle>; fontSize: number; minimumFontScale?: number };

export function SingleLineText({ children, style, fontSize, minimumFontScale = 0.55 }: SingleLineTextProps) {
  return <Text style={[style, { fontSize }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={minimumFontScale}>{children}</Text>;
}
