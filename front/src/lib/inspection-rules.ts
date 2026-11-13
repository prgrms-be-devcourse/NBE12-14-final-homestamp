import { uid, type DemoState, type FactCorrection, type Inspection, type InspectionSession, type Role, type Room } from '@/data/demo'
import { factRequestName, nowText, roomSoloReason } from '@/lib/repair-rules'
import { isLateMoveInInspection, todayKey } from '@/lib/workflows'
import { assertSeenVersion, bumpVersion, versionOf, VERSION_CONFLICT_MESSAGE } from '@/lib/versions'
import { sideText } from '@/lib/words'

// 점검(상태와 규칙 H)과 정정(G). 잠긴 점검에는 정정만 덧붙이고, 원래 기록은 지우지 않습니다.
// 잠금 판단: 처음 점검 항목만 셉니다. 정정으로 추가 요청한 항목과 정정으로 삭제된 항목은 잠금 판단에서 뺍니다.
export const lockItems = (items: Inspection[]) => items.filter(i => !i.addRequest && !i.removed)
export function isInspectionLocked(items: Inspection[]) {
  const base = lockItems(items)
  return base.length > 0 && base.every(i => ['확인됨', '이견', '상대 미확인'].includes(i.ack))
}
const item = (d: DemoState, id: string) => { const i = d.inspections.find(v => v.id === id); if (!i) throw new Error('점검 항목을 찾을 수 없어요.'); return i }

