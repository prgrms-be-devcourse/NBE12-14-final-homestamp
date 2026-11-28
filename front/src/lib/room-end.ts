import type { Role, Room } from "@/data/demo"

// S-06 거주 종료 카드(06 S-06 표, ROOM-9)에 보일 것. 버튼은 이 값이 아니라 서버 availableActions로만 정해요(06 4-0).
// 서버가 연결되면 호실 상세(GET /api/rooms/{id})의 endRequest·endedAt·endSoloReason으로, 그 전에는 목업 칸으로 만들어요.
export type RoomEndView =
  | { kind: "none" }
  // 세입자 쪽: 집주인이 이사했다고 남겼어요(요청을 연 날짜, 남은 시간은 dueAt이 있을 때만)
  | { kind: "asked"; openedAt?: string; dueAt?: string | null }
  // 집주인 쪽: 세입자의 답을 기다려요(N일째)
  | { kind: "waiting"; openedAt?: string; unconfirmed: boolean }
  // 세입자가 "아직 살고 있어요"로 답함(양쪽, 그 뒤 집주인은 다시 요청할 수 있어요)
  | { kind: "still-living"; reason?: string | null }
  // 거주 종료됨(양쪽, 공유를 그만둔 뒤에도 보여요)
  | { kind: "ended"; endedAt?: string | null; soloReason?: string | null }

const OPEN = new Set(["WAITING", "UNCONFIRMED"])

export function roomEndView(room: Room, role: Role): RoomEndView {
  const server = room.server
  if (server?.availableActions) {
    if (room.status !== "거주 중")
      return room.status === "거주 종료"
        ? {
            kind: "ended",
            endedAt: server.endedAt,
            soloReason: server.endSoloReason,
          }
        : { kind: "none" }
    const req = server.endRequest
    if (req && OPEN.has(req.status))
      return server.mySide === "TENANT"
        ? { kind: "asked", openedAt: req.openedAt, dueAt: req.dueAt }
        : {
            kind: "waiting",
            openedAt: req.openedAt,
            unconfirmed: req.status === "UNCONFIRMED",
          }
    if (req?.status === "ANSWERED" && req.rejectReason)
      return { kind: "still-living", reason: req.rejectReason }
    return { kind: "none" }
  }
  // 목업
  if (room.status === "거주 종료") return { kind: "ended" }
  if (room.status !== "거주 중" || !room.endRequested) return { kind: "none" }
  return role === "임차인"
    ? { kind: "asked" }
    : { kind: "waiting", unconfirmed: room.endWait === "미확인" }
}

// "○월 ○일"(한국 날짜)
export const monthDay = (iso: string) => {
  const parts = new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "numeric",
    day: "numeric",
  }).formatToParts(new Date(iso))
  const get = (t: string) => parts.find((p) => p.type === t)?.value
  return `${get("month")}월 ${get("day")}일`
}

// 연 날을 1일째로 세요(한국 날짜 기준)
export const dayCount = (iso: string, now: Date = new Date()) => {
  const day = (d: Date) =>
    Date.parse(d.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" }))
  return Math.max(
    1,
    Math.round((day(now) - day(new Date(iso))) / 86_400_000) + 1,
  )
}

// 남은 시간(기한이 지났으면 null). 기한이 없으면 보이지 않아요(NOTI-4b)
export const timeLeft = (dueAt: string, now: Date = new Date()) => {
  const ms = Date.parse(dueAt) - now.getTime()
  if (ms <= 0) return null
  const hours = Math.ceil(ms / 3_600_000)
  return hours >= 24
    ? `${Math.floor(hours / 24)}일 ${hours % 24}시간 남음`
    : `${hours}시간 남음`
}

// 혼자 남긴 거주 종료의 이유(MOVE_OUT payload solo_reason) 문구. 모르는 값은 이유를 붙이지 않아요
const SOLO_TEXT: Record<string, string> = {
  NO_COUNTERPART: "세입자 없이 남긴 거주 종료",
  NOT_CONNECTED: "세입자가 아직 연결하지 않았어요",
}
export const endSoloText = (reason?: string | null) =>
  reason ? SOLO_TEXT[reason] : undefined
