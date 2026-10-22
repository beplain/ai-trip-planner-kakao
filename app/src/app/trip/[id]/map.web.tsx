import { SafeAreaView } from 'react-native-safe-area-context';
import { Text, View } from 'react-native';

import { ScreenHeader } from '@/components/screen-header';
import { s } from '@/lib/styles';

/** react-native-maps는 웹을 지원하지 않아 웹에서는 안내만 표시 */
export default function TripMapWeb() {
  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="지도" />
      <View style={s.center}>
        <Text style={s.emptyTitle}>지도는 Android 앱에서 확인해 주세요</Text>
        <Text style={s.empty}>웹 미리보기에서는 지도 SDK를 사용할 수 없습니다.</Text>
      </View>
    </SafeAreaView>
  );
}
