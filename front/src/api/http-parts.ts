import type { NewRoomPart, RoomPart, RoomPartList } from "@/lib/room-parts"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import type { RoomPartsApi } from "./parts"

// 호실 부위 API의 HTTP 구현(06 4-3). 응답 칸 이름이 화면 모양과 같아(RoomPartList·RoomPartView) 그대로 써요.
// 수리 건 상세가 roomPartId로 구역을 찾을 때도 이 목록을 써요(partsOf).
// 받아 두지 않고 부를 때마다 받아요: 목록은 보는 사람 기준이고(공유를 그만둔 뒤 asOf), 상대가 숨기거나 끈 것도 바로 보여야 해서요.
// API 값은 앱이 켜져 있는 동안 하나라 계정이 바뀌어도 이전 계정의 목록이 남지 않아요.
export interface HttpRoomParts extends RoomPartsApi {
  partsOf(roomId: string): Promise<RoomPart[]>
}
export function createHttpRoomParts(request: HttpRequest): HttpRoomParts {
  const load = async (roomId: string): Promise<RoomPartList> => {
    const b = await request<RoomPartList>("GET", endpoints.roomParts(roomId))
    return { asOf: b.asOf ?? null, parts: b.parts }
  }
  return {
    list: (_s, roomId) => load(roomId),
    add: (_s, roomId, body: NewRoomPart) =>
      request<RoomPart>("POST", endpoints.roomParts(roomId), {
        body: { zone: body.zone, name: body.name },
      }),
    hide: (_s, roomId, partId) =>
      request<RoomPart>("POST", endpoints.roomPart(roomId, partId, "hide")),
    show: (_s, roomId, partId) =>
      request<RoomPart>("POST", endpoints.roomPart(roomId, partId, "show")),
    toggle: (_s, roomId, partId, enabled) =>
      request<RoomPart>("POST", endpoints.roomPart(roomId, partId, "toggle"), {
        body: { enabled },
      }),
    partsOf: async (roomId) => (await load(roomId)).parts,
  }
}
