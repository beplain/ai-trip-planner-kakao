import { api } from './api';

// 백엔드 TripDtos.java 와 같은 형태. (서버는 null 필드를 생략하므로 대부분 optional)

export type Role = 'OWNER' | 'EDITOR' | 'VIEWER';

export type Place = {
  id?: number; // 저장된 일정의 장소 id (초안에는 없음)
  placeId?: string; // 카카오 장소 id (검증된 경우)
  name: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  visitTime?: string;
  reason?: string;
  note?: string;
  category?: string;
  verified: boolean;
  placeUrl?: string; // 카카오맵 장소 상세 (영업시간·메뉴·예약 정보)
  phone?: string;
  orderIndex?: number;
};

export type Day = { id?: number; dayNumber: number; date?: string; places: Place[] };

export type Member = { userId: number; name: string; email?: string; role: Role };

export type Trip = {
  calendar?: CalendarResult;
  id: number;
  title: string;
  destination: string;
  startDate?: string;
  endDate?: string;
  dayCount: number;
  budget?: string;
  styles: string[];
  version: number;
  updatedAt: string;
  myRole: Role;
  days: Day[];
  members: Member[];
};

export type CalendarResult = {
  status: 'NOT_CONNECTED' | 'NOT_REGISTERED' | 'NO_DATE' | 'PENDING' | 'CREATED' | 'FAILED' | 'UNKNOWN';
  message: string;
  eventId?: string;
};

export type TripSummary = {
  id: number;
  title: string;
  destination: string;
  startDate?: string;
  endDate?: string;
  dayCount: number;
  budget?: string;
  styles: string[];
  myRole: Role;
  placeCount: number;
  memberCount: number;
  updatedAt: string;
};

export type TripDraft = {
  request?: string;
  title: string;
  destination: string;
  startDate?: string;
  endDate?: string;
  budget?: string;
  styles: string[];
  days: Day[];
  source?: 'AI' | 'SAMPLE';
  warnings?: string[];
};

export type GenerateInput = {
  destination: string;
  startDate?: string;
  days: number;
  budget?: string;
  styles: string[];
  request?: string;
};

export type UpdateTripInput = {
  title: string;
  destination: string;
  startDate?: string;
  days: number;
  budget?: string;
  styles: string[];
  version: number;
};

export type Invite = { token: string; role: Role; link: string; expiresAt: string };
export type InvitePreview = {
  draftId?: string;
  tripId: number;
  tripTitle: string;
  destination: string;
  role: Role;
  invitedBy: string;
  expired: boolean;
  alreadyMember: boolean;
};
export type Changes = { changed: boolean; version: number; updatedAt: string; trip?: Trip };

const t = (id: number | string) => `/api/trips/${encodeURIComponent(String(id))}`;

export const tripsApi = {
  /** AI 초안 생성 (AI + 장소 검증으로 20~60초 걸릴 수 있음) */
  async generate(input: GenerateInput) {
    return (await api.post<TripDraft>('/api/trips/generate', input, { timeout: 300000 })).data;
  },
  async create(draft: TripDraft) {
    return (await api.post<Trip>('/api/trips', draft, { timeout: 45000 })).data;
  },
  async calendarStatus(id: number) {
    return (await api.get<CalendarResult>(`${t(id)}/calendar`)).data;
  },
  async registerCalendar(id: number) {
    return (await api.post<CalendarResult>(`${t(id)}/calendar`, undefined, { timeout: 45000 })).data;
  },
  async list() {
    return (await api.get<TripSummary[]>('/api/trips')).data;
  },
  async get(id: number | string) {
    return (await api.get<Trip>(t(id))).data;
  },
  async update(id: number, input: UpdateTripInput) {
    return (await api.put<Trip>(t(id), input)).data;
  },
  async remove(id: number) {
    await api.delete(t(id));
  },
  async reorder(id: number, dayId: number, placeIds: number[], version: number) {
    return (await api.put<Trip>(`${t(id)}/days/${dayId}/places/order`, { placeIds, version })).data;
  },
  async updatePlace(id: number, placeId: number, visitTime: string, note: string, version: number) {
    return (await api.put<Trip>(`${t(id)}/places/${placeId}`, { visitTime, note, version })).data;
  },
  async deletePlace(id: number, placeId: number, version: number) {
    return (await api.delete<Trip>(`${t(id)}/places/${placeId}`, { params: { version } })).data;
  },
  async changes(id: number, since: number) {
    return (await api.get<Changes>(`${t(id)}/changes`, { params: { since } })).data;
  },
  async createInvite(id: number, role: 'EDITOR' | 'VIEWER') {
    return (await api.post<Invite>(`${t(id)}/invites`, { role })).data;
  },
  async invitePreview(token: string) {
    return (await api.get<InvitePreview>(`/api/invites/${encodeURIComponent(token)}`)).data;
  },
  async joinInvite(token: string) {
    return (await api.post<Trip | { draftId: string; savedTripId?: number }>(`/api/invites/${encodeURIComponent(token)}/join`)).data;
  },
};
