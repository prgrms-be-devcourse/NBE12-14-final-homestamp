import { uid, type DemoState, type Repair, type Role, type Room, type InviteLink, type Inspection, type AmountOffer, type Transfer, type Proposal } from '@/data/demo'

// 이 계정이 호실에서 어느 측인가(가입 유형이 아니라 호실 연결로 정해져요). 연결되지 않았으면 undefined.
export function sideOf(room: Room | undefined, accountId?: string): Role | undefined {
  if (!room || !accountId) return undefined
  return (Object.entries(room.members ?? {}) as [Role, string][]).find(([, id]) => id === accountId)?.[0]
}
// 초대받는 자리: 호실을 만든 쪽의 반대편
export const inviteeSide = (room: Room): Role => room.creator === '임대인' ? '임차인' : '임대인'
export function canAccessRoom(room: Room, role: Role) {
  return room.creator === role || room.connection === '연결됨'
}
export function canSeeInspection(item: Inspection, role: Role) {
  return item.author === role || item.publication !== 'draft'
}
export function respondToOffer<T extends { by: Role; status: string; disputeReason?: string; disputeBy?: Role }>(offer: T, role: Role, agree: boolean, reason = '') {
  if (offer.by === role || offer.status !== '합의 대기') throw new Error('상대 쪽의 현재 제안에만 응답할 수 있어요.')
  if (!agree && !reason.trim()) throw new Error('이견 사유를 입력해 주세요.')
  offer.status = agree ? '합의됨' : '이견'
  if (!agree) { offer.disputeReason = reason.trim(); offer.disputeBy = role }
}
export function newAmountOffer(by: Role, amount: number, reason: string, previous?: AmountOffer): AmountOffer {
  return { id: uid(), by, amount, reason, round: (previous?.round || 0) + 1, status: '합의 대기', at: new Date().toLocaleString('ko-KR') }
}
// 제3자 줄이 있는 안은 lines로 계산합니다(lib/repair-rules의 burden과 같은 규칙).
export function burdenText(p: Proposal) {
  if (p.lines) {
    const sum = (side: string) => p.lines!.filter(l => l.side === side).reduce((s, l) => s + l.amount, 0)
    return { tenant: sum('임차인'), landlord: sum('임대인'), third: sum('제3자') }
  }
  const tenant = p.payer === '나눠서' ? p.tenantAmount || 0 : p.payer === '임차인' ? p.amount : 0
  return { tenant, landlord: p.amount - tenant, third: 0 }
}
export function roomTransfers(room: Room): Transfer[] {
  return room.transfers || (room.transferBy ? [{ id: 'legacy', amount: room.transferAmount ?? 0, by: room.transferBy, direction: '반환', confirmed: room.received, at: '기존 기록' }] : [])
}
export function transferTotal(room: Room) { return roomTransfers(room).reduce((sum, t) => sum + (t.direction === '반환' ? t.amount : -t.amount), 0) }

const normalizePhone = (phone: string | undefined) => (phone || '').replace(/\D/g, '')

