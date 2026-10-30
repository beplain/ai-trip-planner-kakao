import { api } from './api';
import type { DraftOptions, GenerateInput, Invite, Member, Role, Trip, TripDraft } from './trips';

export type DraftRoom = {
  id: string; version: number; draft: TripDraft; members: Member[]; myRole: Role;
  savedTripId?: number; expiresAt?: string;
};
const room = (id: string) => `/api/drafts/${encodeURIComponent(id)}`;
export const draftsApi = {
  async switchOption(id: string, optionId: string, version: number) {
    return (await api.put<DraftRoom>(`${room(id)}/option`, { optionId, version })).data;
  },
  async regenerateOptions(id: string, input: GenerateInput, title: string, version: number) {
    return (await api.post<DraftOptions>(`${room(id)}/regenerate-options`, { input, title, version }, { timeout: 300000 })).data;
  },
  async selectRegenerated(id: string, draft: TripDraft, version: number) {
    return (await api.post<DraftRoom>(`${room(id)}/regenerate-selection`, { draft, version })).data;
  },
  async create(draft: TripDraft) { return (await api.post<DraftRoom>('/api/drafts', draft)).data; },
  async list() { return (await api.get<DraftRoom[]>('/api/drafts')).data; },
  async get(id: string) { return (await api.get<DraftRoom>(room(id))).data; },
  async delete(id: string, version: number) { await api.delete(room(id), { params: { version } }); },
  async rename(id: string, title: string, version: number) {
    return (await api.put<DraftRoom>(`${room(id)}/title`, { title, version })).data;
  },
  async update(id: string, draft: TripDraft, version: number) { return (await api.put<DraftRoom>(room(id), { draft, version })).data; },
  async regenerate(id: string, input: GenerateInput, title: string, version: number) {
    return (await api.post<DraftRoom>(`${room(id)}/regenerate`, { input, title, version }, { timeout: 300000 })).data;
  },
  async save(id: string, version: number) { return (await api.post<Trip>(`${room(id)}/save`, { version }, { timeout: 45000 })).data; },
  async invite(id: string, role: 'EDITOR' | 'VIEWER') { return (await api.post<Invite>(`${room(id)}/invites`, { role })).data; },
};
