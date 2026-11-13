import { useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Choice, Status } from '@/components/prototype'
import { ActionDialog } from '@/components/action-dialog'
import { CorrectionHistory, CorrectionPanel, CorrectionRow } from '@/components/repair-records'
import { partGroups, type DemoState, type Inspection, type Role, type Room } from '@/data/demo'
import { readPhotos } from '@/lib/photos'
import { pendingValue } from '@/lib/corrections'
import { acceptAllItem, addItemPhotos, answer, confirmAll, confirmableItems, requestAdd, requestItemEdits, toggleItemWrongPhoto, withdraw } from '@/lib/inspection-rules'
import { sideText } from '@/lib/words'
import { Fold } from '@/components/fold'
import { MaskedPhotoNotes, PhotoManageList } from '@/components/photo-masks'
import { maskOf, maskPhoto, objectToMask } from '@/lib/photo-privacy'
import { Camera, History, Image as ImageIcon, Link2, Pencil } from 'lucide-react'
import { MoreMenu } from '@/components/more-menu'

export type OnInspectionAct = (text: string, update: (d: DemoState) => void, targetId: string) => void
// 규칙 위반은 복사본에서 먼저 확인합니다.
function check(db: DemoState, update: (d: DemoState) => void) { try { update(structuredClone(db)); return '' } catch (e) { return e instanceof Error ? e.message : '처리할 수 없어요.' } }

function PartPicker({ zone, part, setZone, setPart }: { zone: string; part: string; setZone: (v: string) => void; setPart: (v: string) => void }) {
  return <><div className="form-grid"><div className="field"><label>구역</label><Choice label="구역" value={zone} values={Object.keys(partGroups)} onChange={v => { setZone(v); setPart(partGroups[v][0]) }} /></div><div className="field"><label>부위</label><Choice label="부위" value={part} values={Array.from(new Set([...(partGroups[zone] || []), '기타']))} onChange={setPart} /></div></div>
    {zone === '기타' && <div className="field"><label htmlFor="ic-zone">구역 이름</label><Input id="ic-zone" name="customZone" /></div>}{part === '기타' && <div className="field"><label htmlFor="ic-part">부위 이름</label><Input id="ic-part" name="customPart" /></div>}</>
}
const pickedZone = (f: FormData, zone: string, part: string) => { const z = zone === '기타' ? String(f.get('customZone') || '').trim() : zone; const p = part === '기타' ? String(f.get('customPart') || '').trim() : part; return z && p ? `${z} · ${p}` : '' }

