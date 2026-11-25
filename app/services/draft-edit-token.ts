export const EDIT_HEADER = 'X-Draft-Edit-Token';
// Memory only: separate tabs must acquire separate leases, even for the same account.
const tokens = new Map<string,string>();
export function rememberEditToken(id: string, token: string) { tokens.set(id,token); }
export function forgetEditToken(id: string, expected: string) { if (tokens.get(id) === expected) tokens.delete(id); }
export function draftEditHeaders(id: string): Record<string,string> {
  const token = tokens.get(id); return token ? { [EDIT_HEADER]: token } : {};
}
