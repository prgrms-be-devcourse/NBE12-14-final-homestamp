import type { Role, SettlementServerState, Transfer } from "@/data/demo"
import type { SettlementAction, TransferAction } from "./actions"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import { receiverOf } from "@/lib/settlement-rules"

// 정산 상세(GET /api/settlements/{id}, GET /api/rooms/{id}/settlement, 06 4-8)의 서버 모양. 칸 이름은 백엔드 응답과 같아요.
// 지금 서버가 주는 칸은 정산 머리와 송금이에요(#442). 보증금 금액·항목은 그 기능을 연결할 때 칸을 더해요.
type ServerSide = "TENANT" | "LANDLORD"
type TransferDirection = "REFUND" | "EXTRA"

// 송금 하나(SET-4). 고친 내용(corrections)은 수리 건 상세와 같은 모양이고, 고치기 화면을 연결할 때 써요
export interface SettlementTransferBody {
  id: string
  direction: TransferDirection
  recordedBySide: ServerSide
  amount: number
  paidOn: string
  versionNo: number
  response: "PENDING" | "ACK" | "NEED_MORE" | "DISPUTE"
  confirmedAmount: number | null
  sameAsTransferId: string | null
  receiptConfirmedAt: string | null
  createdAt: string
  corrections: unknown[]
  availableActions: string[]
}

export interface SettlementDetailBody {
  id: string
  roomRecordId: string
  mySide: ServerSide
  depositStatus: SettlementServerState["depositStatus"]
  // 정산 모습 번호(05 settlement.snapshot_no). 끝내기 확인 때 seenVersionNo로 돌려보내요(SET-5e)
  snapshotNo: number
  createdAt: string
  // 내가 아직 답하지 않은 확인 요청 수(SET-5g, 끝내기 확인 화면용)
  unansweredForMe: number
  availableActions: string[]
  transfers: SettlementTransferBody[]
  // 상대가 확인한 반환·추가 송금 합계(같은 송금으로 묶인 건 한 번)
  confirmedRefund: number
  confirmedExtra: number
}

// 서버가 주는 행동 이름은 API 끝 단어와 같아요(06 4-0). 화면에 버튼이 있고 주소를 연결한 것만 남겨요.
// 보증금·항목은 요청 본문이 아직 서버와 맞지 않아 빼요. 송금 고치기(corrections)는 고치기 API를 연결할 때 더해요
const SETTLEMENT_ACTIONS: readonly SettlementAction[] = [
  "transfers",
  "finish",
  "finish-confirm",
  "finish-not-yet",
  "finish-alone",
]
const TRANSFER_ACTIONS: readonly TransferAction[] = ["respond"]
const only = <A extends string>(list: readonly A[], names: readonly string[]) =>
  names.filter((n): n is A => (list as readonly string[]).includes(n))

const toRole = (s: ServerSide): Role => (s === "TENANT" ? "임차인" : "임대인")

const transferFromBody = (t: SettlementTransferBody): Transfer => {
  const direction = t.direction === "REFUND" ? "반환" : "추가 송금"
  const by = toRole(t.recordedBySide)
  return {
    id: t.id,
    by,
    amount: t.amount,
    direction,
    at: t.paidOn,
    confirmed: t.response === "ACK",
    server: {
      versionNo: t.versionNo,
      response: t.response,
      byReceiver: by === receiverOf(direction),
      confirmedAmount: t.confirmedAmount,
      sameAsTransferId: t.sameAsTransferId,
      availableActions: only(TRANSFER_ACTIONS, t.availableActions),
    },
  }
}

// 서버 정산 → 화면 모델(호실의 정산 머리와 송금)
export const settlementFromDetail = (
  b: SettlementDetailBody,
): SettlementServerState => ({
  id: b.id,
  depositStatus: b.depositStatus,
  snapshotNo: b.snapshotNo,
  createdAt: b.createdAt,
  unansweredForMe: b.unansweredForMe,
  availableActions: only(SETTLEMENT_ACTIONS, b.availableActions),
  transfers: (b.transfers ?? []).map(transferFromBody),
  confirmedRefund: b.confirmedRefund ?? 0,
  confirmedExtra: b.confirmedExtra ?? 0,
})

// 받은 본문이 정산 모양인지(다르면 화면 모델로 바꾸지 않고 앞서 받은 정산을 둬요)
export const isSettlementBody = (b: unknown): b is SettlementDetailBody => {
  const v = b as Partial<SettlementDetailBody> | null
  return (
    typeof v?.id === "string" &&
    typeof v.snapshotNo === "number" &&
    typeof v.unansweredForMe === "number" &&
    Array.isArray(v.availableActions) &&
    (v.transfers === undefined || Array.isArray(v.transfers))
  )
}

// 호실의 정산(아직 열지 않았으면 404 NOT_FOUND)
export const getRoomSettlement = (request: HttpRequest, roomId: string) =>
  request<SettlementDetailBody>("GET", endpoints.openSettlement(roomId))

// 정산 상세
export const getSettlement = (request: HttpRequest, id: string) =>
  request<SettlementDetailBody>("GET", endpoints.settlement(id))