// 점검 항목 카드 안의 고치기(작성 중이면 바로, 확인 요청 뒤에는 모두 상대 승인)와 사진 추가, 빠진 하자 추가 응답
export function InspectionItemCorrections({ i, db, role, readOnly, guest, onAct, history, onShare }: { i: Inspection; db: DemoState; role: Role; readOnly: boolean; guest?: boolean; onAct: OnInspectionAct; history?: ReactNode; onShare?: () => void }) {
  const [mode, setMode] = useState<'edit' | 'photos' | 'reject' | null>(null)
  const [target, setTarget] = useState('')
  const [zone, setZone] = useState('욕실')
  const [part, setPart] = useState('천장')
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState('')
  const run = (text: string, update: (d: DemoState) => void) => { const e = check(db, update); if (e) { setError(e); return e } setError(''); onAct(text, update, i.id) }
  const canEdit = !readOnly && !guest && i.author === role && !i.removed && (!i.addRequest || i.addRequest.status === '반영')
  const draft = i.publication === 'draft'
  const add = i.addRequest
  // 경과(최신순): 사진 추가 내역 · 끝난 고치기 · 이견. 본문에는 지금 기록과 확인 대기 요약만 남겨요.
  const photoAdds = [...i.photoAdds || []].reverse()
  const historyCount = photoAdds.length + (i.corrections || []).filter(c => c.status !== '확인 대기').length + (i.edits?.length || 0)
  return <div className="rec-extra space-y-2">
    {(add || i.removed || i.wait || i.photoAdds?.some(p => p.afterConfirm)) && <div className="badges">
      {add && <Badge variant="secondary">빠진 하자로 추가 요청한 항목</Badge>}
      {i.removed && <Status>고치기로 삭제됨 · 원래 기록 보존</Status>}
      {i.photoAdds?.some(p => p.afterConfirm) && <Status>확인 뒤 사진 추가</Status>}
      {i.wait && <Status>{i.wait === '미확인' ? '상대 미확인' : '확인 요청 재통지됨'}</Status>}
    </div>}
    {add && add.status === '확인 대기' && <CorrectionRow c={add} stage={add.wait}>{!readOnly && !guest && <div className="actions mt-2">
      {add.by !== role ? <><Button size="sm" onClick={() => run(`${i.title} · 빠진 하자 추가 확인`, d => answer(d, role, i.id, add.id, true))}>확인</Button><Button size="sm" variant="outline" onClick={() => { setTarget(add.id); setMode('reject') }}>거절</Button></> : <Button size="sm" variant="outline" onClick={() => run(`${i.title} · 빠진 하자 추가 요청 거둠`, d => withdraw(d, role, i.id, add.id))}>거두기</Button>}
    </div>}</CorrectionRow>}
    <CorrectionPanel list={i.corrections || []} stageOf={c => c.wait} role={role} guest={guest} readOnly={readOnly}
      onAnswer={c => run(`${i.title} · 고친 내용 확인(${c.field}) · 본 모습 ${i.version ?? 1}번`, d => answer(d, role, i.id, c.id, true))}
      onReject={c => { setTarget(c.id); setMode('reject') }}
      onWithdraw={c => run(`${i.title} · 고친 내용 거둠(${c.field})`, d => withdraw(d, role, i.id, c.id))}
      onAcceptAll={() => run(`${i.title} · 고친 내용 모두 확인`, d => acceptAllItem(d, role, i.id))} />
    {/* 보조 행동(고치기 · 사진 추가 · 공유 링크 복사)은 카드 오른쪽 위 ⋯ 메뉴로. 노출 조건은 그대로 */}
    <MoreMenu className="absolute top-2 right-2" items={[
      ...(canEdit ? [
        { key: 'edit', label: '고치기', icon: Pencil, onSelect: () => { const [z, p] = String(pendingValue(i.corrections, '부위') ?? i.zone).split(' · '); setZone(partGroups[z] ? z : '기타'); setPart(p || '기타'); setError(''); setMode('edit') } },
        { key: 'photos', label: '사진 추가', icon: Camera, onSelect: () => { setPhotos([]); setError(''); setMode('photos') } },
      ] : []),
      ...(onShare ? [{ key: 'share', label: '공유 링크 복사', icon: Link2, onSelect: onShare }] : []),
    ]} />
    {(historyCount > 0 || history) && <Fold title="경과" count={historyCount || undefined} icon={<History size={16} />}><div className="max-h-80 space-y-2 overflow-y-auto overscroll-contain pr-1.5 [scrollbar-gutter:stable]">
      {photoAdds.map((p, n) => <div className="history-item" key={'p' + n}><div className="history-head"><strong>사진 추가 · {p.count}장</strong>{p.afterConfirm && <Status>확인 뒤 추가</Status>}</div><small className="muted">{sideText(p.by)} · {p.at}</small></div>)}
      {history}
      <CorrectionHistory list={i.corrections || []} edits={i.edits} stageOf={c => c.wait} guest={guest} inline />
    </div></Fold>}
    <MaskedPhotoNotes role={role} readOnly={readOnly || guest} items={(i.privacyMasks || []).map(m => ({ mask: m, src: i.photos[Number(m.key)], label: `사진 ${Number(m.key) + 1}` }))}
      onObject={(key, reason) => run(`${i.title} · 가린 사진에 이견 · 사진 ${Number(key) + 1}`, d => objectToMask(d.inspections.find(v => v.id === i.id)!, key, role, reason))} />
    {!readOnly && !guest && i.author === role && i.photos.length > 0 && <Fold title="사진 관리" icon={<ImageIcon size={16} />}>
      <PhotoManageList items={i.photos.map((src, n) => ({ key: String(n), src, label: `사진 ${n + 1}`, wrong: Boolean(i.wrongPhotos?.includes(n)), canWrong: true, masked: Boolean(maskOf(i, String(n))) }))}
        onWrong={(keys, mark) => run(`${i.title} · ${mark ? '잘못 올린 사진 표시' : '잘못 올린 사진 표시 떼기'} · ${keys.map(k => `사진 ${Number(k) + 1}`).join(', ')}`, d => keys.forEach(k => toggleItemWrongPhoto(d, role, i.id, Number(k))))}
        onMask={keys => run(`${i.title} · 개인정보 이유로 사진 가림 · ${keys.map(k => `사진 ${Number(k) + 1}`).join(', ')}`, d => { const x = d.inspections.find(v => v.id === i.id)!; keys.forEach(k => maskPhoto(x, k, role, x.author)) })} />
    </Fold>}
    {error && <p role="alert" className="form-error">{error}</p>}
    <ActionDialog open={mode === 'edit'} title="고치기" description='바꿀 칸만 고쳐 주세요. 사진은 [사진 추가]로 더해요.' submitLabel={draft ? '고치기' : '확인 요청'} onClose={() => setMode(null)} onSubmit={f => {
      const to = pickedZone(f, zone, part); if (!to) return '구역과 부위를 입력해 주세요.'
      const next = { title: String(f.get('title') || ''), description: String(f.get('text') || ''), zone: to }
      return run(draft ? `${i.title} · 작성 중 고치기` : `${i.title} · 고친 내용 남김 · 상대 확인 전`, d => { requestItemEdits(d, role, i.id, next, String(f.get('reason') || '')) })
    }}>
      <div className="field"><label htmlFor="ie-title">제목 · 필수</label><Input id="ie-title" name="title" maxLength={80} defaultValue={String(pendingValue(i.corrections, '제목') ?? i.title)} required /></div>
      <div className="field"><label htmlFor="ie-text">설명 · 필수</label><Textarea id="ie-text" name="text" defaultValue={String(pendingValue(i.corrections, '설명') ?? i.description)} required /></div>
      <PartPicker zone={zone} part={part} setZone={setZone} setPart={setPart} />
      {!draft && <div className="field"><label htmlFor="ie-reason">왜 바꾸나요 · 필수</label><Textarea id="ie-reason" name="reason" required placeholder="예: 다시 보니 부위가 달랐어요" /></div>}
      {!draft && i.corrections?.some(c => c.status === '확인 대기') && <p className="fix-note">확인을 기다리는 고친 내용이 채워져 있어요. 다시 고친 칸은 이전 고친 내용이 ‘새로 고쳐서 바뀜’으로 닫히고, 확인 요청은 하나로 묶여요.</p>}
      <p className="fix-notice">{draft ? '작성 중(확인 요청 전)이라 바로 반영돼요.' : '등록 후 변경 사항은 상대 확인 후 반영됩니다.'}</p>
    </ActionDialog>
    <ActionDialog open={mode === 'photos'} title="사진 추가" description="사진은 바꾸지 않고 더하기만 해요. 기존 사진은 그대로 남고, 같이 확인한 뒤라면 ‘확인 뒤 추가’로 표시돼요." submitLabel="사진 추가" onClose={() => setMode(null)} onSubmit={() => run(`${i.title} · 사진 추가 · ${photos.length}장`, d => addItemPhotos(d, role, i.id, photos))}>
      <div className="field"><label htmlFor="ia-photos">사진 · 계약서·신분증·통장 사진은 올리지 마세요</label><Input id="ia-photos" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={e => readPhotos(e.target.files, photos.length).then(p => setPhotos(o => [...o, ...p])).catch(err => setError(err.message))} />{photos.length > 0 && <small className="muted">{photos.length}장 선택됨</small>}</div>
    </ActionDialog>
    <ActionDialog open={mode === 'reject'} title="거절" description="거절하면 지금 기록은 그대로 두고, 요청과 거절 사유가 나란히 남아요." submitLabel="거절 남기기" onClose={() => setMode(null)} onSubmit={f => run(`${i.title} · 고친 내용 거절`, d => answer(d, role, i.id, target, false, String(f.get('reason') || '')))}>
      <div className="field"><label htmlFor="ij-reason">거절 사유 · 필수</label><Textarea id="ij-reason" name="reason" required /></div>
    </ActionDialog>
  </div>
}

