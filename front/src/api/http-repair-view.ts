import type {
  Ack,
  CauseClaim,
  Repair,
  RepairStatus,
  RepairWait,
  Role,
} from "@/data/demo"
import type { RepairAction } from "./actions"
import type { RepairCaseView } from "./repair-cases"
import { seoulDate } from "./http-rooms"

// 수리 건 상세(GET /api/repair-cases/{id}, 06 4-6)의 서버 모양. 칸 이름은 백엔드 RepairCaseDetail과 같아요.
type ServerSide = "TENANT" | "LANDLORD"
type AckState = "PENDING" | "ACK" | "NEED_MORE" | "DISPUTE" | "UNCONFIRMED"
type CauseBasis = "GUESS" | "SEEN" | "EXPERT" | "HEARD"

interface ServerCauseNote {
  id: string
  side: ServerSide
  text: string
  basis: CauseBasis
  expertName: string | null
  previousId: string | null
  createdAt: string
  response: { answer: AckState; reason: string | null; at: string } | null
  photos: unknown[]
  canRespond: boolean
  previous: ServerCauseNote[]
}

export interface RepairCaseDetailBody {
  id: string
  roomRecordId: string
  mySide: ServerSide
  registeredBy: ServerSide
  status: "NOT_STARTED" | "IN_PROGRESS" | "CLOSE_WAIT" | "DONE"
  closeRequestedBy: ServerSide | null
  closedBy: "BOTH" | ServerSide | null
  closeObjection: { side: ServerSide; reason: string; at: string } | null
  solo: boolean
  createdAt: string
  asOf: string | null
  content: {
    no: number
    roomPartId: string
    partName: string
    title: string
    description: string | null
  }
  defect: { ack: AckState; nextRerequestAt: string | null }
  money: { status: string }
  photos: unknown[]
  causeNotes: ServerCauseNote[]
  // 종료 확인 요청 중 가장 최근 것. 요청이 없었으면 null(06 4-6)
  closeRequest?: ServerCloseRequest | null
  availableActions: string[]
}

const toRole = (s: ServerSide): Role => (s === "TENANT" ? "임차인" : "임대인")
const otherRole = (s: ServerSide): Role =>
  s === "TENANT" ? "임대인" : "임차인"

const ACK: Record<AckState, Ack> = {
  PENDING: "확인 대기",
  ACK: "확인됨",
  NEED_MORE: "보충 요청",
  DISPUTE: "이견",
  UNCONFIRMED: "상대 미확인",
}

// 열린 응답 요청 하나(06 4-6 목록 waiting, 백엔드 RepairCaseList.Waiting). 금액 확인은 서버가 빼서 오지 않아요
type ServerSoloReason =
  | "UNCONFIRMED"
  | "OPTED_OUT"
  | "COUNTERPART_WITHDRAWN"
  | "SHARING_STOPPED"
  | "SMS_UNDELIVERED"
  | "NOT_CONNECTED"
  | "NO_COUNTERPART"
interface ServerWaiting {
  kind: string
  answeringSide: ServerSide
  status: "WAITING" | "UNCONFIRMED"
  dueAt: string | null
  soloReason: ServerSoloReason | null
}
interface ServerCloseRequest {
  status: "WAITING" | "ANSWERED" | "UNCONFIRMED" | "WITHDRAWN" | "CLOSED"
  dueAt: string | null
  soloReason: ServerSoloReason | null
}

// 요청 종류의 화면 이름('내 차례 · ○○'). lib/turn의 상태로 세는 이름과 같은 글자예요
const WAIT_KIND: Record<string, string> = {
  DEFECT_CONFIRM: "하자 확인",
  CLOSE_CONFIRM: "종료 확인",
  SITUATION_CHECK: "바뀐 상황 확인",
  PROGRESS_CHECK: "진행 상황 답하기",
  CORRECTION_CONFIRM: "고친 내용 확인",
  PROPOSAL: "비용 제안 응답",
}