// 받은 초대는 역할이 아니라 "초대받은 계정의 인증 번호"로 구분합니다.
// 계정 번호는 가입할 때 휴대폰 인증을 마친 번호(로그인 세션)입니다.
// 정산 초대 링크(상태와 규칙 C): 거주 종료 뒤 연결되지 않은 상대에게. 내용 없이 가입·연결 안내만
export const activeSettlementLink = (room: Room) => room.settlementLinks?.find(l => !l.invalidatedAt)
export function isSettlementInvitation(room: Room) {
  return Boolean(room.creator && room.status === '거주 종료' && room.connection !== '연결됨' && activeSettlementLink(room) && normalizePhone(room.phone))
}
export function isPendingInvitation(room: Room) {
  return Boolean(room.creator && room.status === '거주 중' && room.connection === '초대됨' && normalizePhone(room.phone)) || isSettlementInvitation(room)
}
// 정산 초대 링크 보내기: 번호를 고쳐 다시 보내면 이전 정산 초대 링크는 무효
export function sendSettlementLink(room: Room, role: Role, phone: string) {
  if (room.status !== '거주 종료' || room.connection === '연결됨') throw new Error('거주 종료 뒤 연결되지 않은 상대에게만 보낼 수 있어요.')
  if (room.creator !== role) throw new Error('호실을 만든 쪽이 보낼 수 있어요.')
  const next = normalizePhone(phone)
  if (!/^010\d{8}$/.test(next)) throw new Error('010으로 시작하는 11자리 번호를 입력해 주세요.')
  const at = new Date().toISOString()
  room.settlementLinks = (room.settlementLinks || []).map(l => l.invalidatedAt ? l : { ...l, invalidatedAt: at, invalidatedReason: next !== normalizePhone(room.phone) ? '번호 고쳐 다시 보냄' : '다시 보냄' })
  room.phone = next
  room.settlementLinks = [...room.settlementLinks, { id: uid(), issuedAt: at }]
}
/** @deprecated 역할만으로 판단합니다. 받은 초대 표시는 canReceiveInvitation(계정 번호 기준)을 사용하세요. */
export function isDemoInvitation(room: Room, role: Role) {
  return isPendingInvitation(room) && room.creator !== role
}
export function canReceiveInvitation(room: Room, role: Role, accountPhone = '') {
  const phone = normalizePhone(accountPhone)
  return isDemoInvitation(room, role) && phone !== '' && normalizePhone(room.phone) === phone
}
export function receivedInvitationsFor(rooms: Room[], role: Role, accountPhone = '') {
  return rooms.filter(r => canReceiveInvitation(r, role, accountPhone))
}
// 1단계: 번호 일치 확인 (초대 번호 = 계정 인증 번호). 2단계 연결 수락과는 별개입니다.
export function invitationMatches(room: Room, role: Role, enteredPhone: string, accountPhone = '') {
  return canReceiveInvitation(room, role, accountPhone) && normalizePhone(enteredPhone) === normalizePhone(accountPhone)
}
export function acceptInvitation(room: Room, role: Role, enteredPhone: string, phoneVerified: boolean, accepted: boolean, accountPhone = '') {
  if (!phoneVerified || !accepted || !invitationMatches(room, role, enteredPhone, accountPhone)) throw new Error('계정 번호 일치 확인과 연결 수락이 필요해요.')
  room.connection = '연결됨'
  invalidateInviteLinks(room, '연결됨')
  room.settlementLinks = room.settlementLinks?.map(l => l.invalidatedAt ? l : { ...l, invalidatedAt: new Date().toISOString(), invalidatedReason: '연결됨' })
}
export function declineInvitation(room: Room, role: Role, accountPhone = '') {
  if (!canReceiveInvitation(room, role, accountPhone)) throw new Error('받은 초대만 거절할 수 있어요.')
  room.connection = '거절됨'
}

// 늦은 입주 점검(INSP-2a·b): 기준은 호실의 실제 입주일뿐이에요. 비어 있으면 입주 점검을 시작하기 전에 먼저 물어요(호실 생성일로 대신하지 않음).
export const todayKey = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })
export function moveInBaseDate(room: Room) {
  return room.since || ''
}
export function isLateMoveInInspection(room: Room, recordedOn = todayKey()) {
  const base = moveInBaseDate(room)
  return Boolean(base) && recordedOn > base
}

// 초대 링크 목업 (상태와 규칙 B·C, 시나리오 1-G)
export const activeInviteLinks = (room: Room) => (room.inviteLinks || []).filter(l => !l.invalidatedAt)
export function invalidateInviteLinks(room: Room, reason: NonNullable<InviteLink['invalidatedReason']>) {
  const at = new Date().toISOString()
  room.inviteLinks = (room.inviteLinks || []).map(l => l.invalidatedAt ? l : { ...l, invalidatedAt: at, invalidatedReason: reason })
}
// 번호 고치기: 초대한 쪽만, 초대됨·거절됨 상태에서만 가능합니다.
export function canChangeInvitePhone(room: Room, role: Role) {
  return room.creator === role && room.status === '거주 중' && ['초대됨', '거절됨'].includes(room.connection) && Boolean(normalizePhone(room.phone))
}
// 초대 발급. changePhone이 true면 번호를 바꾸고 이전 링크를 모두 무효로 한 뒤 새 링크를 발급합니다.
// 번호를 바꾸지 않으면 저장된 번호를 유지하고, 유효한 링크가 있으면 그 링크를 그대로 씁니다.
export function issueInvite(room: Room, role: Role, phone: string, changePhone: boolean) {
  const next = normalizePhone(phone)
  // 거절됨: 같은 번호로는 다시 초대할 수 없고, 번호를 고친 뒤에만 재초대합니다(상태와 규칙 B).
  if (room.connection === '거절됨' && !changePhone) throw new Error('거절된 번호로는 다시 초대할 수 없어요. 번호를 변경한 뒤 다시 초대해 주세요.')
  if (changePhone) {
    if (!canChangeInvitePhone(room, role)) throw new Error('초대됨·거절됨 상태에서 초대한 쪽만 번호를 바꿀 수 있어요.')
    if (next === normalizePhone(room.phone)) throw new Error('기존 번호와 같아요. 다른 번호를 입력해 주세요.')
    invalidateInviteLinks(room, '번호 변경')
    room.phone = next
  } else room.phone = room.phone || next
  room.connection = '초대됨'
  room.invitedAt = new Date().toISOString()
  room.creator = room.creator || role
  if (!activeInviteLinks(room).length) room.inviteLinks = [...room.inviteLinks || [], { id: uid(), issuedAt: room.invitedAt }]
}

