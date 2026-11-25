import { AppModal as Modal } from './app-modal';
import { useCallback, useState } from 'react';
import { Pressable, ScrollView, Text, View, type AlertButton } from 'react-native';
import { C, s } from '@/lib/styles';

type Notice = { title: string; message?: string; buttons: AlertButton[] };

/** An in-screen dialog also works in Safari standalone mode; RN Alert is a no-op on web. */
export function useScreenAlert() {
  const [notice, setNotice] = useState<Notice | null>(null);
  const alert = useCallback((title: string, message?: string, buttons?: AlertButton[]) => {
    setNotice({ title, message, buttons: buttons?.length ? buttons : [{ text: '확인' }] });
  }, []);
  const choose = (button: AlertButton) => {
    setNotice(null);
    button.onPress?.();
  };
  const cancel = notice?.buttons.find(button => button.style === 'cancel');
  const dialog = notice && <Modal visible transparent animationType="fade" onRequestClose={() => {
    if (cancel) choose(cancel);
    else if (notice.buttons.length === 1) choose(notice.buttons[0]);
  }}>
    <View style={{ flex: 1, justifyContent: 'center', padding: 24, backgroundColor: 'rgba(0,0,0,0.4)' }}>
      <View accessibilityViewIsModal style={{ backgroundColor: C.bg, borderRadius: 20, padding: 24, width: '100%', maxWidth: 480, maxHeight: '85%', alignSelf: 'center' }}>
        <ScrollView keyboardShouldPersistTaps="handled">
          <Text accessibilityRole="header" style={s.sectionTitle}>{notice.title}</Text>
          {!!notice.message && <Text style={s.subtitle}>{notice.message}</Text>}
          {notice.buttons.map((button, index) => <Pressable key={index} accessibilityRole="button"
            accessibilityLabel={button.text ?? '확인'} onPress={() => choose(button)}
            style={[s.primary, button.style === 'cancel' && { backgroundColor: C.border }, button.style === 'destructive' && { backgroundColor: C.danger }]}>
            <Text style={[s.primaryText, button.style === 'cancel' && { color: C.text }]}>{button.text ?? '확인'}</Text>
          </Pressable>)}
        </ScrollView>
      </View>
    </View>
  </Modal>;
  return { alert, dialog };
}
