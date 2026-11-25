import { AppModal as Modal } from './app-modal';
import { useCallback, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { Pressable, Text, View } from 'react-native';
import { C, s } from '@/lib/styles';

/** Mounted for one observed editing session; polling does not reopen a dismissed notice. */
export function DraftEditPopup({ name }: { name: string }) {
  const [dismissed, setDismissed] = useState(false);
  const [focused, setFocused] = useState(false);
  useFocusEffect(useCallback(() => {
    setFocused(true);
    return () => setFocused(false);
  }, []));
  const dismiss = () => setDismissed(true);
  return <Modal visible={focused && !dismissed} transparent animationType="fade" onRequestClose={dismiss}>
    <View style={{ flex: 1, justifyContent: 'center', padding: 24, backgroundColor: 'rgba(0,0,0,0.4)' }}>
      <View accessibilityViewIsModal style={{ backgroundColor: C.bg, borderRadius: 20, padding: 24, width: '100%', maxWidth: 400, alignSelf: 'center' }}>
        <Text accessibilityRole="header" style={s.sectionTitle}>{name}님이 일정을 수정 중입니다.</Text>
        <Text style={s.subtitle}>수정이 끝나면 자동으로 닫힙니다. 기다리는 동안 일정을 계속 확인할 수 있으며, 수정 기능은 잠시 잠깁니다.</Text>
        <Pressable accessibilityRole="button" onPress={dismiss} style={[s.primary, { marginBottom: 0 }]}>
          <Text style={s.primaryText}>일정 계속 보기</Text>
        </Pressable>
      </View>
    </View>
  </Modal>;
}
