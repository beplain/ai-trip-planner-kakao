import type { TravelDetails } from '../../services/trips';
type Transport = NonNullable<TravelDetails['transport']>;
export function transportContext(transport: Transport | undefined, date: string): string {
  return JSON.stringify([transport?.originLocation, transport?.originTime, transport?.arrivalLocation, transport?.mode, transport?.arrivalAuto, transport?.boardingStation, transport?.alightingStation, date]);
}
export function trainThreshold(time: string): string {
  const [h,m] = time.split(':').map(Number);
  const n = Math.max(0, h * 60 + m - 10);
  return `${Math.floor(n / 60).toString().padStart(2, '0')}:${(n % 60).toString().padStart(2, '0')}`;
}
export function returnQuery(t: Transport): Transport {
  return { ...t, boardingStation: t.returnBoardingStation, alightingStation: t.returnAlightingStation, originLocation: t.departureLocation, arrivalLocation: t.returnArrivalLocation, originTime: t.departureTime, train: t.returnTrain, arrivalAuto: t.returnArrivalAuto !== false };
}
function stationName(value: string): string { return value.trim().split(/\s+/)[0].replace(/[()]/g, '').replace(/역$/, ''); }
export function validTrainSelection(transport: Transport, date: string): boolean {
  const train = transport.train;
  const arrival = transport.alightingStation ?? transport.arrivalLocation;
  return !!train && !!arrival?.name.trim().split(/\s+/)[0].endsWith('역') && train.date === date && train.departureTime >= trainThreshold(transport.originTime ?? '00:00')
    && stationName((transport.boardingStation ?? transport.originLocation)?.name ?? '') === stationName(train.originStation)
    && stationName((transport.alightingStation ?? transport.arrivalLocation)?.name ?? '') === stationName(train.arrivalStation);
}
export function patchTransport(transport: Transport, patch: Partial<Transport>): Transport {
  const next = { ...transport, ...patch };
  if (['mode', 'originTime', 'originLocation', 'arrivalLocation', 'arrivalAuto', 'boardingStation', 'alightingStation'].some((key) => key in patch)) next.train = undefined;
  if (['mode', 'departureTime', 'departureLocation', 'returnArrivalLocation', 'returnArrivalAuto', 'returnBoardingStation', 'returnAlightingStation'].some((key) => key in patch)) { next.returnTrain = undefined; if (!('returnArrivalTime' in patch) && next.returnArrivalAuto !== false) next.returnArrivalTime = undefined; }
  if (['mode', 'originLocation'].some(key => key in patch)) next.boardingStation = undefined;
  if (['mode', 'departureLocation'].some(key => key in patch)) next.returnBoardingStation = undefined;
  if (['mode', 'arrivalLocation'].some(key => key in patch)) next.alightingStation = undefined;
  if (['mode', 'returnArrivalLocation'].some(key => key in patch)) next.returnAlightingStation = undefined;
  return next;
}
