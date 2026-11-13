import type { PaymentAction, RepairAction } from "@/api/actions"
import type { Payment, Repair, Role, Room } from "@/data/demo"
import {
  canRecur,
  openPaymentDispute,
  roomSoloReason,
  soloReasonFor,
} from "@/lib/repair-rules"

// 서버가 연결되기 전 Mock: GET /api/repair-cases/{id}의 availableActions를 서버 대신 계산해요.
// 서버가 연결되면 응답에 담긴 값을 그대로 쓰고 이 함수는 쓰지 않아요. 판단은 여기에서만 하고 화면은 하지 않아요.
export function repairAvailableActions(
  r: Repair,
  room: Room,
  role: Role,
  viewer: { guest?: boolean; readOnly?: boolean } = {},
): RepairAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const living = room.status === "거주 중"
  const mine = r.author === role
  const bothClosed = r.status === "처리 완료" && !r.soloClosed
  const solo = roomSoloReason(room)
  const counterpartMember =
    room.connection === "연결됨" && !room.counterpartLeft
  const lateSoloConfirm =
    r.status === "처리 완료" &&
    Boolean(r.soloClosed) &&
    r.soloClosed !== role &&
    counterpartMember
  const theirClose = Boolean(r.closeBy) && r.closeBy !== role
  const out: RepairAction[] = []
  const add = (a: RepairAction, when: boolean) => {
    if (when) out.push(a)
  }
  add("respond", living && !mine && r.ack !== "확인됨" && !bothClosed)
  add("start", living && ["확인 전", "확인 완료"].includes(r.status))
  add("request-close", living && r.status === "처리 중" && !r.closeBy && !solo)
  add(
    "close-alone",
    living &&
      ((r.status === "처리 중" && !r.closeBy && Boolean(solo)) ||
        (r.closeBy === role &&
          soloReasonFor(room, r, "close") === "상대 미확인")),
  )
  add("confirm-close", living && (theirClose || lateSoloConfirm))
  add("not-yet", living && theirClose)
  add("reopen", living && lateSoloConfirm)
  add("recur", living && canRecur(r))
  add("photos", mine || (living && r.status === "처리 중"))
  add(
    "supplement",
    living &&
      mine &&
      (r.ack === "보충 요청" || r.ack === "이견") &&
      !bothClosed,
  )
  add("corrections", mine && !bothClosed)
  return out
}

// 지불마다의 availableActions(MONEY-2). 지불·돈 기록은 거주가 끝난 뒤에도 정산 전까지 이어져요.
export function paymentAvailableActions(
  p: Payment,
  role: Role,
  viewer: { guest?: boolean; readOnly?: boolean } = {},
): PaymentAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const mine = p.by === role
  const out: PaymentAction[] = []
  if (!mine && !p.confirmed) out.push("respond")
  if (mine && !p.confirmed && openPaymentDispute(p)) out.push("supplement")
  if (mine) out.push("corrections")
  return out
}
