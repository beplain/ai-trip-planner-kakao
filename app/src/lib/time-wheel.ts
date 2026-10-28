export const TIME_HOURS = Array.from({ length: 24 }, (_, i) => i);
export const TIME_MINUTES = [0, 10, 20, 30, 40, 50];
export function wheelTimeParts(value: string) {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(value);
  return match ? { hour: Number(match[1]), minute: Math.floor(Number(match[2]) / 10) * 10 } : { hour: 9, minute: 0 };
}
export function formatWheelTime(hour: number, minute: number) {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}
