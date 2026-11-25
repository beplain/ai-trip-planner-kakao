import type { ReactNode } from 'react';
import { ScrollView } from 'react-native';

export function DayTabs({ children }: { children: ReactNode }) {
  return <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flex: 1, minWidth: 0 }} contentContainerStyle={{ alignItems: 'center', gap: 8, paddingRight: 8 }}>{children}</ScrollView>;
}
