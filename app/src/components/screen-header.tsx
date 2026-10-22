import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';

import { s } from '@/lib/styles';

type Props = { title: string; back?: boolean; right?: ReactNode; onBack?: () => void };

export function ScreenHeader({ title, back = true, right, onBack }: Props) {
  const goBack = () => {
    if (onBack) return onBack();
    if (router.canGoBack()) router.back();
    else router.replace('/');
  };
  return (
    <View style={s.header}>
      {back ? (
        <Pressable onPress={goBack} style={s.back} accessibilityLabel="뒤로">
          <Text style={s.backText}>‹</Text>
        </Pressable>
      ) : (
        <View style={s.logo}><Text style={s.logoText}>T</Text></View>
      )}
      <Text style={s.headerTitle} numberOfLines={1}>{title}</Text>
      <View style={{ minWidth: 42, alignItems: 'flex-end' }}>{right}</View>
    </View>
  );
}
