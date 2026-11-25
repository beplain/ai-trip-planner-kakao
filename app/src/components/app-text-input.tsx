import { forwardRef } from 'react';
import { Platform, StyleSheet, TextInput as NativeTextInput, type TextInputInstance, type TextInputProps } from 'react-native';

/** Safari focuses inputs below 16px by zooming the page. Keep every app input consistent. */
export const AppTextInput = forwardRef<TextInputInstance, TextInputProps>(function AppTextInput(props, ref) {
  const fontSize = StyleSheet.flatten(props.style)?.fontSize;
  return <NativeTextInput {...props} ref={ref} placeholderTextColor="#A4ACBA"
    style={[props.style, Platform.OS === 'web' && { fontSize: Math.max(16, fontSize ?? 16) }]} />;
});
