import { emptyState, type DemoState, type Notice, type Room } from "@/data/demo"
import { ApiError } from "./errors"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import { isMissing, isNone, type RecordsApi } from "./records"

// HTTP 기록 API: 서버에서 받은 기록을 화면 모델로 바꿔 두고, 기록을 바꾸는 요청은 POST로 보낸 뒤 다시 받아요.
// 응답 본문 형식이 명세에 아직 없어, 백엔드가 생기면 아래 변환 함수만 서버 모양(OpenAPI 타입)에 맞춰 바꿔요.
const toRooms = (body: unknown) => body as Room[]
const toNotices = (body: unknown) => body as Notice[]

export function createHttpRecords(request: HttpRequest): RecordsApi {
  let state: DemoState = emptyState()
  const listeners = new Set<() => void>()
  const load = async () => {
    // 06 4장의 목록 조회로 받을 수 있는 것: 내 호실, 알림.
    // 수리 건 목록·점검·부위 사진·정산·경과는 호실마다 받아야 해서, 서버 응답 모양이 정해지면 여기에서 채워요.
    const [rooms, notices] = await Promise.all([
      request<unknown>("GET", endpoints.rooms).then(toRooms),
      request<unknown>("GET", endpoints.notifications).then(toNotices),
    ])
    state = { ...state, rooms, notices }
    listeners.forEach((l) => l())
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
    send: async (_s, c) => {
      if (isNone(c.request)) return {}
      if (isMissing(c.request))
        throw new ApiError({
          status: 0,
          code: "NOT_CONNECTED",
          detail: "아직 서버에 연결되지 않은 기능이에요.",
        })
      const list = Array.isArray(c.request) ? c.request : [c.request]
      let made: { id?: string } | undefined
      for (const r of list)
        made = await request<{ id?: string } | undefined>("POST", r.path, {
          body: r.body,
        })
      await load()
      return { id: made?.id }
    },
  }
}
