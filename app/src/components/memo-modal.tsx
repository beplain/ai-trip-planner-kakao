import { AppTextInput as TextInput } from '@/components/app-text-input';
import { AppModal as Modal } from './app-modal';
import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { C, s } from '@/lib/styles';

type Props = {
  visible: boolean;
  title: string;
  initialTime?: string;
  initialNote?: string;
  onCancel: () => void;
  onSave: (time: string, note: string) => void;
  disabled?: boolean;
  notice?: string;
};

/** 장소의 방문 시각/메모 편집 (Android에는 Alert.prompt가 없어 모달 사용) */
export function MemoModal({ visible, onCancel, ...form }: Props) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      {visible && <MemoForm key={JSON.stringify([form.title, form.initialTime, form.initialNote])}
        {...form} onCancel={onCancel} />}
    </Modal>
  );
}

function MemoForm({ title, initialTime, initialNote, onCancel, onSave, disabled = false, notice }: Omit<Props, 'visible'>) {
  const [time, setTime] = useState(initialTime ?? '');
  const [note, setNote] = useState(initialNote ?? '');

  const timeOk = time.trim() === '' || /^([01]\d|2[0-3]):[0-5]\d$/.test(time.trim());

  return (
    <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 24 }}>
      <View style={{ backgroundColor: '#fff', borderRadius: 20, padding: 20 }}>
        <Text style={s.sectionTitle} numberOfLines={1}>{title}</Text>
        {!!notice && <Text style={s.warnText}>{notice}</Text>}
        <Text style={s.label}>방문 시각 (HH:mm)</Text>
        <TextInput editable={!disabled} value={time} onChangeText={setTime} placeholder="예: 10:30" style={s.input} maxLength={5} />
        {!timeOk && <Text style={{ color: C.danger, fontSize: 12, marginTop: 4 }}>00:00~23:59 형식으로 입력해 주세요.</Text>}
        <Text style={s.label}>메모</Text>
        <TextInput editable={!disabled} value={note} onChangeText={setNote} placeholder="예: 오픈런 필요" style={[s.input, s.inputMulti]} multiline maxLength={500} />
        <View style={[s.row, { marginTop: 18 }]}>
          <Pressable style={[s.outlineButton, { flex: 1, marginTop: 0 }]} onPress={onCancel}><Text style={s.outlineText}>취소</Text></Pressable>
          <Pressable disabled={!timeOk || disabled} style={[s.primary, { flex: 1, marginBottom: 0, minHeight: 48 }, (!timeOk || disabled) && s.disabled]} onPress={() => onSave(time.trim(), note.trim())}>
            <Text style={s.primaryText}>저장</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}
