import type { Place } from './trips';

/** A saved receipt describes the generated slot; it is not a live opening-hours lookup. */
export function businessHoursNotice(place: Place, visitDate?: string) {
  const receipt = place.businessHours;
  if (receipt?.status === 'VERIFIED') {
    if (!receipt.checkedAt || !receipt.checkedVisitTime || !receipt.visitDate) {
      return { verified: false, label: '운영시간 미확인', detail: '방문 시간의 확인 기록이 없어요.' };
    }
    if (!visitDate || receipt.visitDate !== visitDate) {
      return { verified: false, label: '운영시간 재확인 필요', detail: '방문 날짜가 달라 생성 당시 확인 결과를 적용할 수 없어요.' };
    }
    if (receipt.checkedVisitTime !== place.visitTime) {
      return { verified: false, label: '운영시간 재확인 필요', detail: '방문 시간이 변경됐어요. 변경된 시간은 아직 확인하지 않았어요.' };
    }
    return {
      verified: true,
      label: '운영시간 확인됨.',
      detail: receipt.basis === 'REGULAR'
        ? 'Google Maps · 요일별 운영시간 기준, 해당 날짜의 임시휴무는 미확인'
        : 'Google Maps · 생성 당시 방문 날짜·시간 기준',
    };
  }
  return {
    verified: false,
    label: '운영시간 미확인',
    detail: receipt?.detail || '현재 방문 날짜·시간의 확인 기록이 없어요.',
  };
}
