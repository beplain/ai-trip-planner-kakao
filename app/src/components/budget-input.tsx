import { Text, TextInput, View } from 'react-native';

import { formatBudget } from '@/lib/format';
import { s } from '@/lib/styles';

export function budgetInWords(digits: string) {
  if (!/^\d+$/.test(digits)) return '';
  let amount = Number(digits);
  if (!Number.isSafeInteger(amount)) return '';
  if (amount === 0) return '0원';
  const units = ['', '만', '억', '조'];
  const parts: string[] = [];
  for (const unit of units) {
    const group = amount % 10000;
    if (group) {
      const number = group % 1000 === 0 ? `${group / 1000}천` : group.toLocaleString('ko-KR');
      parts.unshift(`${number}${unit}`);
    }
    amount = Math.floor(amount / 10000);
  }
  return `${parts.join(' ')}원`;
}

export function BudgetInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const formatted = formatBudget(value);
  const numeric = /^[\d,]+원$/.test(formatted);
  const hint = numeric ? budgetInWords(formatted.replace(/\D/g, '')) : '';
  return (
    <View>
    <View style={[s.input, { flexDirection: 'row', alignItems: 'center', paddingVertical: 0 }]}>
      <TextInput
        accessibilityLabel="예산 금액"
        value={numeric ? formatted.slice(0, -1) : formatted}
        onChangeText={(text) => onChange(text.replace(/\D/g, '').replace(/^0+(?=\d)/, '').slice(0, 15))}
        keyboardType="number-pad"
        placeholder="예: 1,000,000"
        style={{ flex: 1, fontSize: 16, paddingVertical: 14, color: '#172033' }}
      />
      {(numeric || !formatted) && <Text style={{ fontSize: 16, color: '#172033', marginLeft: 4 }}>원</Text>}
    </View>
      {!!hint && <Text accessibilityLiveRegion="polite" style={[s.mutedSmall, { marginTop: 6, marginLeft: 4 }]}>{hint}</Text>}
    </View>
  );
}
