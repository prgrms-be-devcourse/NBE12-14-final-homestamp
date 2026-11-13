import type { Role, Room, Settlement } from "@/data/demo"
import { roomTransfers } from "@/lib/workflows"
import { reaskWaitDays } from "@/lib/reask"
import { VERSION_CONFLICT_MESSAGE } from "@/lib/versions"

// 정산 끝내기(06 SET-5a~e, 03 1-4)와 송금 합계(06 SET-4, 03 J, 08 10장).

/* ── 송금 합계: 상대가 확인한 송금만 더해요. 확인 전 송금은 "받았는지 확인 중"으로 따로 보여요. ── */
const signed = (t: { direction: "반환" | "추가 송금"; amount: number }) =>
  t.direction === "반환" ? t.amount : -t.amount
export const confirmedTransferTotal = (room: Room) =>
  roomTransfers(room)
    .filter((t) => t.confirmed)
    .reduce((s, t) => s + signed(t), 0)
export const pendingTransferTotal = (room: Room) =>
  roomTransfers(room)
    .filter((t) => !t.confirmed)
    .reduce((s, t) => s + signed(t), 0)

/* ── 정산 모습: 끝내기를 요청한 뒤 이 값이 바뀌면 요청이 닫혀요(5b·5e, 불변식 19). ── */
// 보증금, 정산 항목(금액·방향·상태·빼기), 송금(금액·확인)을 담아요. 서버에서는 정산 버전 번호가 같은 일을 해요.
export function settlementSnapshot(room: Room, items: Settlement[]) {
  return JSON.stringify({
    deposit: [room.deposit, room.depositConfirmed, room.depositOffer?.amount],
    depositStatus: room.depositOffer?.status,
    items: items
      .map((s) => [
        s.id,
        s.amount,
        s.direction,
        s.status,
        Boolean(s.excluded),
        Boolean(s.withdrawn),
        Boolean(s.excludeProposal),
      ])
      .sort(),
    transfers: roomTransfers(room).map((t) => [t.id, t.amount, t.confirmed]),
  })
}

export const FINISH_CHANGED_MESSAGE =
  "정산 내용이 바뀌어 끝내기 요청이 닫혔어요"
const HOURS_72 = 72 * 3_600_000

export type FinishState =
  | { kind: "none" }
  | { kind: "waiting"; by: Role; canAloneAt: number }
  | { kind: "changed" }
  | { kind: "not-yet"; by?: Role; text?: string; waitDays: number }

// 지금 끝내기 요청의 상태. 요청 뒤 정산이 바뀌었으면 저장된 값과 관계없이 '바뀌어 닫힘'이에요.
export function finishState(
  room: Room,
  items: Settlement[],
  now = Date.now(),
): FinishState {
  if (room.finishBy) {
    if (
      room.finishSnapshot !== undefined &&
      room.finishSnapshot !== settlementSnapshot(room, items)
    )
      return { kind: "changed" }
    return {
      kind: "waiting",
      by: room.finishBy,
      canAloneAt:
        Date.parse(room.finishAt ?? new Date(now).toISOString()) + HOURS_72,
    }
  }
  if (room.finishClosed?.reason === "changed") return { kind: "changed" }
  if (room.finishClosed?.reason === "not-yet")
    return {
      kind: "not-yet",
      by: room.finishClosed.by,
      text: room.finishClosed.text,
      waitDays: reaskWaitDays(room.finishClosed.at, now),
    }
  return { kind: "none" }
}

// 요청이 바뀌어 닫혔으면 기록으로 남겨요(5b). 화면이 정산을 다시 그릴 때 불러요.
function closeIfChanged(room: Room, items: Settlement[], now: number) {
  if (finishState(room, items, now).kind === "changed" && room.finishBy) {
    room.finishBy = undefined
    room.finishSnapshot = undefined
    room.finishAt = undefined
    room.finishClosed = { reason: "changed", at: new Date(now).toISOString() }
  }
}

// [정산 끝내기 제안](5a). 상대가 연결 전이면 혼자 정리하고 바로 끝내요(03 1-4).
// [아직 안 끝났어요]로 닫혔으면 다시 요청은 7일 뒤(5c), 정산이 바뀌어 닫혔으면 제한 없음(5b).
export function requestFinish(
  room: Room,
  items: Settlement[],
  role: Role,
  now = Date.now(),
) {
  if (room.status !== "거주 종료")
    throw new Error("거주가 끝난 뒤에 정산을 끝낼 수 있어요.")
  closeIfChanged(room, items, now)
  if (room.finishBy) throw new Error("이미 끝내기를 요청했어요.")
  if (room.connection === "연결 전") {
    room.status = "정산 완료"
    room.finishedAlone = role
    return
  }
  const closed = room.finishClosed
  if (closed?.reason === "not-yet") {
    const days = reaskWaitDays(closed.at, now)
    if (days > 0) throw new Error(`${days}일 뒤 다시 요청할 수 있어요.`)
  }
  room.finishBy = role
  room.finishAt = new Date(now).toISOString()
  room.finishSnapshot = settlementSnapshot(room, items)
  room.finishClosed = undefined
}

// [정산 끝내기 확인](5a·5e): 요청 뒤 정산이 바뀌었으면 완료하지 않고 요청을 닫아요.
export function confirmFinish(
  room: Room,
  items: Settlement[],
  role: Role,
  now = Date.now(),
) {
  if (!room.finishBy || room.finishBy === role)
    throw new Error("상대가 요청한 정산 끝내기만 확인할 수 있어요.")
  if (finishState(room, items, now).kind === "changed") {
    closeIfChanged(room, items, now)
    throw new Error(`${VERSION_CONFLICT_MESSAGE} ${FINISH_CHANGED_MESSAGE}.`)
  }
  room.status = "정산 완료"
  room.finishSnapshot = undefined
}

// (아직 안 끝났어요 · 사유)(5c): 요청이 닫히고 혼자 끝내기는 열리지 않아요.
export function rejectFinish(
  room: Room,
  role: Role,
  reason: string,
  now = Date.now(),
) {
  if (!room.finishBy || room.finishBy === role)
    throw new Error("상대가 요청한 정산 끝내기에만 답할 수 있어요.")
  if (!reason.trim()) throw new Error("사유를 입력해 주세요.")
  room.finishBy = undefined
  room.finishSnapshot = undefined
  room.finishAt = undefined
  room.finishClosed = {
    reason: "not-yet",
    by: role,
    text: reason.trim(),
    at: new Date(now).toISOString(),
  }
}

// [혼자 끝내기](5d): 요청한 쪽이, 72시간 답이 없고 요청이 바뀌지 않았을 때만.
export const SOLO_NOT_ALLOWED_MESSAGE =
  "지금은 혼자 끝낼 수 없어요. 상대가 답했거나 정산 내용이 바뀌어 요청이 닫혔어요."
export function finishAlone(
  room: Room,
  items: Settlement[],
  role: Role,
  now = Date.now(),
) {
  const st = finishState(room, items, now)
  if (st.kind !== "waiting" || st.by !== role || now < st.canAloneAt)
    throw new Error(SOLO_NOT_ALLOWED_MESSAGE)
  room.status = "정산 완료"
  room.finishedAlone = role
  room.finishSnapshot = undefined
}
