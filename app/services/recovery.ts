import { api } from './api';
import type { Place } from './trips';
import { draftEditHeaders } from './draft-edit-token';

export type RecoveryKind = 'trips' | 'drafts';
export type TrashItem = { id: string; title: string; destination: string; deletedAt: string; expiresAt: string };
export type DeletedPlace = { id: string; deletedAt: string; dayNumber: number; optionId?: string | null; context?: string | null; place: Place };
const base = (kind: RecoveryKind, id: string | number) => `/api/${kind}/${encodeURIComponent(id)}`;
export const recoveryApi = {
  async trash(kind: RecoveryKind) { return (await api.get<TrashItem[]>(`/api/${kind}/trash`)).data; },
  async restore(kind: RecoveryKind, id: string) { await api.post(`${base(kind,id)}/restore`); },
  async permanent(kind: RecoveryKind, id: string) { await api.delete(`${base(kind,id)}/permanent`); },
  async places(kind: RecoveryKind, id: string | number) { return (await api.get<DeletedPlace[]>(`${base(kind,id)}/deleted-places`)).data; },
  async restorePlace<T>(kind: RecoveryKind, id: string | number, entry: string, version: number, visitTime?: string) {
    return (await api.post<T>(`${base(kind,id)}/deleted-places/${encodeURIComponent(entry)}/restore`, { version, visitTime }, { headers: kind === 'drafts' ? draftEditHeaders(String(id)) : {} })).data;
  },
};
