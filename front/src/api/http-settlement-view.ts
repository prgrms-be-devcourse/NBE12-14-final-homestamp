import type { SettlementServerState } from "@/data/demo"
import type { SettlementAction } from "./actions"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"

// 정산 상세(GET /api/settlements/{id}, GET /api/rooms/{id}/settlement, 06 4-8)의 서버 모양. 칸 이름은 백엔드 응답과 같아요.
// 지금 서버가 주는 칸은 정산 머리뿐이에요(#442). 보증금 금액·항목·송금은 그 기능을 연결할 때 칸을 더해요.
export interface SettlementDetailBody {
  id: string
  roomRecordId: string
  mySide: "TENANT" | "LANDLORD"
  depositStatus: SettlementServerState["depositStatus"]
  // 정산 모습 번호(05 settlement.snapshot_no). 끝내기 확인 때 seenVersionNo로 돌려보내요(SET-5e)
  snapshotNo: number
  createdAt: string
  // 내가 아직 답하지 않은 확인 요청 수(SET-5g, 끝내기 확인 화면용)
  unansweredForMe: number
  availableActions: string[]
}

// 서버가 주는 행동 이름은 API 끝 단어와 같아요(06 4-0). 화면에 버튼이 있고 주소를 연결한 것만 남겨요.
// 보증금·항목·송금은 요청 본문이 아직 서버와 맞지 않아(#442 범위 밖) 빼요
const SETTLEMENT_ACTIONS: readonly SettlementAction[] = [
  "finish",
  "finish-confirm",
  "finish-not-yet",
  "finish-alone",
]
const only = <A extends string>(list: readonly A[], names: readonly string[]) =>
  names.filter((n): n is A => (list as readonly string[]).includes(n))

// 서버 정산 → 화면 모델(호실의 정산 머리)
export const settlementFromDetail = (
  b: SettlementDetailBody,
): SettlementServerState => ({
  id: b.id,
  depositStatus: b.depositStatus,
  snapshotNo: b.snapshotNo,
  createdAt: b.createdAt,
  unansweredForMe: b.unansweredForMe,
  availableActions: only(SETTLEMENT_ACTIONS, b.availableActions),
})

// 받은 본문이 정산 모양인지(다르면 화면 모델로 바꾸지 않고 앞서 받은 정산을 둬요)
export const isSettlementBody = (b: unknown): b is SettlementDetailBody => {
  const v = b as Partial<SettlementDetailBody> | null
  return (
    typeof v?.id === "string" &&
    typeof v.snapshotNo === "number" &&
    typeof v.unansweredForMe === "number" &&
    Array.isArray(v.availableActions)
  )
}

// 호실의 정산(아직 열지 않았으면 404 NOT_FOUND)
export const getRoomSettlement = (request: HttpRequest, roomId: string) =>
  request<SettlementDetailBody>("GET", endpoints.openSettlement(roomId))

// 정산 상세
export const getSettlement = (request: HttpRequest, id: string) =>
  request<SettlementDetailBody>("GET", endpoints.settlement(id))