// 빠진 하자 추가(사실 정정). 잠긴 점검의 '정정 요청하기'에서 엽니다.
export function MissingDefectDialog({ open, kind, db, room, role, onClose, onAct }: { open: boolean; kind: 'in' | 'out'; db: DemoState; room: Room; role: Role; onClose: () => void; onAct: OnInspectionAct }) {
  const [zone, setZone] = useState('욕실')
  const [part, setPart] = useState('천장')
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState('')
  return <ActionDialog open={open} title="빠진 하자 추가" description="점검을 고치는 일이라 기준 기록을 쓴 쪽이 확인해야 반영돼요. 빠진 하자 추가도 상대 쪽이 확인해야 항목이 돼요(응답이 없어도 혼자 반영되지 않아요). 기존 항목의 고치기·사진 추가·삭제 요청은 각 항목 카드에서 할 수 있어요." submitLabel="추가 요청" onClose={() => { setPhotos([]); setError(''); onClose() }} onSubmit={f => {
    const z = pickedZone(f, zone, part); if (!z) return '구역과 부위를 입력해 주세요.'
    const data = { zone: z, title: String(f.get('title') || ''), description: String(f.get('description') || ''), photos: [...photos] }
    const reason = String(f.get('reason') || '')
    const update = (d: DemoState) => { requestAdd(d, d.rooms.find(r => r.id === room.id)!, role, kind, data, reason) }
    const e = check(db, update); if (e) return e
    onAct(`${kind === 'in' ? '입주' : '퇴거'} 점검 · 빠진 하자 추가 정정 요청 · ${data.title}`, update, '')
    setPhotos([])
  }}>
    <PartPicker zone={zone} part={part} setZone={setZone} setPart={setPart} />
    <div className="field"><label htmlFor="im-title">하자 제목</label><Input id="im-title" name="title" maxLength={80} required /></div>
    <div className="field"><label htmlFor="im-desc">상태 설명</label><Textarea id="im-desc" name="description" required /></div>
    <div className="field"><label htmlFor="im-photos">사진 · 선택</label><Input id="im-photos" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={e => readPhotos(e.target.files, photos.length).then(p => setPhotos(o => [...o, ...p])).catch(err => setError(err.message))} />{photos.length > 0 && <small className="muted">{photos.length}장 선택됨</small>}</div>
    <div className="field"><label htmlFor="im-reason">추가하는 이유 · 필수</label><Textarea id="im-reason" name="reason" required placeholder="예: 입주 점검 때 빠뜨린 하자예요" /></div>
    {error && <p role="alert" className="form-error">{error}</p>}
  </ActionDialog>
}

// 남은 항목 모두 확인(확인 창 → 항목마다 확인 행동)
export function ConfirmAllItems({ items, db, role, onAct }: { items: Inspection[]; db: DemoState; role: Role; onAct: OnInspectionAct }) {
  const [open, setOpen] = useState(false)
  const ids = confirmableItems(items, role).map(i => i.id)
  if (ids.length < 2) return null
  return <><Button variant="outline" onClick={() => setOpen(true)}>남은 항목 모두 확인</Button>
    <ActionDialog open={open} title={`${ids.length}개 항목을 확인할까요?`} description="확인은 항목마다 ‘확인’ 행동으로 저장돼요. 이견이 있는 항목은 이 창을 닫고 하나씩 이견을 남겨 주세요." submitLabel={`${ids.length}개 항목 확인`} onClose={() => setOpen(false)} onSubmit={() => { const seen = Object.fromEntries(items.map(i => [i.id, i.version ?? 1])); const update = (d: DemoState) => confirmAll(d, role, ids, seen); const e = check(db, update); if (e) return e; onAct(`남은 점검 항목 모두 확인 · ${ids.length}개`, update, '') }}><p className="muted">{confirmableItems(items, role).map(i => i.title).join(', ')}</p></ActionDialog></>
}

