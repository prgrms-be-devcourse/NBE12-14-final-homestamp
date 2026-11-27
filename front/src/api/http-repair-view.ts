import type { Ack, CauseClaim, Repair, RepairStatus, Role } from "@/data/demo"
import type { RepairAction } from "./actions"
import type { RepairCaseView } from "./repair-cases"
import { seoulDate } from "./http-rooms"
import { ERASED_PHOTO, UNAVAILABLE_PHOTO } from "@/lib/photos"

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
  photos: ServerPhotoCard[]
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
  photos: ServerPhotoCard[]
  causeNotes: ServerCauseNote[]
  availableActions: string[]
}

// 사진 카드(06 4-5 PhotoCard). 주소는 없고 POST /api/photos/view-urls로 따로 받아요(#366)
export interface ServerPhotoCard {
  id: string
  stage: "BEFORE" | "AFTER" | "EVIDENCE" | "BASIS" | "PROGRESS" | string
  uploadedBy: ServerSide | null
  sortOrder: number
  createdAt: string
  erased: { side: ServerSide | null; at: string; reason: string } | null
}
// 사진 ID → 썸네일 주소. 지운 사진·주소를 받지 못한 사진은 정해진 표시로 그려요(불변식 16, 대체 그림 파일 없음)
export type PhotoUrls = ReadonlyMap<string, string>
export const photoSrc = (card: ServerPhotoCard, urls: PhotoUrls) =>
  card.erased ? ERASED_PHOTO : (urls.get(card.id) ?? UNAVAILABLE_PHOTO)
// 카드 사진을 단계별로 나눠요. 수리 전 사진 칸에는 등록 사진과 보충 사진(EVIDENCE)을, 수리 후 칸에는 AFTER를 둬요
const bySort = (a: ServerPhotoCard, b: ServerPhotoCard) =>
  a.sortOrder - b.sortOrder
function photosOf(cards: readonly ServerPhotoCard[], urls: PhotoUrls) {
  const of = (...stages: string[]) =>
    cards
      .filter((c) => stages.includes(c.stage))
      .sort(bySort)
      .map((c) => photoSrc(c, urls))
  return { before: of("BEFORE", "EVIDENCE"), after: of("AFTER") }
}
// 상세 응답에서 주소를 받을 사진 ID(지운 사진은 주소를 받지 않아요)
export const photoIdsOf = (b: RepairCaseDetailBody) =>
  [
    ...b.photos,
    ...b.causeNotes.flatMap(function all(n): ServerPhotoCard[] {
      return [...n.photos, ...n.previous.flatMap(all)]
    }),
  ]
    .filter((c) => !c.erased)
    .map((c) => c.id)

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
function causesOf(
  notes: ServerCauseNote[],
  urls: PhotoUrls = new Map(),
): CauseClaim[] {
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
    ...(n.photos.length
      ? { photos: [...n.photos].sort(bySort).map((c) => photoSrc(c, urls)) }
      : {}),
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
    // 목록에는 사진이 없어요. 상세는 repairFromDetail이 사진 카드와 받은 주소로 채워요(#366)
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

// urls: 사진 썸네일 주소(http-repair-cases가 view-urls로 받아 넘겨요). 없으면 사진은 "불러오지 못함" 표시예요
export function repairFromDetail(
  b: RepairCaseDetailBody,
  urls: PhotoUrls = new Map(),
): RepairCaseView {
  const photos = photosOf(b.photos, urls)
  const repair: Repair = {
    ...repairBase(b, b.roomRecordId),
    description: b.content.description ?? "",
    causes: causesOf(b.causeNotes, urls),
    photos: photos.before,
    ...(photos.after.length ? { afterPhotos: photos.after } : {}),
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
