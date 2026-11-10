import { api } from './api';

// 백엔드 TripDtos.java 와 같은 형태. (서버는 null 필드를 생략하므로 대부분 optional)

export type LuggagePlan = 'HOTEL' | 'LOCKER' | 'CARRY';
export type Stay = {
  placeId?: string; latitude?: number; longitude?: number; placeUrl?: string;
  name: string; address: string; checkInDay: number; checkOutDay: number;
  checkInTime: string; checkOutTime: string; beforeCheckIn: LuggagePlan; afterCheckOut: LuggagePlan;
};
export type SelectedPlace = { placeId: string; name: string; address: string; latitude: number; longitude: number; placeUrl: string };
export type TrainOption = { id: string; date: string; trainNo: string; trainType: string; originStation: string; arrivalStation: string; departureTime: string; arrivalTime: string };
export type TravelDetails = {
  movementMode: 'CAR' | 'PUBLIC_TRANSIT' | 'WALK'; stays: Stay[];
  transport?: { alightingStation?: SelectedPlace; returnAlightingStation?: SelectedPlace; boardingStation?: SelectedPlace; returnBoardingStation?: SelectedPlace; returnArrivalAuto?: boolean; returnArrivalTime?: string; returnArrivalLocation?: SelectedPlace; returnTrain?: TrainOption; train?: TrainOption; originLocation?: SelectedPlace; originTime?: string; arrivalAuto?: boolean; arrivalLocation?: SelectedPlace; departureLocation?: SelectedPlace; mode: 'KTX' | 'PLANE' | 'BUS' | 'CAR'; arrivalPlace: string; arrivalTime: string; departurePlace: string; departureTime: string };
};

export type Role = 'OWNER' | 'EDITOR' | 'VIEWER';

export type Place = {
  businessHours?: { hoursText?: string; closedText?: string; status?: string; detail?: string; contentId?: string; checkedAt?: string; visitDate?: string; checkedVisitTime?: string; basis?: string };
  categoryPath?: string;
  fixedEventId?: string;
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
  travelDetails?: TravelDetails;
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

export type StoredDraftOption = { id: string; label: string; description?: string; days: Day[]; warnings?: string[] };

export type TripDraft = {
  options?: StoredDraftOption[];
  selectedOption?: string;
  travelDetails?: TravelDetails;
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

export type DraftOption = { id: string; label: string; description: string; draft: TripDraft };
export type DraftOptions = { options: DraftOption[]; warnings: string[] };

export function combineDraftOptions(result: DraftOptions, title?: string): TripDraft {
  const first = result.options[0];
  if (!first) throw new Error('생성된 초안이 없습니다. 다시 시도해 주세요.');
  const warnings = [...(first.draft.warnings ?? []), ...result.warnings];
  return { ...first.draft, title: title?.trim() || first.draft.title, selectedOption: first.id, warnings,
    options: result.options.map((option, index) => ({ id: option.id, label: option.label, description: option.description,
      days: option.draft.days, warnings: index === 0 ? warnings : [...(option.draft.warnings ?? []), ...result.warnings] })) };
}

export type GenerateInput = {
  travelDetails?: TravelDetails;
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

export type RequestCheck = { request: string; status: 'APPLIED' | 'UNMET' | 'UNVERIFIABLE'; detail: string };

export const tripsApi = {
  async stationCandidates(origin: SelectedPlace, destination?: SelectedPlace) {
    return (await api.post<SelectedPlace[]>('/api/trips/transport/station-candidates', { origin, destination }, { timeout: 15000 })).data;
  },
  async requestChecks(draft: TripDraft) {
    return (await api.post<RequestCheck[]>('/api/trips/requests/check', draft)).data;
  },
  async recommendTrainStation(transport: NonNullable<TravelDetails['transport']>, date: string) {
    return (await api.post<{ station: SelectedPlace; trains: TrainOption[]; distanceMeters: number }>('/api/trips/transport/station-recommendation', { transport, date }, { timeout: 45000 })).data;
  },
  async trainOptions(transport: NonNullable<TravelDetails['transport']>, date: string) {
    return (await api.post<TrainOption[]>('/api/trips/transport/trains', { transport, date }, { timeout: 45000 })).data;
  },
  async transportArrival(input: NonNullable<TravelDetails['transport']>) {
    return (await api.post<NonNullable<TravelDetails['transport']>>('/api/trips/transport/arrival', input)).data;
  },
  async generateOptions(input: GenerateInput) {
    return (await api.post<DraftOptions>('/api/trips/generate-options', input, { timeout: 300000 })).data;
  },
  /** AI 초안 생성 (AI + 장소 검증으로 20~60초 걸릴 수 있음) */
  async generate(input: GenerateInput) {
    return (await api.post<TripDraft>('/api/trips/generate', input, { timeout: 300000 })).data;
  },
  async create(draft: TripDraft) {
    return (await api.post<Trip>('/api/trips', draft, { timeout: 45000 })).data;
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
