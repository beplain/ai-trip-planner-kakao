import { Pressable, Text, View } from 'react-native';
import { s } from '@/lib/styles';
import { DraftEditPopup } from './draft-edit-popup';

export function DraftEditNotice({ message, blocked, onRetry, editingKey, editorName }: { message: string; blocked: boolean; onRetry: () => unknown; editingKey?: string; editorName?: string | null }) {
  if(!message)return null;
  return <View accessibilityLiveRegion="polite" style={blocked?s.warnBox:s.infoBox}>
    {editingKey && <DraftEditPopup key={editingKey} name={editorName || '동행자'} />}
    <Text style={blocked?s.warnText:s.infoText}>{message}</Text>
    {blocked&&<Pressable accessibilityRole="button" onPress={()=>{void onRetry();}}><Text style={s.linkText}>편집 상태 다시 확인</Text></Pressable>}
  </View>;
}
