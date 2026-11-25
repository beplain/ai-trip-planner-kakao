import { Pressable, Text, View } from 'react-native';
import { C, s } from '@/lib/styles';

export type UndoNoticeValue = { name: string; busy: boolean; run: () => void };
export function UndoNotice({ notice, onDismiss }: { notice: UndoNoticeValue | null; onDismiss: () => void }) {
  if (!notice) return null;
  return <View pointerEvents="box-none" style={{ position: 'absolute', bottom: 20, left: 16, right: 16, zIndex: 100, elevation: 8 }}>
    <View accessibilityLiveRegion="polite" style={{ backgroundColor: C.text, borderRadius: 16, padding: 16 }}>
      <Text style={{ color: '#fff', marginBottom: 8 }}>{notice.name} 삭제됨</Text>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <Pressable accessibilityRole="button" disabled={notice.busy} onPress={notice.run} style={{ padding: 8 }}>
          <Text style={[s.primaryText, { color: '#fff' }]}>{notice.busy ? '복원 중…' : '실행 취소'}</Text>
        </Pressable>
        <Pressable accessibilityRole="button" accessibilityLabel="실행 취소 안내 닫기" onPress={onDismiss} style={{ padding: 8 }}>
          <Text style={{ color: '#fff' }}>닫기</Text>
        </Pressable>
      </View>
    </View>
  </View>;
}