// 고치기(팀 확정 정책): 작성 중(확인 요청 전 초안)에는 바로 고쳐지고 이력만 남아요.
// 확인 요청으로 등록된 뒤에는 도장 여부와 관계없이 칸마다 '고친 내용'으로 남아 상대 쪽이 승인해야 반영돼요.
// 확인 전·거절·거둠·미응답이면 지금 기록 그대로. 같은 칸을 다시 고치면 이전 고친 내용은 '대체됨'으로 닫혀 이력에 남아요.
function apply(i: Inspection, c: FactCorrection<string>) {
  c.status = '반영'; c.mode = '상대 확인'; c.decidedAt = nowText(); c.wait = undefined
  if (['제목', '설명', '부위'].includes(c.field)) {
    bumpVersion(i)
    // 이견·미확인이던 항목의 내용이 바뀌면: 이전 이견은 이력(disputes·이 고친 내용)에 남기고, 새 내용은 다시 확인을 받아요.
    if (i.ack === '이견' || i.ack === '상대 미확인') {
      const last = i.disputes?.at(-1)
      c.priorDispute = last ? `${sideText(last.by)} 이견 · ${last.reason} · ${last.at}` : '상대 미확인'
      i.ack = '확인 대기'; i.wait = undefined
    }
  }
  if (c.field === '부위') i.zone = c.to
  else if (c.field === '제목') i.title = c.to
  else if (c.field === '설명') i.description = c.to
  else if (c.field === '항목 삭제') i.removed = true
  else if (c.field === '빠진 하자 추가') { i.ack = '확인됨'; i.solo = false }
}
const editable = (i: Inspection) => !i.removed && (!i.addRequest || i.addRequest.status === '반영')
function newRequest(i: Inspection, role: Role, field: string, from: string, to: string, reason: string) {
  const list = i.corrections || []
  if (list.some(c => c.status === '확인 대기' && c.field === field && c.to === to)) throw new Error('같은 내용으로 고친 내용이 이미 확인을 기다리고 있어요.')
  const at = nowText()
  list.forEach(c => { if (c.status === '확인 대기' && c.field === field) { c.status = '대체됨'; c.decidedAt = at; c.wait = undefined } })
  const c: FactCorrection<string> = { id: uid(), by: role, field, from, to, reason: reason.trim(), at, status: '확인 대기', baseVersion: versionOf(i) }
  i.corrections = [...list, c]
  return c
}
// [고치기]: 제목·설명·부위 중 바뀐 칸마다 고친 내용 하나
export function requestItemEdits(d: DemoState, role: Role, id: string, next: { title: string; description: string; zone: string }, reason: string) {
  const i = item(d, id)
  if (i.author !== role) throw new Error('고치기는 처음 기록한 쪽만 할 수 있어요. 빠진 하자는 누구든 추가 요청할 수 있어요.')
  if (!editable(i)) throw new Error('고칠 수 없는 항목이에요.')
  const title = next.title.trim(), description = next.description.trim()
  if (!title || !description) throw new Error('제목과 설명을 입력해 주세요.')
  const waiting = (field: string, to: string) => i.corrections?.some(c => c.status === '확인 대기' && c.field === field && c.to === to)
  const changes = ([['제목', i.title, title], ['설명', i.description, description], ['부위', i.zone, next.zone]] as const).filter(([f, a, b]) => a !== b && !waiting(f, b))
  if (!changes.length) throw new Error('바뀐 내용이 없어요.')
  if (i.publication === 'draft') {
    // 작성 중(상대에게 보이기 전, 등록 이전 단계)에는 바로 고쳐지고 이력만 남아요.
    i.edits = [...i.edits || [], { by: role, at: nowText(), titleBefore: i.title, titleAfter: title, before: i.description, after: description }]
    i.title = title; i.description = description; i.zone = next.zone; bumpVersion(i)
    return []
  }
  if (!reason.trim()) throw new Error('왜 바꾸는지 적어 주세요.')
  return changes.map(([field, from, to]) => newRequest(i, role, field, from, to, reason))
}
// 항목 삭제 요청(기존 기능 유지): 원래 기록한 쪽, 상대 확인이 있어야 '정정으로 삭제됨'
export function requestFact(d: DemoState, role: Role, id: string, field: '항목 삭제', to: string, reason: string) {
  const i = item(d, id)
  if (i.author !== role) throw new Error('항목 삭제는 원래 기록한 쪽이 요청해요.')
  if (!editable(i)) throw new Error('정정할 수 없는 항목이에요.')
  if (i.corrections?.some(c => c.status === '확인 대기' && c.field === field)) throw new Error('응답을 기다리는 삭제 요청이 있어요.')
  if (!reason.trim()) throw new Error('정정 사유를 입력해 주세요.')
  return newRequest(i, role, field, '항목 있음', to, reason)
}
// 빠진 하자 추가: 누구든(상대 쪽도) 요청, 상대 쪽 확인이 있어야 항목이 돼요(미응답이어도 혼자 반영하지 않음)
export function requestAdd(d: DemoState, room: Room, role: Role, kind: 'in' | 'out', data: { zone: string; title: string; description: string; photos: string[] }, reason: string) {
  if (!data.title.trim() || !data.description.trim()) throw new Error('하자 제목과 설명을 입력해 주세요.')
  if (!reason.trim()) throw new Error('정정 사유를 입력해 주세요.')
  const c: FactCorrection<string> = { id: uid(), by: role, field: '빠진 하자 추가', from: '기록 없음', to: `${data.zone} · ${data.title.trim()}`, reason: reason.trim(), at: nowText(), status: '확인 대기' }
  const i: Inspection = { id: uid(), roomId: room.id, kind, zone: data.zone, title: data.title.trim(), description: data.description.trim(), photos: data.photos, ack: '확인 대기', author: role, art: 'wall', publication: 'requested',
    late: kind === 'in' && isLateMoveInInspection(room), afterEnd: room.status !== '거주 중', addRequest: c }
  d.inspections.push(i)
  return i
}
// 덧붙이기: 사진 추가(상대 확인 없이 남고 '확인 뒤 추가' 표시), 잘못 올린 사진 표시(PHO-9, 올린 사람만 붙이고 뗌)
export function addItemPhotos(d: DemoState, role: Role, id: string, photos: string[]) {
  const i = item(d, id)
  if (i.author !== role) throw new Error('처음 기록한 쪽만 이 항목에 사진을 더할 수 있어요.')
  if (i.removed) throw new Error('정정으로 삭제된 항목이에요.')
  if (!photos.length) throw new Error('더할 사진을 골라 주세요.')
  i.photos = [...i.photos, ...photos]
  i.photoAdds = [...i.photoAdds || [], { by: role, at: nowText(), count: photos.length, afterConfirm: i.ack === '확인됨' }]
}
export function toggleItemWrongPhoto(d: DemoState, role: Role, id: string, index: number) {
  const i = item(d, id)
  if (i.author !== role) throw new Error('올린 사람만 잘못 올린 사진 표시를 붙이거나 뗄 수 있어요.')
  i.wrongPhotos = i.wrongPhotos?.includes(index) ? i.wrongPhotos.filter(n => n !== index) : [...i.wrongPhotos || [], index]
}
function findCorrection(i: Inspection, cid: string) {
  const c = i.addRequest?.id === cid ? i.addRequest : i.corrections?.find(v => v.id === cid)
  if (!c) throw new Error('정정 요청을 찾을 수 없어요.')
  return c
}
export function answer(d: DemoState, role: Role, id: string, cid: string, accept: boolean, reason = '') {
  const i = item(d, id); const c = findCorrection(i, cid)
  if (c.status === '대체됨') throw new Error(VERSION_CONFLICT_MESSAGE)
  if (c.status !== '확인 대기' || c.by === role) throw new Error('상대의 확인 대기 정정에만 답할 수 있어요.')
  c.decidedBy = role
  if (accept) {
    // 잠긴 점검은 잠긴 채로 둬요: 반영으로 항목이 다시 확인 대기가 되어도 점검 잠금은 풀리지 않게 상태를 고정해요.
    const room = d.rooms.find(r => r.id === i.roomId)
    const m = room && sessionOf(room, i.kind)
    if (room && m && m.status !== '잠김' && inspectionStatus(room, d.inspections.filter(x => x.roomId === i.roomId && x.kind === i.kind), i.kind) === '잠김') m.status = '잠김'
    apply(i, c)
  }
  else { if (!reason.trim()) throw new Error('거절 사유를 입력해 주세요.'); c.status = '거절'; c.rejectReason = reason.trim(); c.decidedAt = nowText(); c.wait = undefined }
}
// [모두 확인]: 이 항목에 상대가 고친 확인 대기 내용을 칸마다 확인
export function acceptAllItem(d: DemoState, role: Role, id: string) {
  const i = item(d, id)
  const ids = (i.corrections || []).filter(c => c.status === '확인 대기' && c.by !== role).map(c => c.id)
  if (!ids.length) throw new Error('확인할 고친 내용이 없어요.')
  ids.forEach(cid => answer(d, role, id, cid, true))
}
export function withdraw(d: DemoState, role: Role, id: string, cid: string) {
  const i = item(d, id); const c = findCorrection(i, cid)
  if (c.status !== '확인 대기' || c.by !== role) throw new Error('응답 전인 내 정정 요청만 철회할 수 있어요.')
  c.status = '철회'; c.decidedAt = nowText(); c.wait = undefined
}
// 남은 항목 모두 확인: 확인 창을 거쳐 항목마다 확인 행동으로 저장
export function confirmableItems(items: Inspection[], role: Role) {
  return items.filter(i => i.author !== role && i.ack === '확인 대기' && i.publication !== 'draft' && !i.addRequest && !i.removed && !i.solo)
}
// 항목 하나 확인: 본 모습 번호가 지금과 다르면 다시 보게 해요(REP-7d·7j).
export function confirmItem(d: DemoState, role: Role, id: string, seenVersion?: number) {
  const i = item(d, id)
  if (i.author === role || i.ack !== '확인 대기') throw new Error('확인할 수 없는 항목이에요.')
  assertSeenVersion(i, seenVersion)
  i.ack = '확인됨'; i.wait = undefined
}
export function confirmAll(d: DemoState, role: Role, ids: string[], seen: Record<string, number> = {}) {
  ids.forEach(id => { const i = item(d, id); if (i.author === role || i.ack !== '확인 대기') throw new Error('확인할 수 없는 항목이 있어요.'); assertSeenVersion(i, seen[id]); i.ack = '확인됨'; i.wait = undefined
    d.events.unshift({ id: uid(), roomId: i.roomId, text: `${i.title} · 점검 확인(남은 항목 모두 확인)`, by: role, at: nowText(), target: { kind: 'inspection', id: i.id } }) })
}

