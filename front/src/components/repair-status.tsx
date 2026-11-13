import { useContext, useEffect, useRef, useState, type ComponentType } from 'react'
import { DetailMoreContext } from '@/components/detail-more'
import { Camera, Check, Clock, RotateCcw, Wrench } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Choice, Status } from '@/components/prototype'
import { ActionDialog, tryOn } from '@/components/action-dialog'
import type { Repair, Role, Room } from '@/data/demo'
import { canRecur, confirmClose, confirmDefect, confirmSoloClosed, disputeDefect, hasAfterPhoto, notYet, reopen, requestClose, roomSoloReason, soloClose, soloReasonFor, soloReasonText, startRepair } from '@/lib/repair-rules'
import { versionOf } from '@/lib/versions'
import { sideText } from '@/lib/words'

type OnAction = (text: string, update: (r: Repair) => void) => void
const waitText = (stage?: string) => stage === '재통지' ? '재통지됨' : stage === '미확인' ? '상대 미확인' : ''

// 수리 상태·하자 인정 표시(상태와 규칙 D). 비회원 링크 화면에서도 같은 표시를 씁니다.
// compact: 상세 화면 제목 옆에 수리 상태·하자 인정·수리 시작 표시가 이미 있어 겹치는 배지를 뺍니다.
export function RepairBadges({ r, room, repairs, onOpenRepair, compact }: { r: Repair; room: Room; repairs: Repair[]; onOpenRepair?: (id: string) => void; compact?: boolean }) {
  const solo = roomSoloReason(room)
  const prev = r.recurrenceOf ? repairs.find(x => x.id === r.recurrenceOf) : undefined
  const later = repairs.filter(x => x.recurrenceOf === r.id)
  return <div className={compact ? "space-y-3" : "mb-5 space-y-3"}>
    <div className="badges">
      {r.status === '처리 완료' && <Status>{r.soloClosed ? `처리 완료(${r.soloClosed} 확인)` : '처리 완료(양측 확인)'}</Status>}
      {(!compact || r.ackDispute) && <Status>하자 인정 · {r.ack}{r.ackDispute ? ` · ${r.ackDispute.type}` : ''}</Status>}
      {r.waits?.ack === '재통지' && <Status>하자 확인 재통지됨</Status>}
      {!compact && r.startFlag && <Status>{r.startFlag}</Status>}
      {r.closeBy && <Status>종료 확인 대기 · {r.closeBy} 요청{r.waits?.close ? ` · ${waitText(r.waits.close)}` : ''}</Status>}
      {r.soloReason && <Status>{r.soloReason === '상대 없음' ? '혼자 남긴 기록' : r.soloReason}</Status>}
      {r.confirmRequested && <Status>{`종료 확인 요청됨 · 상대 연결 뒤${r.waits?.soloclose ? ` · ${waitText(r.waits.soloclose)}` : ''}`}</Status>}
      {!r.soloReason && solo && r.status !== '처리 완료' && <Status>{solo === '상대 없음' ? '혼자 남긴 기록(상대 확인 없음)' : solo}</Status>}
    </div>
    {r.ackDispute && <div className="dispute-callout"><strong>{sideText(r.ackDispute.by)} 하자 이견 · {r.ackDispute.type}</strong><small className="block">{r.ackDispute.at}</small><p>{r.ackDispute.reason}</p></div>}
    {prev && <p className="muted">이전 수리 건 이후 재발 · {onOpenRepair ? <Button variant="link" className="h-auto p-0" onClick={() => onOpenRepair(prev.id)}>{prev.title} ({prev.date})</Button> : `${prev.title} (${prev.date})`}</p>}
    {later.length > 0 && <p className="muted">이 건 이후 재발 · {later.map(x => onOpenRepair ? <Button key={x.id} variant="link" className="h-auto p-0 mr-2" onClick={() => onOpenRepair(x.id)}>{x.title} ({x.date})</Button> : <span key={x.id}>{x.title} </span>)}</p>}
  </div>
}

