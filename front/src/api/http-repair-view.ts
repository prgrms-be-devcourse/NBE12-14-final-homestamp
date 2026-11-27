import type {
  Ack,
  CauseClaim,
  ProgressNote,
  Repair,
  RepairStatus,
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

// 진행 상황(06 4-6 progressNotes, REP-6)
interface ServerProgressNote {
  id: string
  side: ServerSide
  text: string
  asksCheck: boolean
  createdAt: string
  photos: unknown[]
  response: { answer: AckState; reason: string | null; at: string } | null
  canRespond: boolean
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
  progressNotes: ServerProgressNote[]
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

// 호실의 수리 건 목록(GET /api/rooms/{id}/repair-cases, 06 4-6)의 서버 모양. 칸 이름은 백엔드 RepairCaseList와 같아요.
// 열린 응답 요청(waiting)은 응답 요청 조회 포트(#266) 전이라 지금은 늘 빈 목록이에요(#283)
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
  waiting: unknown[]
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

const PROGRESS_ANSWER: Partial<
  Record<AckState, NonNullable<ProgressNote["response"]>["answer"]>
> = { ACK: "맞아요", NEED_MORE: "보류", DISPUTE: "이견" }
// 진행 상황: 남긴 순서. 답은 진행 상황을 남긴 측의 상대가 해요
function progressNotesOf(notes: readonly ServerProgressNote[] = []) {
  return notes.map((n): ProgressNote => {
    const answer = n.response ? PROGRESS_ANSWER[n.response.answer] : undefined
    return {
      id: n.id,
      by: toRole(n.side),
      text: n.text,
      asksCheck: n.asksCheck,
      at: n.createdAt,
      photoCount: n.photos.length,
      ...(n.response && answer
        ? {
            response: {
              by: otherRole(n.side),
              answer,
              reason: n.response.reason ?? "",
              at: n.response.at,
            },
          }
        : {}),
      canRespond: n.canRespond,
    }
  })
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
  "ask-progress",
  "progress-notes",
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
  return { ...repairBase(b, roomId), lastActionAt: b.lastActionAt }
}

export function repairFromDetail(b: RepairCaseDetailBody): RepairCaseView {
  const repair: Repair = {
    ...repairBase(b, b.roomRecordId),
    description: b.content.description ?? "",
    causes: causesOf(b.causeNotes),
    progressNotes: progressNotesOf(b.progressNotes),
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