// 확인 없음의 이유(03 4장 "'확인 없음' 화면 표시"). lib/repair-rules의 soloReasonText와 같은 말이에요
const SOLO_REASON_TEXT: Record<ServerSoloReason, string> = {
  UNCONFIRMED: "72시간 동안 답이 오지 않았어요",
  OPTED_OUT: "문자를 그만 받기로 했어요",
  COUNTERPART_WITHDRAWN: "탈퇴했어요",
  SHARING_STOPPED: "공유를 그만뒀어요",
  SMS_UNDELIVERED: "문자가 전달되지 않았어요",
  NOT_CONNECTED: "아직 연결되지 않았어요",
  NO_COUNTERPART: "아직 상대를 초대하기 전이에요",
}
const soloText = (r: ServerSoloReason | null) =>
  r ? (SOLO_REASON_TEXT[r] ?? undefined) : undefined

function waitOf(w: ServerWaiting): RepairWait {
  const unconfirmed = w.status === "UNCONFIRMED"
  return {
    // 모르는 종류가 와도 화면이 깨지지 않게 일반 이름으로 보여요
    kind: WAIT_KIND[w.kind] ?? "확인 요청",
    by: toRole(w.answeringSide),
    unconfirmed,
    ...(!unconfirmed && w.dueAt ? { dueAt: w.dueAt } : {}),
    ...(unconfirmed && soloText(w.soloReason)
      ? { reason: soloText(w.soloReason) }
      : {}),
  }
}

// 호실의 수리 건 목록(GET /api/rooms/{id}/repair-cases, 06 4-6)의 서버 모양. 칸 이름은 백엔드 RepairCaseList와 같아요.
// waiting은 두 측의 열린 응답 요청이 연 시각 순으로 와요. 공유를 그만둔 뒤(asOf 있음)에는 빈 목록이에요
export interface RepairCaseListItemBody {
  id: string
  registeredBy: ServerSide
  createdAt: string
  content: { no: number; partName: string; title: string }
  status: RepairCaseDetailBody["status"]
  lastActionAt: string
  closeRequestedBy: ServerSide | null
  closedBy: RepairCaseDetailBody["closedBy"]
  defect: { ack: AckState }
  money: { status: string }
  waiting: ServerWaiting[]
}
export interface RepairCasePageBody {
  asOf: string | null
  mySide: ServerSide
  items: RepairCaseListItemBody[]
  nextCursor: string | null
}

// 화면은 시작 전을 하자 인정에 따라 "확인 전"/"확인 완료"로 나눠 보여요. 종료 확인 대기는 "처리 중" + 요청한 측(closeBy)이에요.
function statusOf(b: {
  status: RepairCaseDetailBody["status"]
  defect: { ack: AckState }
}): RepairStatus {
  if (b.status === "DONE") return "처리 완료"
  if (b.status === "NOT_STARTED")
    return b.defect.ack === "ACK" ? "확인 완료" : "확인 전"
  return "처리 중"
}

// 근거 종류의 화면 이름(features/repair/cause-notes.tsx의 고르기와 같은 글자)
export const CAUSE_BASIS_LABEL: Record<CauseBasis, string> = {
  GUESS: "내 추측",
  SEEN: "직접 봤어요",
  EXPERT: "업체·전문가 소견",
  HEARD: "다른 사람에게 들었어요",
}

export function causeBasisCode(label: string): CauseBasis {
  const found = (Object.keys(CAUSE_BASIS_LABEL) as CauseBasis[]).find(
    (k) => CAUSE_BASIS_LABEL[k] === label,
  )
  if (!found) throw new Error(`모르는 근거 종류예요: ${label}`)
  return found
}

// 서버 날짜(UTC ISO)를 화면의 기록 날짜 글자("2026.11.04", 한국 시간)로
export const recordDate = (iso: string) => seoulDate(iso).replaceAll("-", ".")

