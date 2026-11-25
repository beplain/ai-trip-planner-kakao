import { useEffect, useMemo, useState, useCallback } from 'react';
import { useFocusEffect } from 'expo-router';
import { DraftEditSession, initialEditState } from '../../services/draft-edit-session';

export function useDraftEdit(id: string, onUnlocked?: () => void, autoAcquire = false) {
  const [state,setState] = useState(() => initialEditState(id));
  const session = useMemo(() => new DraftEditSession(id,setState),[id]);
  useEffect(() => { session.setOnUnlocked(onUnlocked); },[session,onUnlocked]);
  useFocusEffect(useCallback(() => {
    session.open();
    if(autoAcquire)void session.begin();
    const poll=setInterval(() => { void session.poll(); },2000);
    const heartbeat=setInterval(() => { void session.renew(); },15000);
    return () => { clearInterval(poll); clearInterval(heartbeat); session.close(); };
  },[session,autoAcquire]));
  const current=state.id===id?state:initialEditState(id);
  const blocked=!current.ready || !!current.error || !current.canEdit || (current.active&&!current.mine);
  const message=current.error || (!current.ready?'편집 상태를 확인하고 있어요.':current.active&&!current.mine?
    `${current.editorName || '동행자'}님이 일정을 수정 중입니다. 완료될 때까지 일정 확인만 가능합니다.`:current.mine?'내가 일정을 수정 중입니다. 다른 동행자는 일정을 확인할 수 있어요.':'');
  return { ...current, blocked, message, begin:session.begin, release:session.release, refresh:session.poll, getStatus:()=>session.state };
}
