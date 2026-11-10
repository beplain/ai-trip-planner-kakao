import { api } from './api';
import type { TravelDetails } from './trips';
export type RoutePoint = { latitude: number; longitude: number };
export type RouteSegment = { mode: 'CAR' | 'BUS' | 'SUBWAY' | 'WALKING'; points: RoutePoint[] };
export type RouteLeg = { status: 'OK' | 'PARTIAL' | 'UNAVAILABLE'; segments: RouteSegment[] };
export async function loadMapRoutes(mode: TravelDetails['movementMode'], legs: { from: RoutePoint; to: RoutePoint }[]) {
  return (await api.post<{ legs: RouteLeg[] }>('/api/trips/map-routes', { mode, legs }, { timeout: 18000 })).data.legs;
}
