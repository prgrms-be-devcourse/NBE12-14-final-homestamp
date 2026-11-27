import type { Ack, Inspection, InspectionSession, Role } from "@/data/demo"
import type { InspectionAction, InspectionItemAction } from "./actions"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import { seoulDate } from "./http-rooms"

// 점검 상세(GET /api/inspections/{id}, 06 4-7)의 서버 모양. 칸 이름은 백엔드 InspectionDetail과 같아요.
// 지금은 입주 점검만 연결해요(#357). 퇴거 점검의 나란히 보기(sideBySide)는 받지만 쓰지 않아요.
type ServerSide = "TENANT" | "LANDLORD"
type AckState = "PENDING" | "ACK" | "NEED_MORE" | "DISPUTE" | "UNCONFIRMED"
type InspectionStatus = "DRAFT" | "REQUESTED" | "LOCKED"

export interface InspectionItemBody {
  id: string
  status: AckState
  addedBy: ServerSide
  addedAfterLock: boolean
  addedAfterEnd: boolean
  createdAt: string
  version: {
    no: number
    roomPartId: string
    partName: string
    description: string
  }
  photos: unknown[]
  nextRerequestAt: string | null
  availableActions: string[]
}

export interface InspectionDetailBody {
  id: string
  roomRecordId: string
  kind: "MOVE_IN" | "MOVE_OUT"
  status: InspectionStatus
  mySide: ServerSide
  authorSide: ServerSide
  late: boolean
  lockedAt: string | null
  lockReason: "ALL_RESOLVED" | "NO_ITEMS" | null
  createdAt: string
  asOf: string | null
  items: InspectionItemBody[]
  counterpartCanRequestAt: string
  availableActions: string[]
}

// 호실 경과(GET /api/rooms/{id}/timeline, 06 4-3)에서 점검 ID를 찾는 데 쓰는 칸만
interface TimelineBody {
  items: {
    type: string
    target: { kind: string; id: string } | null
    payload: Record<string, unknown> | null
  }[]
}

const toRole = (s: ServerSide): Role => (s === "TENANT" ? "임차인" : "임대인")

const ACK: Record<AckState, Ack> = {
  PENDING: "확인 대기",
  ACK: "확인됨",
  NEED_MORE: "보충 요청",
  DISPUTE: "이견",
  UNCONFIRMED: "상대 미확인",
}

const STATUS: Record<InspectionStatus, InspectionSession["status"]> = {
  DRAFT: "작성 중",
  REQUESTED: "확인 요청",
  LOCKED: "잠김",
}

// 서버가 주는 행동 이름은 API 끝 단어와 같아요(06 4-0). 화면에 버튼이 있는 것만 남겨요.
const INSPECTION_ACTIONS: readonly InspectionAction[] = [
  "items",
  "request-confirm",
  "confirm-all",
]
const ITEM_ACTIONS: readonly InspectionItemAction[] = ["respond", "supplement"]
const only = <A extends string>(list: readonly A[], names: readonly string[]) =>
  names.filter((n): n is A => (list as readonly string[]).includes(n))

// 다시 확인 요청 간격(lib/reask.ts의 REASK_DAYS와 같은 7일). 서버는 다음 가능 시각을 주고, 화면은 마지막 보충 시각으로 세요
const REASK_MS = 7 * 86_400_000
const reaskedAtOf = (next: string | null) =>
  next ? new Date(Date.parse(next) - REASK_MS).toISOString() : undefined

// 이 호실의 입주 점검 ID. 점검을 ID로 찾는 API가 없어, 경과의 점검 시작 줄(START_INSPECTION, kind MOVE_IN)에서 찾아요.
// 경과는 보는 사람에게 보이는 범위라, 공유를 그만둔 뒤 상대가 시작한 점검은 나오지 않아요(점검 상세도 404).
export function moveInInspectionId(timeline: TimelineBody): string | null {
  const start = timeline.items.find(
    (i) =>
      i.type === "START_INSPECTION" &&
      i.target?.kind === "INSPECTION" &&
      i.payload?.kind === "MOVE_IN",
  )
  return start?.target?.id ?? null
}

// 서버 점검 → 화면 모델(호실의 점검 머리 + 항목들)
export function inspectionFromDetail(b: InspectionDetailBody): {
  session: InspectionSession
  items: Inspection[]
} {
  const kind = b.kind === "MOVE_IN" ? "in" : "out"
  // 하자 기록 없음: 확인 요청 뒤 기준 측이 쓴 항목이 하나도 없음. 확인하는 쪽(상대 측)이 답할 항목 수와 같고(06 4-7 confirm-all, #291),
  // 기준 측이 봐도 같은 값이에요(자기 항목 수, #357 리뷰). 상대가 더한 항목은 세지 않아요
  const baseItems = b.items.filter((i) => i.addedBy === b.authorSide)
  const session: InspectionSession = {
    author: toRole(b.authorSide),
    status: STATUS[b.status],
    startedAt: b.createdAt,
    noDefects: b.status !== "DRAFT" && baseItems.length === 0,
    lateOn: b.late ? seoulDate(b.createdAt) : undefined,
    server: {
      id: b.id,
      availableActions: only(INSPECTION_ACTIONS, b.availableActions),
      lockedAt: b.lockedAt,
      lockReason: b.lockReason,
      counterpartCanRequestAt: b.counterpartCanRequestAt,
      asOf: b.asOf,
    },
  }
  const items = b.items.map((i): Inspection => ({
    id: i.id,
    roomId: b.roomRecordId,
    kind,
    // 서버 항목에는 제목이 없어요(부위 + 설명, 03 H). 제목 자리에 부위 이름을 써요
    title: i.version.partName,
    zone: i.version.partName,
    description: i.version.description,
    // 사진 주소는 사진 창구(POST /api/photos/view-urls)로 따로 받아요. 이번 연결 범위 밖이라 비워 둬요(#295)
    photos: [],
    ack: ACK[i.status],
    author: toRole(i.addedBy),
    art: "",
    late: b.late,
    afterEnd: i.addedAfterEnd,
    // 작성 중에 가려지는 것은 기준 측 항목뿐이에요. 상대가 작성 중에 더한 항목은 더할 때 기준 측에게 보이고 바로 답해요(06 4-7, #286)
    publication:
      b.status === "DRAFT" && i.addedBy === b.authorSide
        ? "draft"
        : "requested",
    version: i.version.no,
    reaskedAt: reaskedAtOf(i.nextRerequestAt),
    // 부위 ID(version.roomPartId)는 공통 부위 모델(#356)이 항목의 roomPartId로 채워요
    server: {
      availableActions: only(ITEM_ACTIONS, i.availableActions),
      addedAfterLock: i.addedAfterLock,
    },
  }))
  return { session, items }
}

// 점검 상세(GET /api/inspections/{id})
export const getInspection = (request: HttpRequest, id: string) =>
  request<InspectionDetailBody>("GET", endpoints.inspection(id))
