import { ApiError } from "@/api/errors"
import type { RoomPartsApi } from "@/api/parts"
import type { Session } from "@/api/types"
import type { Room } from "@/data/demo"
import { checkNewPart, partLabel } from "@/lib/room-parts"
import { canAccessRoom, sideOf } from "@/lib/workflows"
import { ensureRoomParts, recordAction, type MockStore } from "./store"

// 서버가 연결되기 전 Mock: 호실 부위 API를 서버 대신 처리해요(06 4-3, ROOM-3).
// 부위는 호실을 만들 때 기본 목록을 복사해 호실마다 다른 ID를 가져요(room-form, 시작 데이터는 store).
// 그 밖에 부위 없이 생긴 호실은 처음 부를 때 한 번 복사해 둬요(서버의 ROOM-1a와 같은 결과).

export function createMockRoomParts(store: MockStore): RoomPartsApi {
  const fail = (status: number, code: string, detail: string): never => {
    throw new ApiError({ status, code, detail })
  }
  // 그 호실 회원만(남의 호실도 404, 06 4-3)
  const access = (s: Session, roomId: string) => {
    const room = store.getState().rooms.find((r) => r.id === roomId)
    const role = sideOf(room, s.accountId)
    if (!room || !role || !canAccessRoom(room, role))
      return fail(404, "NOT_FOUND", "찾을 수 없는 호실이에요.")
    return { room: room as Room, role }
  }
  const partsOf = (roomId: string) => {
    if (!store.getState().roomParts?.[roomId])
      store.setState((d) => {
        const next = { ...d }
        ensureRoomParts(next, roomId)
        return next
      })
    return store.getState().roomParts![roomId]
  }
  return {
    list: async (s, roomId) => {
      access(s, roomId)
      return { asOf: null, parts: structuredClone(partsOf(roomId)) }
    },
    add: async (s, roomId, body) => {
      const { room, role } = access(s, roomId)
      // 호실 단위 거절(06 4-10): 정산 완료·합류 전 기록
      if (room.status === "정산 완료")
        fail(409, "SETTLED", "정산이 끝난 호실은 바꿀 수 없어요.")
      if (room.preJoinOf)
        fail(409, "JOINED_RECORD", "합류 전 기록은 읽기만 할 수 있어요.")
      const b = checkNewPart(body)
      partsOf(roomId)
      const part = {
        id: crypto.randomUUID(),
        zone: b.zone,
        name: b.name,
        enabled: true,
        hidden: false,
        toggleable: false,
      }
      recordAction(
        store,
        role,
        `부위 추가 · ${partLabel(part)}`,
        (d) => {
          // 그 구역 맨 뒤에 보여요(목록은 구역 순서 다음 호실 안 순서)
          const list = ensureRoomParts(d, roomId)
          const last = list.findLastIndex((p) => p.zone === part.zone)
          list.splice(last < 0 ? list.length : last + 1, 0, part)
        },
        { roomId, notify: false },
      )
      return structuredClone(part)
    },
  }
}
