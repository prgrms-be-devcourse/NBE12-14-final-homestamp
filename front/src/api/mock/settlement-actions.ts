import type {
  SettlementAction,
  SettlementItemAction,
  TransferAction,
} from "@/api/actions"
import type { Role, Room, Settlement, Transfer } from "@/data/demo"

type Viewer = { guest?: boolean; readOnly?: boolean }

// 서버가 연결되기 전 Mock: GET /api/settlements/{id}의 availableActions를 서버 대신 계산해요.
// 서버가 연결되면 응답에 담긴 값을 그대로 쓰고 이 함수는 쓰지 않아요.
export function settlementAvailableActions(
  room: Room,
  role: Role,
  viewer: Viewer = {},
): SettlementAction[] {
  if (viewer.guest || viewer.readOnly || room.status === "정산 완료") return []
  const offer = room.depositOffer
  const theirOffer = offer?.status === "합의 대기" && offer.by !== role
  const out: SettlementAction[] = ["deposit-proposals"]
  if (theirOffer) out.push("deposit-agree", "deposit-dispute")
  out.push("items", "transfers")
  if (room.status === "거주 종료") {
    if (room.finishBy && room.finishBy !== role) out.push("finish-confirm")
    else if (!room.finishBy) out.push("finish")
  }
  return out
}

// 정산 항목마다(돈 안건 장치 하나: 부담·정산 항목·보증금 공통, 06 4장)
export function settlementItemAvailableActions(
  s: Settlement,
  role: Role,
  viewer: Viewer = {},
): SettlementItemAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const out: SettlementItemAction[] = []
  const waiting = s.status === "합의 대기"
  if (s.by !== role && waiting) {
    // 수리 건 금액이 아직 정해지지 않은 항목은 동의할 수 없어요(금액 미정)
    if (!s.autoChange?.pending) out.push("agree")
    out.push("dispute")
  }
  if (s.by === role && waiting && !s.autoChange) out.push("withdraw")
  if (!s.excludeProposal) out.push("exclude")
  if (s.excludeProposal && s.excludeProposal.by !== role)
    out.push("exclude-agree", "exclude-dispute")
  if (s.excludeProposal && s.excludeProposal.by === role)
    out.push("exclude-withdraw")
  // 수리 건에서 온 항목은 [바꾸기] 대신 수리 건에서 고쳐요(08 10-1)
  if (!waiting && !s.repairId) out.push("proposals")
  return out
}

// 정산 송금마다: 기록을 남기지 않은 쪽이 응답해요
export function transferAvailableActions(
  t: Transfer,
  role: Role,
  viewer: Viewer = {},
): TransferAction[] {
  if (viewer.guest || viewer.readOnly) return []
  return t.by !== role && !t.confirmed ? ["respond"] : []
}
