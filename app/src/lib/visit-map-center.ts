import type { Day, TravelDetails } from '../../services/trips';
export function visitMapCenter(details: TravelDetails | undefined, day: Day | undefined) {
  const stays = [...(details?.stays ?? [])].filter(s => day && s.checkInDay <= day.dayNumber && s.checkOutDay >= day.dayNumber)
    .sort((a,b) => b.checkInDay - a.checkInDay);
  const positions = [...stays, ...(day?.places ?? [])];
  const position = positions.find(p => typeof p.latitude === 'number' && Number.isFinite(p.latitude) && typeof p.longitude === 'number' && Number.isFinite(p.longitude));
  return position ? { latitude: position.latitude!, longitude: position.longitude! } : undefined;
}