// 원인 설명: 측마다 지금 설명 하나 + 고치기 전 설명(previous). 화면은 이전 설명을 supersededBy로 접어 보여요.
function causesOf(notes: ServerCauseNote[]): CauseClaim[] {
  const one = (n: ServerCauseNote, supersededBy?: string): CauseClaim => ({
    id: n.id,
    by: toRole(n.side),
    basis: CAUSE_BASIS_LABEL[n.basis],
    text: n.text,
    at: n.createdAt,
    responses: n.response
      ? [
          {
            by: otherRole(n.side),
            agree: n.response.answer === "ACK",
            reason: n.response.reason ?? "",
            at: n.response.at,
          },
        ]
      : [],
    company: n.expertName ?? undefined,
    supersededBy,
    supersedes: n.previousId ?? undefined,
    canRespond: n.canRespond,
  })
  return notes.flatMap((n) => [one(n), ...n.previous.map((p) => one(p, n.id))])
}

// 서버가 주는 행동 이름은 API 끝 단어와 같아요(06 4-0). 화면에 그 행동이 없는 것(진행 상황 묻기·남기기 등)은 아직 버튼이 없어 빼요.
const SCREEN_ACTIONS: readonly RepairAction[] = [
  "respond",
  "supplement",
  "start",
  "request-close",
  "confirm-close",
  "not-yet",
  "close-alone",
  "cause-notes",
]

// 서버에서만 받는 수리 건을 상세로 열 때, 응답이 오기 전 화면에 넘기는 빈 값(화면은 응답 전에는 그리지 않아요)
export function repairStub(id: string, roomId: string): Repair {
  return {
    id,
    roomId,
    title: "",
    zone: "",
    description: "",
    status: "확인 전",
    ack: "확인 대기",
    author: "임차인",
    photos: [],
    art: "",
    date: "",
  }
}

// 목록 줄과 상세가 같이 가진 칸(칸 이름이 같아요, 06 4-6) → 화면 모델
function repairBase(
  b: RepairCaseListItemBody | RepairCaseDetailBody,
  roomId: string,
): Repair {
  return {
    id: b.id,
    roomId,
    title: b.content.title,
    zone: b.content.partName,
    description: "",
    status: statusOf(b),
    ack: ACK[b.defect.ack],
    author: toRole(b.registeredBy),
    // 사진 주소는 사진 창구(POST /api/photos/view-urls, C)로 따로 받아요. 연결 전이라 비워 둬요
    photos: [],
    art: "",
    date: recordDate(b.createdAt),
    createdAt: b.createdAt,
    version: b.content.no,
    closeBy:
      b.status === "CLOSE_WAIT" && b.closeRequestedBy
        ? toRole(b.closeRequestedBy)
        : undefined,
    soloClosed:
      b.status === "DONE" && b.closedBy && b.closedBy !== "BOTH"
        ? toRole(b.closedBy)
        : undefined,
  }
}

// 목록 한 줄 → 화면 모델. 설명·원인 설명·사진은 목록에 없어 상세에서 받아요
export function repairFromListItem(
  b: RepairCaseListItemBody,
  roomId: string,
): Repair {
  return {
    ...repairBase(b, roomId),
    lastActionAt: b.lastActionAt,
    waiting: (b.waiting ?? []).map(waitOf),
  }
}

export function repairFromDetail(b: RepairCaseDetailBody): RepairCaseView {
  const repair: Repair = {
    ...repairBase(b, b.roomRecordId),
    description: b.content.description ?? "",
    causes: causesOf(b.causeNotes),
    // 종료 확인 대기에서 상대가 기한 안에 답하지 않았으면 "확인 없음(날짜부터) · 이유"(03 4장)
    ...(b.status === "CLOSE_WAIT" && b.closeRequest?.status === "UNCONFIRMED"
      ? {
          closeUnconfirmed: {
            ...(b.closeRequest.dueAt ? { since: b.closeRequest.dueAt } : {}),
            ...(soloText(b.closeRequest.soloReason)
              ? { reason: soloText(b.closeRequest.soloReason) }
              : {}),
          },
        }
      : {}),
    ...(b.closeObjection
      ? {
          closeObjection: {
            by: toRole(b.closeObjection.side),
            reason: b.closeObjection.reason,
            at: b.closeObjection.at,
          },
        }
      : {}),
  }
  const availableActions = b.availableActions.filter((a): a is RepairAction =>
    (SCREEN_ACTIONS as readonly string[]).includes(a),
  )
  return { repair, availableActions }
}
