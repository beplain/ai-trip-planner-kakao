import { draftEditHeaders } from './draft-edit-token';
import { api } from './api';
import type { DraftOptions, GenerateInput, Invite, Member, Role, Trip, TripDraft } from './trips';

export type DraftRoom = {
  id: string; version: number; draft: TripDraft; members: Member[]; myRole: Role;
  savedTripId?: number; expiresAt?: string;
};
export type DraftSummary = { id: string; title: string; destination: string; companionCount: number };

const room = (id: string) => `/api/drafts/${encodeURIComponent(id)}`;
export const draftsApi = {
  async switchOption(id: string, optionId: string, version: number) {
    return (await api.put<DraftRoom>(`${room(id)}/option`, { optionId, version }, { headers: draftEditHeaders(id) })).data;
  },
  async restoreGenerated(id: string, input: GenerateInput, title: string, version: number) {
    const response = await api.post<DraftRoom>(`${room(id)}/restore-generated`, { input, title, version }, { headers: draftEditHeaders(id) });
    return response.status === 204 ? null : response.data;
  },
  async regenerateOptions(id: string, input: GenerateInput, title: string, version: number) {
    return (await api.post<DraftOptions>(`${room(id)}/regenerate-options`, { input, title, version }, { timeout: 300000, headers: draftEditHeaders(id) })).data;
  },
  async selectRegenerated(id: string, draft: TripDraft, version: number) {
    return (await api.post<DraftRoom>(`${room(id)}/regenerate-selection`, { draft, version }, { headers: draftEditHeaders(id) })).data;
  },
  async create(draft: TripDraft) { return (await api.post<DraftRoom>('/api/drafts', draft)).data; },
  async listSummaries() { return (await api.get<DraftSummary[]>('/api/drafts/summaries')).data; },
  async get(id: string) { return (await api.get<DraftRoom>(room(id))).data; },
  async delete(id: string, version: number) { await api.delete(room(id), { params: { version }, headers: draftEditHeaders(id) }); },
  async rename(id: string, title: string, version: number) {
    return (await api.put<DraftRoom>(`${room(id)}/title`, { title, version }, { headers: draftEditHeaders(id) })).data;
  },
  async update(id: string, draft: TripDraft, version: number) { return (await api.put<DraftRoom>(room(id), { draft, version }, { headers: draftEditHeaders(id) })).data; },
  async regenerate(id: string, input: GenerateInput, title: string, version: number) {
    return (await api.post<DraftRoom>(`${room(id)}/regenerate`, { input, title, version }, { timeout: 300000, headers: draftEditHeaders(id) })).data;
  },
  async save(id: string, version: number) { return (await api.post<Trip>(`${room(id)}/save`, { version }, { timeout: 45000, headers: draftEditHeaders(id) })).data; },
  async invite(id: string, role: 'EDITOR' | 'VIEWER') { return (await api.post<Invite>(`${room(id)}/invites`, { role })).data; },
};
