import { apiError } from './api';
import { draftEditApi, type EditStatus } from './draft-edit';
import { forgetEditToken, rememberEditToken } from './draft-edit-token';

export type EditState = EditStatus & { id: string; ready: boolean; error: string };
export const initialEditState = (id: string): EditState => ({ id, active:false, mine:false, canEdit:false, version:0, ready:false, error:'' });
// Routes in one browser share an id. Finish the previous route's release before acquiring it again.
const releases = new Map<string, Promise<void>>();
export class DraftEditSession {
  private token: string | null = null;
  private active = false;
  private epoch = 0;
  private pending = false;
  private acquiring: Promise<boolean> | null = null;
  private polling = false;
  private renewing = false;
  state: EditState;
  onUnlocked?: () => void;
  setOnUnlocked = (callback?: () => void) => { this.onUnlocked = callback; };
  constructor(private id: string, private notify: (state: EditState) => void, private api = draftEditApi) { this.state = initialEditState(id); }
  private publish(next: EditState) {
    if (JSON.stringify(this.state) === JSON.stringify(next)) return;
    this.state = next; if (this.active) this.notify(next);
  }
  private accept(status: EditStatus) {
    const before = this.state;
    if (this.token && !status.mine) { forgetEditToken(this.id,this.token); this.token = null; }
    this.publish({ ...status, id:this.id, ready:true, error:'' });
    if (before.ready && ((!status.active && before.active) || before.version !== status.version)) this.onUnlocked?.();
  }
  open = () => {
    this.active = true; this.epoch++; this.publish(initialEditState(this.id));
    const previous=releases.get(this.id);
    if(previous)void previous.then(()=>{if(this.active)void this.poll();});else void this.poll();
  };
  close = () => { this.active = false; void this.release(); };
  poll = async () => {
    if (!this.active || this.polling || this.pending || releases.has(this.id)) return;
    this.polling = true; const epoch = this.epoch, token = this.token;
    try { const status = await this.api.status(this.id,token); if (this.active && epoch === this.epoch && token === this.token) this.accept(status); }
    catch (e) { if (this.active && epoch === this.epoch && token === this.token) this.publish({ ...this.state, ready:false, error:apiError(e) }); }
    finally { this.polling = false; }
  };
  begin = async (): Promise<boolean> => {
    if (!this.active) return false;
    if (this.acquiring) { await this.acquiring; return this.begin(); }
    if (this.token && this.state.mine && this.state.ready && !this.state.error
      && (!this.state.expiresAt || Date.parse(this.state.expiresAt) > Date.now()+1000)) return true;
    this.pending = true; const epoch = ++this.epoch;
    const task = (async () => { try {
      await releases.get(this.id);
      if (!this.active || epoch !== this.epoch) return false;
      const grant = await this.api.acquire(this.id,this.token);
      if (!this.active || epoch !== this.epoch) { try { await this.api.release(this.id,grant.token); } catch {} return false; }
      this.token = grant.token; rememberEditToken(this.id,grant.token); this.accept(grant.status); return true;
    } catch (e) {
      if (this.active && epoch === this.epoch) { this.pending=false; await this.poll(); this.publish({ ...this.state, error:apiError(e) }); }
      return false;
    } finally { this.pending = false; } })();
    this.acquiring=task;
    try { return await task; } finally { if(this.acquiring===task)this.acquiring=null; }
  };
  renew = async () => {
    if (!this.active || !this.token || this.renewing) return;
    this.renewing = true; const token=this.token, epoch=this.epoch;
    try { const status=await this.api.renew(this.id,token); if (this.active && epoch===this.epoch && this.token===token) this.accept(status); }
    catch (e) { if (this.active && epoch===this.epoch) this.publish({ ...this.state, ready:false, error:apiError(e) }); }
    finally { this.renewing = false; }
  };
  release = async () => {
    const token=this.token; this.token=null; this.epoch++;
    if (token) forgetEditToken(this.id,token);
    if (this.active) this.publish({ ...this.state, active:false, editorId:null, editorName:null, ready:true, mine:false });
    const acquiring=this.acquiring, previous=releases.get(this.id);
    const task=(async()=>{try {await previous;if(token)await this.api.release(this.id,token);else if(acquiring)await acquiring;}
      catch { /* A disconnected client cannot keep a lease beyond its server expiry. */ }})();
    releases.set(this.id,task);
    await task;
    if(releases.get(this.id)===task)releases.delete(this.id);
    if(this.active)void this.poll();
  };
}
