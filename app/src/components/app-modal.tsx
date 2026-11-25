import { Modal, Platform, useWindowDimensions, View, type ModalProps } from 'react-native';
import { WEB_APP_WIDTH } from '@/lib/app-layout';

/** Web modals render outside the navigator, so they need the same width constraint. */
export function AppModal({ children, ...props }: ModalProps) {
  const { width } = useWindowDimensions();
  return <Modal {...props}>
    {Platform.OS === 'web' ? <View style={{ flex: 1, backgroundColor: props.transparent ? 'rgba(0,0,0,0.35)' : undefined }}>
      <View style={{ flex: 1, width: Math.min(width, WEB_APP_WIDTH), maxWidth: '100%', alignSelf: 'center', minWidth: 0, overflow: 'hidden' }}>{children}</View>
    </View> : children}
  </Modal>;
}
