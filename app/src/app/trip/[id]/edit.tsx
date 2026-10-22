import { Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { s } from '@/lib/styles';

/** Keep old links readable, while preventing editing of finalized travel conditions. */
export default function EditTripRoute() {
  return <RequireAuth><SafeAreaView style={s.safe}><ScreenHeader title="여행 정보 수정" /><View style={s.content}>
    <Text style={s.sectionTitle}>여행 정보는 초안에서만 수정할 수 있어요</Text>
    <Text style={s.subtitle}>확정된 여행의 목적지·기간·예산·스타일은 변경할 수 없습니다. 초안 화면의 여행 정보 수정 버튼을 이용해 주세요.</Text>
  </View></SafeAreaView></RequireAuth>;
}
