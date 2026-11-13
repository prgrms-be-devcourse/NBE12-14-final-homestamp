import { useState, type ReactNode } from 'react'
import { EyeOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/textarea'
import { ActionDialog } from '@/components/action-dialog'
import type { PhotoMask, Role } from '@/data/demo'
import { maskLabel } from '@/lib/photo-privacy'
import { sideText } from '@/lib/words'

export interface ManagedPhoto { key: string; src: string; label: string; wrong?: boolean; canWrong?: boolean; masked?: boolean; note?: string; extra?: ReactNode }

// 사진 관리: 체크박스로 여러 장을 골라 [잘못 올린 사진 표시 / 표시 떼기]·[개인정보로 가리기]를 한 번에 해요.
// 가린 사진은 되돌릴 수 없어서 고를 수 없고, 가리기 전에는 한 번 더 물어요.
export function PhotoManageList({ items, onWrong, onMask }: {
  items: ManagedPhoto[]
  onWrong: (keys: string[], mark: boolean) => string | void
  onMask: (keys: string[]) => string | void }) {
  const [picked, setPicked] = useState<string[]>([])
  const [confirm, setConfirm] = useState(false)
  const [error, setError] = useState('')
  const sel = items.filter(it => picked.includes(it.key) && !it.masked)
  const wrongable = sel.filter(it => it.canWrong)
  // 고른 사진 중 표시 안 된 게 하나라도 있으면 '표시', 모두 표시돼 있으면 '표시 떼기'
  const mark = wrongable.some(it => !it.wrong)
  const selectable = items.filter(it => !it.masked)
  const all = selectable.length > 0 && selectable.every(it => picked.includes(it.key))
  const done = (e: string | void) => { if (e) { setError(e); return e } setError(''); setPicked([]) }
  const toggle = (key: string, on: boolean) => setPicked(p => on ? [...p, key] : p.filter(k => k !== key))
  return <div className="photo-manage">
    <div className="photo-manage-bar">
      <label className="photo-manage-all"><Checkbox checked={all} disabled={!selectable.length} onCheckedChange={v => setPicked(v ? selectable.map(it => it.key) : [])} />전체 선택</label>
      <span className="muted text-sm">{sel.length ? `${sel.length}장 선택` : '사진을 골라 주세요'}</span>
      <div className="actions">
        <Button size="sm" variant="outline" disabled={!wrongable.length} onClick={() => done(onWrong(wrongable.filter(it => Boolean(it.wrong) !== mark).map(it => it.key), mark))}>{mark || !wrongable.length ? '잘못 올린 사진 표시' : '표시 떼기'}</Button>
        <Button size="sm" variant="outline" disabled={!sel.length} onClick={() => setConfirm(true)}><EyeOff />개인정보로 가리기</Button>
      </div>
    </div>
    {/* 갤러리처럼 사진을 바둑판으로: 사진을 눌러 고르고, 아래에 '사진 1' 같은 이름과 표시를 붙여요 */}
    <div className="photo-manage-list photo-pick-grid">{[...items.filter(it => !it.masked), ...items.filter(it => it.masked)].map(it => <div className={`photo-pick${picked.includes(it.key) ? ' is-picked' : ''}${it.masked ? ' is-locked' : ''}`} key={it.key}>
      <label className="photo-pick-tile">
        <span className={`photo-pick-img${it.masked ? ' photo-masked' : ''}`}>
          <img src={it.src} alt={it.masked ? '' : it.label} className={it.wrong && !it.masked ? 'opacity-40' : undefined} />
          {it.masked && <span className="photo-masked-label"><EyeOff size={14} aria-hidden />가림</span>}
        </span>
        <span className="photo-pick-check"><Checkbox checked={picked.includes(it.key)} disabled={it.masked} onCheckedChange={v => toggle(it.key, Boolean(v))} aria-label={`${it.label} 선택`} /></span>
      </label>
      <strong className="photo-pick-name">{it.label}</strong>
      {(it.masked || it.wrong || it.note) && <span className="photo-pick-tags">{it.masked && <span>개인정보 이유로 가림</span>}{it.wrong && <span>잘못 올린 사진</span>}{it.note && <span>{it.note}</span>}</span>}
      {it.extra && <div className="photo-pick-extra">{it.extra}</div>}
    </div>)}</div>
    {error && <p role="alert" className="form-error">{error}</p>}
    <ActionDialog open={confirm} title={`사진 ${sel.length}장을 개인정보 이유로 가릴까요?`} description="사진은 지우지 않고 흐리게 가려요. 사진 자리와 ‘개인정보 이유로 가림’ 표시·가린 시각이 남고, 상대에게 알림이 가요." submitLabel={`${sel.length}장 가리기`} onClose={() => setConfirm(false)} onSubmit={() => done(onMask(sel.map(it => it.key)))}>
      <p className="muted text-sm">{sel.map(it => it.label).join(', ')}</p>
      <p className="fix-notice">가린 사진은 다시 보이게 할 수 없어요. 상대는 가린 데에 이견을 남길 수 있어요.</p>
    </ActionDialog>
  </div>
}

// 가린 사진 목록: 누가 언제 가렸는지와 상대의 이견을 나란히 보여 주고, 상대는 [이견 남기기]를 할 수 있어요.
export function MaskedPhotoNotes({ items, role, readOnly, onObject }: {
  items: { mask: PhotoMask; src?: string; label: string }[]; role: Role; readOnly?: boolean
  onObject: (key: string, reason: string) => string | void }) {
  const [target, setTarget] = useState<string | null>(null)
  if (!items.length) return null
  const picked = items.find(it => it.mask.key === target)
  return <div className="masked-notes">
    {items.map(({ mask, src, label }) => <div className="masked-note" key={mask.key}>
      {src && <span className="photo-masked masked-note-thumb" aria-hidden><img src={src} alt="" /></span>}
      <div className="min-w-0">
        <strong><EyeOff size={14} aria-hidden />{label} · 개인정보 이유로 가림</strong>
        <small className="muted block">{sideText(mask.by)}이 가림 · {mask.at}</small>
        {mask.objections?.map((o, n) => <div className="dispute-callout mt-2" key={n}>{sideText(o.by)} 이견 · {o.at}<br />{o.reason}</div>)}
        {!readOnly && mask.by !== role && <Button size="sm" variant="outline" className="mt-2" onClick={() => setTarget(mask.key)}>이견 남기기</Button>}
      </div>
    </div>)}
    <ActionDialog open={Boolean(picked)} title="가린 사진에 이견 남기기" description={picked ? `${picked.label} · ${maskLabel(picked.mask)}. 가린 사진은 다시 보이게 할 수 없지만, 이견은 가린 기록 옆에 나란히 남아요.` : ''} submitLabel="이견 남기기" onClose={() => setTarget(null)} onSubmit={f => picked ? onObject(picked.mask.key, String(f.get('reason') || '')) : undefined}>
      <div className="field"><label htmlFor="mask-objection">이견 사유 · 필수</label><Textarea id="mask-objection" name="reason" required placeholder="예: 가린 사진에 하자 부위가 찍혀 있었어요. 개인정보만 가린 사진을 다시 올려 주세요." /></div>
    </ActionDialog>
  </div>
}