// 시연 전용 시간 경과: 요청 → 48시간 재통지 → 24시간 상대 미확인
export interface InspectionWait { itemId: string; cid?: string; label: string; stage?: '재통지' | '미확인' }
export function inspectionWaits(items: Inspection[]): InspectionWait[] {
  const list: InspectionWait[] = []
  items.forEach(i => {
    if (i.ack === '확인 대기' && i.publication !== 'draft' && !i.solo && !i.addRequest && !i.removed) list.push({ itemId: i.id, label: `${i.title} · 점검 확인 요청`, stage: i.wait })
    ;[...i.corrections || [], ...i.addRequest ? [i.addRequest] : []].forEach(c => { if (c.status === '확인 대기') list.push({ itemId: i.id, cid: c.id, label: `${i.title} · ${c.field === '항목 삭제' || c.field === '빠진 하자 추가' ? `${c.field} 정정 요청` : factRequestName(c.field)}`, stage: c.wait }) })
  })
  return list
}
export function advance(d: DemoState, w: InspectionWait) {
  const i = item(d, w.itemId)
  if (!w.cid) { i.wait = i.wait ? '미확인' : '재통지'; if (i.wait === '미확인') i.ack = '상대 미확인'; return }
  const c = findCorrection(i, w.cid)
  if (!c.wait) { c.wait = '재통지'; return }
  // 고친 내용은 미응답이어도 반영하지 않아요(지금 기록 유지, 상대 미확인 표시만).
  c.wait = '미확인'
}

