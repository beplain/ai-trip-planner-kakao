export type MapLoadState = { attempt: number; status: 'loading' | 'slow' | 'error' | 'ready'; message: string | null };
export type MapLoadAction = { type: 'retry' } | { type: 'ready' | 'timeout' | 'error'; attempt: number; message?: string };
export const initialMapLoadState: MapLoadState = { attempt: 0, status: 'loading', message: null };
export function mapLoadState(state: MapLoadState, action: MapLoadAction): MapLoadState {
  if (action.type === 'retry') return { attempt: state.attempt + 1, status: 'loading', message: null };
  if (action.attempt !== state.attempt) return state;
  if (action.type === 'ready') return { ...state, status: 'ready', message: null };
  if (action.type === 'timeout') {
    if (state.status !== 'loading') return state;
    return { ...state, status: 'slow', message: '지도를 불러오는 데 시간이 걸리고 있어요. 잠시 기다리거나 다시 불러와 주세요.' };
  }
  return { ...state, status: 'error', message: action.message ?? '지도를 불러오지 못했어요. 다시 불러와 주세요.' };
}
