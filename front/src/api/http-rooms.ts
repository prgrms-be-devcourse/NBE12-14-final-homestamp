import type {
  PartyStatus,
  Role,
  Room,
  RoomEndRequest,
  RoomServerState,
} from "@/data/demo"
// ServerSide(TENANT·LANDLORD)로 바꾸기는 화면이 요청 본문을 만들 때 쓰므로 records.ts(roomSide)에 있어요
import { regionName } from "@/lib/region"
import { endpoints } from "./endpoints"
import { ApiError } from "./errors"
import type { HttpRequest } from "./http-client"

// 호실 API(06 4-3)의 서버 모양과 화면 모델(Room) 사이 변환. 서버 값은 room.server에 그대로 남겨요.
export type ServerSide = RoomServerState["mySide"]
export interface ServerRegion {
  code: string
  sido: string
  sigungu: string | null
  dong: string
}
// GET /api/rooms 한 줄(RoomSummary)
export interface RoomSummaryBody {
  id: string
  alias: string
  region: ServerRegion
  status: "LIVING" | "ENDED" | "SETTLED"
  mySide: ServerSide
  counterpartStatus: PartyStatus
  joinedAway: boolean
  nextStep: "SETTLE" | "READ_ONLY" | "NONE"
  createdAt: string
}
// GET /api/rooms/{id}(RoomDetail)
export interface RoomDetailBody {
  id: string
  alias: string
  region: ServerRegion
  moveInDate: string | null
  status: RoomSummaryBody["status"]
  mySide: ServerSide
  createdBySide: ServerSide
  myStatus: PartyStatus
  counterpartStatus: PartyStatus
  joinedAway: boolean
  asOf: string | null
  createdAt: string
  availableActions: string[]
  // 상대 자리에 저장된 번호의 가린 모양(010-****-5678). 내가 만든 측이고 번호가 남아 있을 때(번호만·초대됨·거절됨)만 와요(#317)
  counterpartMaskedPhone: string | null
  endRequest: RoomEndRequest | null
  endedAt: string | null
  endSoloReason: string | null
}
// 서버 행동 이름(API 끝 단어, 06 4-0) → 화면 행동 이름. 거주 종료 요청의 답만 화면에서 이름을 달리 써요
const ROOM_ACTION_NAMES: Record<string, string> = {
  confirm: "move-out-confirm",
  reject: "move-out-reject",
}
export const roomActionsOf = (names: string[]) =>
  names.map((n) => ROOM_ACTION_NAMES[n] ?? n)
const OPEN_END = new Set<RoomEndRequest["status"]>(["WAITING", "UNCONFIRMED"])
interface RoomPage {
  items: RoomSummaryBody[]
  nextCursor: string | null
}

// 서버 시각(UTC Instant) → 화면 날짜(한국 날짜, 목업의 todayKey와 같은 모양 YYYY-MM-DD)
export const seoulDate = (iso: string) =>
  new Date(iso).toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" })
export const toRole = (s: ServerSide): Role =>
  s === "TENANT" ? "임차인" : "임대인"
const STATUS: Record<RoomSummaryBody["status"], Room["status"]> = {
  LIVING: "거주 중",
  ENDED: "거주 종료",
  SETTLED: "정산 완료",
}
// 상대 자리 상태 → 화면의 연결 상태. 공유를 그만뒀거나 탈퇴해도 그 전 기록은 함께 본 호실이라 '연결됨'으로 두고
// (호실 화면에 들어갈 수 있어야 해요), 정상 연결과의 차이는 room.server·counterpartLeft로 가려요(connectionText).
export const connectionOf = (s: PartyStatus): Room["connection"] =>
  s === "EMPTY" || s === "NUMBER_ONLY"
    ? "연결 전"
    : s === "INVITED"
      ? "초대됨"
      : s === "REJECTED"
        ? "거절됨"
        : "연결됨"
// 아직 상대가 들어온 적 없는 호실은 내가 만든 호실이에요(초대는 만든 측만 해요, 06 4-3)
const notJoinedYet = (s: PartyStatus) => connectionOf(s) !== "연결됨"

// 목록 한 줄 → 화면 모델. 앞서 받은 상세 값(입주일·만든 측·행동)은 이어서 써요.
export function roomFromSummary(
  b: RoomSummaryBody,
  accountId: string,
  before?: Room,
): Room {
  const kept = before?.server
  const creator = kept?.createdBySide
    ? toRole(kept.createdBySide)
    : notJoinedYet(b.counterpartStatus)
      ? toRole(b.mySide)
      : undefined
  return {
    id: b.id,
    name: b.alias,
    address: regionName(b.region),
    since: before?.since ?? "",
    status: STATUS[b.status],
    connection: connectionOf(b.counterpartStatus),
    received: false,
    createdAt: seoulDate(b.createdAt),
    creator,
    members: { [toRole(b.mySide)]: accountId },
    counterpartLeft: b.counterpartStatus === "WITHDRAWN",
    server: {
      ...kept,
      mySide: b.mySide,
      counterpartStatus: b.counterpartStatus,
      joinedAway: b.joinedAway,
      nextStep: b.nextStep,
    },
  }
}

// 상세 → 화면 모델. 목록에만 있는 nextStep은 앞 값을 지켜요.
export function roomFromDetail(
  b: RoomDetailBody,
  accountId: string,
  before?: Room,
): Room {
  return {
    ...roomFromSummary(
      { ...b, nextStep: before?.server?.nextStep ?? "NONE" },
      accountId,
      before,
    ),
    since: b.moveInDate ?? "",
    creator: toRole(b.createdBySide),
    // 목업과 같은 칸도 맞춰 둬요(열린 요청만 참)
    endRequested: Boolean(b.endRequest && OPEN_END.has(b.endRequest.status)),
    server: {
      mySide: b.mySide,
      counterpartStatus: b.counterpartStatus,
      joinedAway: b.joinedAway,
      nextStep: before?.server?.nextStep,
      myStatus: b.myStatus,
      createdBySide: b.createdBySide,
      asOf: b.asOf,
      availableActions: roomActionsOf(b.availableActions),
      counterpartMaskedPhone: b.counterpartMaskedPhone ?? null,
      endRequest: b.endRequest,
      endedAt: b.endedAt,
      endSoloReason: b.endSoloReason,
    },
  }
}

// 한 쪽의 최대 크기(06 4-3 size 1~100)로 nextCursor가 없을 때까지 받아요
export const ROOM_PAGE_SIZE = 100
export async function listAllRooms(request: HttpRequest) {
  const items: RoomSummaryBody[] = []
  const seen = new Set<string>()
  let cursor: string | null = null
  do {
    const page: RoomPage = await request<RoomPage>(
      "GET",
      endpoints.roomPage(ROOM_PAGE_SIZE, cursor ?? undefined),
    )
    items.push(...page.items)
    cursor = page.nextCursor
    // 같은 커서가 다시 오면(서버 오류) 끝없이 받지 않고 멈춰요
    if (cursor && seen.has(cursor))
      throw new Error("호실 목록을 끝까지 받지 못했어요.")
    if (cursor) seen.add(cursor)
  } while (cursor)
  return items
}

export const getRoomDetail = (request: HttpRequest, roomId: string) =>
  request<RoomDetailBody>("GET", endpoints.room(roomId))

// 남의 호실·없는 호실은 404(ROOM-2a)
export const isNotFound = (e: unknown) =>
  e instanceof ApiError && e.status === 404
