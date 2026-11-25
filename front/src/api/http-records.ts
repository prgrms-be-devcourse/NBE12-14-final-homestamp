import { notConnected } from "./connected"
import { emptyState, type DemoState, type Notice, type Room } from "@/data/demo"
import { endpoints } from "./endpoints"
import { ApiError } from "./errors"
import type { HttpRequest } from "./http-client"
import {
  getRoomDetail,
  isNotFound,
  listAllRooms,
  roomFromDetail,
  roomFromSummary,
} from "./http-rooms"
import { isMissing, isNone, type RecordsApi } from "./records"
import type { Session } from "./types"

// HTTP 기록 API: 서버에서 받은 기록을 화면 모델로 바꿔 두고, 기록을 바꾸는 요청은 POST로 보낸 뒤 다시 받아요.
// 호실은 http-rooms가 서버 모양(06 4-3)을 화면 모델로 바꿔요. 나머지는 연결할 때 변환 함수를 서버 모양에 맞춰 바꿔요.
const toNotices = (body: unknown) => body as Notice[]
// 아직 연결 전인 목록은 빈 목록으로 두고, 다른 목록(호실)은 그대로 받아요
const unlessNotConnected =
  <T>(fallback: T) =>
  (e: unknown) => {
    if (e instanceof ApiError && e.code === "NOT_CONNECTED") return fallback
    throw e
  }

export function createHttpRecords(request: HttpRequest): RecordsApi {
  let state: DemoState = emptyState()
  const listeners = new Set<() => void>()
  const emit = () => listeners.forEach((l) => l())
  // 상세를 받은 차례. load가 기다리는 사이 loadRoom이 더 새 상세를 받았으면 load가 덮어쓰지 않게 해요
  let tick = 0
  const freshAt = new Map<string, number>()
  // 상세를 받아 화면 모델로 바꿔요. 남의 호실·없는 호실(404)은 목록에서 빼요(ROOM-2a).
  // 다른 오류(500·연결 끊김)면 목록 값은 두고 오류를 함께 돌려줘요. 부른 쪽이 화면에 알려요(버튼이 조용히 사라지지 않게).
  const detailOf = async (
    s: Session,
    room: Room,
  ): Promise<{ room: Room | null; error?: unknown }> => {
    try {
      const b = await getRoomDetail(request, room.id)
      return { room: roomFromDetail(b, s.accountId, room) }
    } catch (e) {
      if (isNotFound(e)) return { room: null }
      return { room, error: e }
    }
  }
  const load = async (s: Session) => {
    const startedAt = ++tick
    // 06 4장의 목록 조회로 받을 수 있는 것: 내 호실, 알림.
    // 호실 화면 칸(입주일·만든 측·행동)은 상세에만 있어 호실마다 상세도 받아요.
    // 수리 건 목록·점검·부위 사진·정산·경과는 호실마다 받아야 해서, 서버 응답 모양이 정해지면 여기에서 채워요.
    const [summaries, notices] = await Promise.all([
      listAllRooms(request),
      request<unknown>("GET", endpoints.notifications)
        .then(toNotices)
        .catch(unlessNotConnected<Notice[]>([])),
    ])
    const before = new Map(state.rooms.map((r) => [r.id, r]))
    const listed = summaries.map((b) =>
      roomFromSummary(b, s.accountId, before.get(b.id)),
    )
    const results = await Promise.all(listed.map((r) => detailOf(s, r)))
    const now = new Map(state.rooms.map((r) => [r.id, r]))
    const rooms = results
      .map(({ room }) =>
        room && (freshAt.get(room.id) ?? 0) > startedAt
          ? (now.get(room.id) ?? null)
          : room,
      )
      .filter((r): r is Room => r !== null)
    state = { ...state, rooms, notices }
    emit()
    const failed = results.find((r) => r.error)
    if (failed) throw failed.error
  }
  return {
    subscribe: (l) => {
      listeners.add(l)
      return () => {
        listeners.delete(l)
      }
    },
    getSnapshot: () => state,
    load,
    loadRoom: async (s, roomId) => {
      const room = state.rooms.find((r) => r.id === roomId)
      if (!room) return
      const { room: next, error } = await detailOf(s, room)
      if (error) throw error
      freshAt.set(roomId, ++tick)
      state = {
        ...state,
        rooms: next
          ? state.rooms.map((r) => (r.id === roomId ? next : r))
          : state.rooms.filter((r) => r.id !== roomId),
      }
      emit()
    },
    send: async (s, c) => {
      if (isNone(c.request)) return {}
      if (isMissing(c.request)) throw notConnected()
      const list = Array.isArray(c.request) ? c.request : [c.request]
      let made: { id?: string } | undefined
      for (const r of list)
        made = await request<{ id?: string } | undefined>("POST", r.path, {
          body: r.body,
        })
      // 보내기는 이미 성공했어요. 다시 받기에서 상세 하나가 실패해도 실패로 돌려주지 않아요
      // (같은 기록을 다시 보내 두 번 만들지 않게). 상세는 호실에 들어갈 때 다시 받고(loadRoom), 그때 실패하면 알려요.
      await load(s).catch(() => {})
      return { id: made?.id }
    },
  }
}