// 번호만 저장된 상태(NUMBER_ONLY, 연결 전 + 번호 있음): 초대 전이라 번호만 고치고 링크·초대는 만들지 않습니다.
export function canEditNumberOnlyPhone(room: Room, role: Role) {
  return room.creator === role && room.status === '거주 중' && room.connection === '연결 전' && Boolean(normalizePhone(room.phone))
}
export function updateNumberOnlyPhone(room: Room, role: Role, phone: string) {
  const next = normalizePhone(phone)
  if (!canEditNumberOnlyPhone(room, role)) throw new Error('초대 전, 번호만 저장된 호실에서만 수정할 수 있어요.')
  if (next === normalizePhone(room.phone)) throw new Error('기존 번호와 같아요.')
  room.phone = next
}

// 통지 상태(시나리오 5): 문자 발송을 요청한 사실만 남깁니다. 발송 성공·전달됨은 서버의 문자 업체 결과로만 붙어요.
export function logRepairNotice(repair: Repair, at = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })) {
  repair.notifyLog = [...repair.notifyLog || [], { label: '발송 요청됨', at }]
}
// 링크 열람: 유효한 초대 링크의 열람 횟수를 늘리고, 그 링크로 공유한 수리 건에 '링크 열람'을 남깁니다(시나리오 1-5, 1-G).
export function recordLinkView(d: DemoState, roomId: string) {
  const room = d.rooms.find(r => r.id === roomId)
  if (!room) return
  const at = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
  room.inviteLinks = (room.inviteLinks || []).map(l => l.invalidatedAt ? l : { ...l, views: (l.views || 0) + 1 })
  if (room.shareTarget?.kind === 'repair') { const r = d.repairs.find(x => x.id === room.shareTarget!.id); if (r) { r.linkViews = (r.linkViews || 0) + 1; r.notifyLog = [...r.notifyLog || [], { label: '링크 열람', at }] } }
}
// 상대가 연결되면 혼자 끝낸 기록(입주·퇴거 점검, 끝낸 수리 건, 지불)에 확인 요청이 한 번에 갑니다(시나리오 3).
export function requestSoloRecordsOnConnect(d: DemoState, roomId: string) {
  const at = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
  const notice = (title: string, to: Role, repairId?: string) => d.notices.unshift({ id: uid(), roomId, title, to, read: false, repairId })
  // 상대 없음으로 혼자 마친 입주·퇴거 점검만 연결 시 확인 요청 상태가 됩니다. 작성 중인 점검은 그대로 두고 작성자가 직접 확인 요청해요.
  const room = d.rooms.find(r => r.id === roomId)!
  ;(['in', 'out'] as const).forEach(kind => {
    const session = kind === 'in' ? room.moveIn : room.moveOut
    if (!session?.soloLocked) return
    session.soloLocked = false
    const items = d.inspections.filter(i => i.roomId === roomId && i.kind === kind && i.author === session.author && !i.addRequest && !i.removed)
    items.forEach(i => { i.solo = false; i.ack = '확인 대기'; i.publication = 'requested' })
    if (items.length) { session.status = '확인 요청'; session.requestedAt = at; notice(`${kind === 'in' ? '입주' : '퇴거'} 점검 확인 요청 · 하자 ${items.length}개(혼자 마친 기록)`, session.author === '임차인' ? '임대인' : '임차인') }
  })
  // 작성 중 항목은 공개하지 않고 단독 표시만 거둡니다.
  d.inspections.filter(i => i.roomId === roomId && i.solo && i.publication === 'draft').forEach(i => { i.solo = false })
  d.repairs.filter(r => r.roomId === roomId).forEach(r => {
    if (r.status === '처리 완료' && r.soloClosed && r.soloReason === '상대 없음') { r.soloReason = undefined; r.confirmRequested = true; r.notifyLog = [...r.notifyLog || [], { label: '종료 확인 요청(연결 뒤 일괄)', at }]; notice(`${r.title} · 혼자 종료한 수리 건 종료 확인 요청`, r.soloClosed === '임차인' ? '임대인' : '임차인', r.id) }
    r.payments?.filter(p => !p.confirmed && !p.disputes?.length).forEach(p => notice(`${r.title} · 지불 ${p.amount.toLocaleString('ko-KR')}원 금액 확인 요청`, p.by === '임차인' ? '임대인' : '임차인', r.id))
  })
}