/* ── 점검 전체 단위(입주·퇴거 각각 호실당 하나, 상태와 규칙 H) ── */
export type MoveInStatus = '없음' | '작성 중' | '확인 요청' | '잠김'
type Kind = 'in' | 'out'
const sessionKey = (kind: Kind) => kind === 'in' ? 'moveIn' as const : 'moveOut' as const
export const sessionOf = (room: Room, kind: Kind) => room[sessionKey(kind)]
export const kindLabel = (kind: Kind) => kind === 'in' ? '입주' : '퇴거'
// 잠김: 확인 요청 뒤 모든 하자 항목이 확인·이견·상대 미확인으로 정리되거나, 하자 없이 요청해 '하자 기록 없음'으로 잠긴 경우
export function inspectionStatus(room: Room, items: Inspection[], kind: Kind): MoveInStatus {
  const m = sessionOf(room, kind)
  if (!m) return '없음'
  if (m.status === '잠김') return '잠김'
  if (m.status === '확인 요청' && isInspectionLocked(items)) return '잠김'
  return m.status
}
// 점검 시작(POST /rooms/{id}/inspections와 같은 규칙): 입주 점검인데 입주일이 비어 있으면 시작하지 않아요(MOVE_IN_DATE_REQUIRED).
// 입주일보다 늦게 시작하면 시작한 날을 '입주 후 등록' 날짜로 남겨요(INSP-2a).
export const MOVE_IN_DATE_REQUIRED = '입주일을 먼저 알려 주세요. 입주 점검을 시작하려면 꼭 필요해요.'
function newSession(room: Room, kind: Kind, role: Role): InspectionSession {
  if (kind === 'in' && !room.since) throw new Error(MOVE_IN_DATE_REQUIRED)
  return { author: role, status: '작성 중', startedAt: nowText(), ...(kind === 'in' && isLateMoveInInspection(room) ? { lateOn: todayKey() } : {}) }
}
// 입주일이 비어 있는 호실의 입주 점검 시작: 입주일 저장과 시작을 한 번에(같은 트랜잭션, 명세 4장 inspections의 moveInDate)
export function startInspection(d: DemoState, roomId: string, kind: Kind, role: Role, moveInDate?: string) {
  const room = d.rooms.find(r => r.id === roomId)
  if (!room) throw new Error('호실을 찾을 수 없어요.')
  const key = sessionKey(kind)
  if (room[key]) throw new Error(`${kindLabel(kind)} 점검은 호실에 하나예요. 이미 시작된 점검이 있어요.`)
  if (kind === 'in' && !room.since) {
    const date = (moveInDate ?? '').trim()
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) throw new Error(MOVE_IN_DATE_REQUIRED)
    room.since = date
  }
  room[key] = newSession(room, kind, role)
}
// 먼저 작성한 쪽이 기준 기록 작성자가 됩니다. 하자는 작성 중에만 추가할 수 있고, 부위 사진은 확인 대상이 아니라 확인 요청 뒤에도 작성자가 올릴 수 있어요.
export function ensureAuthor(room: Room, kind: Kind, role: Role, photoOnly: boolean) {
  const key = sessionKey(kind)
  const m = room[key]
  if (!m) { room[key] = newSession(room, kind, role); return }
  if (m.author !== role) throw new Error(`${kindLabel(kind)} 점검은 호실에 하나예요. ${sideText(m.author)}이 기준 기록을 쓰고 있어요. 빠진 하자는 확인 요청 뒤 정정으로 추가해 주세요.`)
  if (!photoOnly && m.status !== '작성 중') throw new Error('확인 요청을 보낸 뒤에는 하자를 정정(빠진 하자 추가)으로 덧붙여요.')
}
// 점검 확인 요청: 작성자가 명시적으로 누를 때만. 작성 중 하자 전체가 한 번에 상대에게 보이고, 하자가 없으면 '하자 기록 없음'으로 잠깁니다.
export function requestInspectionAll(d: DemoState, roomId: string, kind: Kind, role: Role) {
  const room = d.rooms.find(r => r.id === roomId)!
  const key = sessionKey(kind)
  if (!room[key]) room[key] = newSession(room, kind, role)
  const m = room[key]!
  if (m.author !== role || m.status !== '작성 중') throw new Error(`작성 중인 내 ${kindLabel(kind)} 점검만 확인 요청할 수 있어요.`)
  const items = d.inspections.filter(i => i.roomId === roomId && i.kind === kind && i.author === role && !i.addRequest && !i.removed)
  items.forEach(i => { i.publication = 'requested'; i.solo = false; i.ack = '확인 대기' })
  m.requestedAt = nowText()
  // 하자가 없어도 '하자 기록 없음'으로 상대에게 확인 요청을 보내고, 상대가 확인해야 잠겨요(H).
  m.status = '확인 요청'
  m.noDefects = !items.length
}
export function confirmNoDefects(d: DemoState, roomId: string, kind: Kind, role: Role) {
  const m = d.rooms.find(r => r.id === roomId)?.[sessionKey(kind)]
  if (!m || m.author === role || m.status !== '확인 요청' || !m.noDefects) throw new Error('상대가 보낸 ‘하자 기록 없음’ 확인 요청에만 답할 수 있어요.')
  m.status = '잠김'; m.noDefectsConfirmedBy = role
}
// 입주 점검 이름(기존 호출부 호환)
export const moveInStatus = (room: Room, items: Inspection[]) => inspectionStatus(room, items, 'in')
export const ensureMoveInAuthor = (room: Room, role: Role, photoOnly: boolean) => ensureAuthor(room, 'in', role, photoOnly)
export const requestMoveIn = (d: DemoState, roomId: string, role: Role) => requestInspectionAll(d, roomId, 'in', role)
// 상대 없음(단독 기록)일 때 작성자가 혼자 점검을 마칩니다(1-1 혼자 할 수 있는 것: 점검 잠금). 상대가 연결되면 확인 요청이 한 번에 가요.
export function finishSoloInspection(d: DemoState, roomId: string, kind: Kind, role: Role) {
  const room = d.rooms.find(r => r.id === roomId)!
  if (roomSoloReason(room) !== '상대 없음') throw new Error('상대가 없을 때만 혼자 점검을 마칠 수 있어요.')
  const key = sessionKey(kind)
  if (!room[key]) room[key] = newSession(room, kind, role)
  const m = room[key]!
  if (m.author !== role || m.status !== '작성 중') throw new Error(`작성 중인 내 ${kindLabel(kind)} 점검만 마칠 수 있어요.`)
  const items = d.inspections.filter(i => i.roomId === roomId && i.kind === kind && i.author === role && !i.addRequest && !i.removed)
  items.forEach(i => { i.publication = 'requested'; i.solo = true; i.ack = '확인 대기' })
  m.status = '잠김'; m.soloLocked = true; m.noDefects = !items.length; m.requestedAt = nowText()
}