interface Act { key: string; label: string; icon?: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>; outline?: boolean; onClick: () => void }
interface ActionsProps { r: Repair; room: Room; role: Role; onAction: OnAction; onOpen: (m: 'note' | 'reconfirm' | 'after') => void; onRecur: (r: Repair) => void }
export function RepairActions({ r, room, role, onAction, onOpen, onRecur }: ActionsProps) {
  const [mode, setMode] = useState<'dispute' | 'notyet' | 'reopen' | 'close-ack' | null>(null)
  const [disputeType, setDisputeType] = useState<'하자 없음' | '설명과 다름'>('하자 없음')
  const [closeAck, setCloseAck] = useState('네, 하자도 확인해요')
  const [error, setError] = useState('')
  const run = (text: string, update: (x: Repair) => void) => { const e = tryOn(r, update); if (e) { setError(e); return e } setError(''); onAction(text, update) }
  const solo = roomSoloReason(room)
  const closeSolo = soloReasonFor(room, r, 'close')
  const bothClosed = r.status === '처리 완료' && !r.soloClosed
  const ackPending = ['확인 대기', '상대 미확인'].includes(r.ack)
  const canAck = r.author !== role && r.ack !== '확인됨' && !bothClosed
  const counterpartMember = room.connection === '연결됨' && !room.counterpartLeft
  const reasons = [solo, r.waits?.ack === '미확인' ? '상대 미확인' : undefined, r.waits?.close === '미확인' ? '상대 미확인' : undefined].filter(Boolean)
  // 지금 누를 수 있는 행동(노출 조건·순서는 그대로). 수리 상세에서는 앞의 두 개만 크게, 나머지는 '더 보기' 메뉴로 보내요.
  const acts: Act[] = []
  if (canAck) acts.push({ key: 'ack', label: '하자 사실 확인', icon: Check, onClick: () => run(`하자 사실 확인 · 본 모습 ${versionOf(r)}번`, x => confirmDefect(x, role, versionOf(r))) })
  if (canAck && r.ack !== '이견') acts.push({ key: 'dispute', label: '사실에 이견', outline: true, onClick: () => setMode('dispute') })
  if (['확인 전', '확인 완료'].includes(r.status)) acts.push({ key: 'start', label: '수리 시작', icon: Wrench, onClick: () => run(`수리 시작${r.ack === '이견' ? ' · 이견 유지 중 수리 시작' : r.ack !== '확인됨' ? ' · 상대 확인 전 수리 시작' : ''}`, startRepair) })
  if (r.status === '처리 중' && !r.closeBy) acts.push(solo
    ? { key: 'solo-close', label: `처리 완료로 남기기 · ${solo}`, onClick: () => run(`수리 혼자 종료 · 처리 완료(${role} 확인) · ${solo}`, x => soloClose(x, role, solo)) }
    : { key: 'request-close', label: '종료 확인 요청', onClick: () => run('수리 종료 확인 요청', x => requestClose(x, role)) })
  if (r.closeBy && r.closeBy !== role) acts.push(
    { key: 'confirm-close', label: '수리 종료 확인', onClick: () => { if (r.author !== role && ackPending) { setCloseAck('네, 하자도 확인해요'); setMode('close-ack') } else run('수리 종료 · 양측 확인', x => confirmClose(x, role)) } },
    { key: 'notyet', label: '아직 안 됐어요', outline: true, onClick: () => setMode('notyet') })
  if (r.closeBy === role && closeSolo === '상대 미확인') acts.push({ key: 'unconfirmed-close', label: '미확인 뒤 혼자 종료', onClick: () => run(`미확인 뒤 혼자 종료 · 처리 완료(${role} 확인) · 상대 미확인`, x => soloClose(x, role, '상대 미확인')) })
  if (r.status === '처리 완료' && r.soloClosed && r.soloClosed !== role && counterpartMember) acts.push(
    { key: 'confirm-solo', label: '종료 확인 · 양측 확인', onClick: () => run('혼자 종료한 건 종료 확인 · 양측 확인', x => confirmSoloClosed(x, role)) },
    { key: 'reopen', label: '다시 열기', icon: RotateCcw, outline: true, onClick: () => setMode('reopen') })
  if (canRecur(r)) acts.push({ key: 'recur', label: '같은 곳 재발 · 새 수리 건 등록', outline: true, onClick: () => onRecur(r) })
  if (r.status === '처리 중') acts.push({ key: 'after', label: '수리 후 사진 남기기', icon: Camera, outline: true, onClick: () => onOpen('after') })
  if (r.author === role && r.ack === '이견') acts.push({ key: 'reconfirm', label: '설명·사진 보충 후 다시 확인 요청', outline: true, onClick: () => onOpen('reconfirm') })
  const register = useContext(DetailMoreContext)
  const big = register ? acts.slice(0, 2) : acts
  const rest = register ? acts.slice(2) : []
  // 메뉴에서 누를 때는 그때의 최신 행동을 불러요(오래된 화면 값으로 실행되지 않게)
  const latest = useRef(acts)
  const restKey = rest.map(a => `${a.key}:${a.label}`).join('|')
  useEffect(() => { latest.current = acts })
  useEffect(() => {
    register?.(rest.map(a => ({ key: a.key, label: a.label, icon: a.icon, onSelect: () => latest.current.find(x => x.key === a.key)?.onClick() })))
  }, [register, restKey]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => register?.([]), [register])
  return <div className="repair-actions space-y-3 mt-5">
    {reasons.length > 0 && <div className="info-box"><div><strong>혼자 진행할 수 있어요 · {[...new Set(reasons)].join(', ')}</strong>{[...new Set(reasons)].map(x => <p key={x}>{soloReasonText[x as keyof typeof soloReasonText]}</p>)}<p>사실(수리 진행, 종료, 지불 기록)은 혼자 남길 수 있지만, 돈 상태는 혼자 합의됨이 되지 않아요.</p></div></div>}
    {big.length > 0 && <div className="actions">
      {big.map(a => <Button key={a.key} variant={a.outline ? 'outline' : 'default'} onClick={a.onClick}>{a.icon && <a.icon />}{a.label}</Button>)}
    </div>}
    {r.status === '처리 중' && !r.closeBy && !hasAfterPhoto(r) && <p className="muted"><Camera size={14} className="inline mr-1" />종료하려면 수리 후 사진이 1장 이상 필요해요. [수리 후 사진 남기기]로 먼저 올려 주세요.</p>}
    {r.closeBy === role && <p className="muted"><Clock size={14} className="inline mr-1" />상대 쪽의 종료 확인을 기다리고 있어요{r.waits?.close ? ` · ${waitText(r.waits.close)}` : ''}.</p>}
    {error && <p role="alert" className="form-error">{error}</p>}

    <ActionDialog open={mode === 'dispute'} title="하자 사실에 이견" onClose={() => setMode(null)} onSubmit={f => run(`하자 이견 · ${disputeType}`, x => disputeDefect(x, role, disputeType, String(f.get('reason') || '')))}>
      <div className="field"><label>이견 종류</label><Choice label="이견 종류" value={disputeType} values={['하자 없음', '설명과 다름']} onChange={v => setDisputeType(v as typeof disputeType)} /></div>
      <div className="field"><label htmlFor="ack-reason">사유 · 필수</label><Textarea id="ack-reason" name="reason" required placeholder="예: 방문해 보니 물 자국이 없어요" /></div>
      <p className="muted">이견이 있어도 누구든 수리를 시작할 수 있어요.</p>
    </ActionDialog>
    <ActionDialog open={mode === 'notyet'} title="아직 안 됐어요" onClose={() => setMode(null)} onSubmit={f => run(`아직 안 됐어요 · ${String(f.get('reason') || '')}`, x => notYet(x, role, String(f.get('reason') || '')))}>
      <div className="field"><label htmlFor="notyet-reason">사유 · 필수</label><Textarea id="notyet-reason" name="reason" required /></div>
    </ActionDialog>
    <ActionDialog open={mode === 'reopen'} title="다시 열기" description="상대가 혼자 종료한 건을 사유와 함께 다시 엽니다. 처리 중으로 돌아가고 종료 확인을 다시 받아야 해요." onClose={() => setMode(null)} onSubmit={f => run(`다시 열기 · ${String(f.get('reason') || '')}`, x => reopen(x, role, String(f.get('reason') || '')))}>
      <div className="field"><label htmlFor="reopen-reason">사유 · 필수</label><Textarea id="reopen-reason" name="reason" required /></div>
    </ActionDialog>
    <ActionDialog open={mode === 'close-ack'} title="수리 종료 확인" description="끝난 뒤에는 수리 건이 잠기므로, 아직 비어 있는 하자 인정도 함께 물어요." submitLabel="종료 확인" onClose={() => setMode(null)} onSubmit={f => {
      const agree = closeAck.startsWith('네')
      const reason = String(f.get('reason') || '')
      return run(`수리 종료 · 양측 확인 · 하자 ${agree ? '확인' : `이견(${disputeType})`}`, x => { if (agree) confirmDefect(x, role, versionOf(r)); else disputeDefect(x, role, disputeType, reason); confirmClose(x, role) })
    }}>
      <div className="field"><label>이 하자가 있었다는 것도 확인할까요?</label><Choice label="하자 인정" value={closeAck} values={['네, 하자도 확인해요', '아니요, 이견 있어요']} onChange={setCloseAck} /></div>
      {closeAck.startsWith('아니요') && <><div className="field"><label>이견 종류</label><Choice label="이견 종류" value={disputeType} values={['하자 없음', '설명과 다름']} onChange={v => setDisputeType(v as typeof disputeType)} /></div><div className="field"><label htmlFor="close-ack-reason">사유 · 필수</label><Textarea id="close-ack-reason" name="reason" required /></div></>}
    </ActionDialog>
  </div>
}
