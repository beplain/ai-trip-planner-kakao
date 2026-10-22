import { Modal, Pressable, Text, View } from 'react-native';

import { s } from '@/lib/styles';

export function DestinationMapPicker({ visible, onClose }: {
  visible: boolean; initialDestination: string; onSelect: (destination: string) => void; onClose: () => void;
}) {
  return (
    <Modal visible={visible} transparent onRequestClose={onClose}>
      <View style={{ flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#0006' }}>
        <View style={{ backgroundColor: '#fff', borderRadius: 20, padding: 24 }}>
          <Text style={s.sectionTitle}>지도 선택은 휴대폰 앱에서 이용해 주세요</Text>
          <Text style={s.subtitle}>웹에서는 목적지 입력칸에 지역명을 직접 입력할 수 있어요.</Text>
          <Pressable style={s.primary} onPress={onClose}><Text style={s.primaryText}>확인</Text></Pressable>
        </View>
      </View>
    </Modal>
  );
}
