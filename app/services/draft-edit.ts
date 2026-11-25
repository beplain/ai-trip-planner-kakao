import { api } from './api';
import { EDIT_HEADER } from './draft-edit-token';
export type EditStatus = { active: boolean; editorId?: number | null; editorName?: string | null; expiresAt?: string | null; mine: boolean; canEdit: boolean; version: number };
const path = (id: string) => id.startsWith('trip:') ? `/api/trips/${encodeURIComponent(id.slice(5))}/edit-lock` : `/api/drafts/${encodeURIComponent(id)}/edit-lock`;
const config = (token: string | null) => ({ headers: { [EDIT_HEADER]: token ?? '' }, timeout: 8000 });
export const draftEditApi = {
  async status(id: string, token: string | null) { return (await api.get<EditStatus>(path(id),config(token))).data; },
  async acquire(id: string, token: string | null) { return (await api.post<{token:string;status:EditStatus}>(path(id),{},config(token))).data; },
  async renew(id: string, token: string) { return (await api.put<EditStatus>(path(id),{},config(token))).data; },
  async release(id: string, token: string) { await api.delete(path(id),config(token)); },
};
