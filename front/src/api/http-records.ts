import { notConnected } from "./connected"
import {
  emptyState,
  type DemoState,
  type Notice,
  type Repair,
  type Room,
} from "@/data/demo"
import { listAllRepairs } from "./http-repair-cases"
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
  // 목록에 아직 없는 호실(before 없음)은 상세만으로 만들어요.
  // 호실의 수리 건도 함께 받아요(GET /api/rooms/{id}/repair-cases, 06 4-6). 실패하면 그 호실의 수리 건은 앞서 받은 것을 둬요(repairs 없음)
  const detailOf = async (
    s: Session,
    roomId: string,
    before?: Room,
  ): Promise<{ room: Room | null; repairs?: Repair[]; error?: unknown }> => {
    try {
      const [b, repairs] = await Promise.all([
        getRoomDetail(request, roomId),
        listAllRepairs(request, roomId),
      ])
      return { room: roomFromDetail(b, s.accountId, before), repairs }
    } catch (e) {
      if (isNotFound(e)) return { room: null, repairs: [] }
      return { room: before ?? null, error: e }
    }
  }
  // 받은 호실의 수리 건으로 바꿔요. 받지 못한 호실은 앞서 받은 것을 두고, 목록에서 빠진 호실의 수리 건은 지워요
  const mergeRepairs = (
    rooms: readonly Room[],
    fresh: ReadonlyMap<string, Repair[]>,
  ) => {
    const roomIds = new Set(rooms.map((r) => r.id))
    return [
      ...state.repairs.filter(
        (r) => roomIds.has(r.roomId) && !fresh.has(r.roomId),
      ),
      ...[...fresh.values()].flat(),
    ]
  }
  const load = async (s: Session) => {
    const startedAt = ++tick
    // 06 4장의 목록 조회로 받을 수 있는 것: 내 호실, 알림.
    // 호실 화면 칸(입주일·만든 측·행동)은 상세에만 있어 호실마다 상세도 받아요.
    // 수리 건 목록도 호실마다 상세와 함께 받아요(detailOf). 점검·부위 사진·정산·경과는 서버 응답 모양이 정해지면 여기에서 채워요.
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
    const results = await Promise.all(listed.map((r) => detailOf(s, r.id, r)))
    const now = new Map(state.rooms.map((r) => [r.id, r]))
    // 기다리는 사이 loadRoom이 더 새로 받은 호실은 호실·수리 건 모두 그 값을 둬요
    const newer = (room: Room | null) =>
      Boolean(room && (freshAt.get(room.id) ?? 0) > startedAt)
    const rooms = results
      .map(({ room }) => (newer(room) ? (now.get(room!.id) ?? null) : room))
      .filter((r): r is Room => r !== null)
    const fresh = new Map<string, Repair[]>()
    for (const { room, repairs } of results)
      if (room && repairs && !newer(room)) fresh.set(room.id, repairs)
    state = { ...state, rooms, repairs: mergeRepairs(rooms, fresh), notices }
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
    // 목록에 없는 호실도 상세를 받아 넣어요. 만든 뒤 목록 다시 받기가 실패해도 새 호실 화면이 열려요(#241 리뷰).
    // 남의 호실·없는 호실(404)이면 넣지 않아 '찾을 수 없는 기록'이 그대로 보여요.
    loadRoom: async (s, roomId) => {
      const room = state.rooms.find((r) => r.id === roomId)
      const { room: next, repairs, error } = await detailOf(s, roomId, room)
      if (error) throw error
      freshAt.set(roomId, ++tick)
      const rooms = !next
        ? state.rooms.filter((r) => r.id !== roomId)
        : room
          ? state.rooms.map((r) => (r.id === roomId ? next : r))
          : [next, ...state.rooms]
      state = {
        ...state,
        rooms,
        repairs: mergeRepairs(rooms, new Map([[roomId, repairs ?? []]])),
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
      // 보내기는 이미 성공했어요. 다시 받기(목록·상세)가 실패해도 실패로 돌려주지 않아요
      // (같은 기록을 다시 보내 두 번 만들지 않게). 새 호실은 그 화면에 들어갈 때 상세로 받고(loadRoom), 그때 실패하면 알려요.
      await load(s).catch(() => {})
      return { id: made?.id }
    },
  }
}
