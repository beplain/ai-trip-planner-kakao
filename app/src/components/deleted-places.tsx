import { AppTextInput as TextInput } from '@/components/app-text-input';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { apiError } from '../../services/api';
import { recoveryApi, type DeletedPlace, type RecoveryKind } from '../../services/recovery';
import type { Trip } from '../../services/trips';
import type { DraftRoom } from '../../services/drafts';
import { C, s } from '@/lib/styles';
import type { UndoNoticeValue } from '@/components/undo-notice';
import { TimeWheelInput } from '@/components/time-wheel-input';

export function DeletedPlaces({ kind, id, version, editable, selectedOption, onRestored, onRefresh, onUndoChange, onBeginEdit, onEndEdit }: {
  kind: RecoveryKind; id: string | number; version: number; editable: boolean; selectedOption?: string | null;
  onRestored: (value: Trip | DraftRoom) => void; onRefresh: () => unknown;
  onUndoChange?: (notice: UndoNoticeValue | null) => void;
  onBeginEdit?: () => Promise<number | null>; onEndEdit?: () => void;
}) {
  const [rows, setRows] = useState<DeletedPlace[]>([]);
  const [open, setOpen] = useState(false);
  const [undo, setUndo] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [times, setTimes] = useState<Record<string,string>>({});
  const seen = useRef<Set<string> | null>(null);
  const lock = useRef(false);
  const request = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const undoAction = useRef<() => void>(() => {});
  const showUndo = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    setUndo(true); timer.current = setTimeout(() => setUndo(false), 10000);
  }, []);
  const load = useCallback(async () => {
    const attempt = ++request.current;
    try {
      const next = await recoveryApi.places(kind,id);
      if (attempt !== request.current) return;
      if (seen.current && next.some(row => !seen.current!.has(row.id))) showUndo();
      seen.current = new Set(next.map(row => row.id)); setRows(next); setError('');
    } catch (e) { if (attempt === request.current) setError(apiError(e)); }
  }, [kind,id,showUndo]);
  const cancelLoad = useCallback(() => { request.current++; }, []);
  useEffect(() => {
    let active = true;
    void Promise.resolve().then(() => { if (active) void load(); });
    return () => { active = false; cancelLoad(); };
  }, [load,version,cancelLoad]);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  async function restore(row: DeletedPlace, originalTime = false) {
    if (lock.current || !editable) return;
    const time = originalTime ? row.place.visitTime : times[row.id] ?? row.place.visitTime;
    if (!time || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) { setError(kind === 'drafts' ? '복원 시각을 선택해 주세요.' : '복원 시각을 14:30처럼 입력해 주세요.'); setOpen(true); return; }
    lock.current = true; setBusy(true); setError('');
    try {
      const editVersion = onBeginEdit ? await onBeginEdit() : version;
      if (editVersion == null) return;
      const restored = await recoveryApi.restorePlace<Trip | DraftRoom>(kind,id,row.id,editVersion,time);
      onRestored(restored); await load(); showUndo();
    } catch (e) { setError(apiError(e)); setOpen(true); void onRefresh(); }
    finally { lock.current = false; setBusy(false); onEndEdit?.(); }
  }
  const eligible = rows.filter(row => kind !== 'drafts' || (row.optionId ?? null) === (selectedOption ?? null));
  const latest = eligible[0];
  useEffect(() => { undoAction.current = () => { if (latest) void restore(latest,true); }; });
  useEffect(() => {
    let active = true;
    void Promise.resolve().then(() => {
      if (active) onUndoChange?.(undo && latest && editable ? { name: latest.place.name, busy, run: () => undoAction.current() } : null);
    });
    return () => { active = false; };
  }, [undo,latest,editable,busy,onUndoChange]);
  return <View style={{ marginTop: 16 }}>
    {!onUndoChange && undo && latest && editable && <View style={s.infoBox}>
      <Text style={s.infoText}>최근 삭제한 장소: {latest.place.name}</Text>
      <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void restore(latest,true); }} style={s.outlineButton}>
        <Text style={s.outlineText}>{busy ? '복원 중…' : '실행 취소'}</Text>
      </Pressable>
    </View>}
    <Pressable accessibilityRole="button" accessibilityState={{ expanded: open }} onPress={() => setOpen(value => !value)} style={s.outlineButton}>
      <Text style={s.outlineText}>삭제한 장소 ({rows.length}) {open ? '▴' : '▾'}</Text>
    </Pressable>
    {!!error && <View accessibilityRole="alert"><Text style={s.warnText}>{error}</Text>
      <Pressable onPress={() => { void load(); }}><Text style={s.outlineText}>삭제 목록 다시 불러오기</Text></Pressable></View>}
    {open && <>
      <Text style={s.mutedSmall}>삭제 후 30일 동안 복원할 수 있어요. 시간이 겹치면 복원 시각을 바꿔 주세요.</Text>
      {rows.length === 0 && <Text style={s.empty}>삭제한 장소가 없습니다.</Text>}
      {rows.map(row => <View key={row.id} style={[s.memberBox, { marginTop: 12 }]}>
        <Text style={s.sectionTitleSmall}>{row.place.name}</Text>
        <Text style={s.mutedSmall}>DAY {row.dayNumber} · 원래 시각 {row.place.visitTime} · 삭제 {new Date(row.deletedAt).toLocaleDateString('ko-KR')}</Text>
        {kind === 'drafts' && row.optionId != null && row.optionId !== selectedOption
          ? <Text style={{ color: C.warnText }}>삭제했던 초안 유형으로 전환한 후 복원해 주세요.</Text>
          : <>
            {kind === 'drafts'
              ? <TimeWheelInput label={`${row.place.name} 복원 시각`} value={times[row.id] ?? row.place.visitTime ?? ''} minuteInterval={1}
                  onChange={time => setTimes(values => ({ ...values, [row.id]: time }))} disabled={!editable || busy} />
              : <TextInput accessibilityLabel={`${row.place.name} 복원 시각`} value={times[row.id] ?? row.place.visitTime ?? ''}
                  onChangeText={text => setTimes(values => ({ ...values, [row.id]: text }))} placeholder="HH:mm" maxLength={5} style={s.input} editable={editable && !busy} />}
            {editable && <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void restore(row); }} style={s.outlineButton}><Text style={s.outlineText}>이 장소 복원</Text></Pressable>}
          </>}
      </View>)}
    </>}
  </View>;
}
