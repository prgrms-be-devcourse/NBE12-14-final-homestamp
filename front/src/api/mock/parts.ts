import { ApiError } from "@/api/errors"
import type { RoomPartsApi } from "@/api/parts"
import type { Session } from "@/api/types"
import type { Role, Room } from "@/data/demo"
import { checkNewPart, partLabel, type RoomPart } from "@/lib/room-parts"
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
  // 바꾸는 요청의 호실 단위 거절(06 4-10): 정산 완료·합류 전 기록. 거주 종료 뒤에는 막지 않아요.
  // 공유를 그만둔 사람(SHARING_STOPPED)은 목업에 그 상태가 없어 서버만 막아요
  const editable = (s: Session, roomId: string) => {
    const at = access(s, roomId)
    if (at.room.status === "정산 완료")
      fail(409, "SETTLED", "정산이 끝난 호실은 바꿀 수 없어요.")
    if (at.room.preJoinOf)
      fail(409, "JOINED_RECORD", "합류 전 기록은 읽기만 할 수 있어요.")
    return at
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
  // 그 호실의 부위가 아니면 404(06 4-3)
  const partOf = (roomId: string, partId: string) =>
    partsOf(roomId).find((p) => p.id === partId) ??
    fail(404, "NOT_FOUND", "찾을 수 없는 부위예요.")
  // 부위 한 칸을 바꾸고 행동을 남겨요. 돌려주는 것은 바꾼 뒤 부위예요
  const change = (
    roomId: string,
    role: Role,
    part: RoomPart,
    text: string,
    patch: Partial<RoomPart>,
  ) => {
    recordAction(
      store,
      role,
      `${text} · ${partLabel(part)}`,
      (d) => {
        const p = ensureRoomParts(d, roomId).find((x) => x.id === part.id)!
        Object.assign(p, patch)
      },
      { roomId, notify: false },
    )
    return structuredClone(partOf(roomId, part.id))
  }
  return {
    list: async (s, roomId) => {
      access(s, roomId)
      return { asOf: null, parts: structuredClone(partsOf(roomId)) }
    },
    add: async (s, roomId, body) => {
      const { role } = editable(s, roomId)
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
    // 숨김(ROOM-3a)·다시 보이기(ROOM-3b): 이미 그 상태면 INVALID_STATE
    hide: async (s, roomId, partId) => {
      const { role } = editable(s, roomId)
      const part = partOf(roomId, partId)
      if (part.hidden) fail(409, "INVALID_STATE", "이미 숨긴 부위예요.")
      return change(roomId, role, part, "부위 숨기기", { hidden: true })
    },
    show: async (s, roomId, partId) => {
      const { role } = editable(s, roomId)
      const part = partOf(roomId, partId)
      if (!part.hidden) fail(409, "INVALID_STATE", "이미 보이는 부위예요.")
      return change(roomId, role, part, "부위 다시 보이기", { hidden: false })
    },
    // 켜고 끔(ROOM-3d): 원하는 값을 받아요. 기본 목록의 설비·가전만, 이미 그 값이면 INVALID_STATE. 숨김은 그대로예요
    toggle: async (s, roomId, partId, enabled) => {
      const { role } = editable(s, roomId)
      if (typeof enabled !== "boolean")
        fail(400, "VALIDATION_FAILED", "켤지 끌지 골라 주세요.")
      const part = partOf(roomId, partId)
      if (!part.toggleable)
        fail(400, "VALIDATION_FAILED", "설비·가전만 켜고 끌 수 있어요.")
      if (part.enabled === enabled)
        fail(
          409,
          "INVALID_STATE",
          enabled ? "이미 켜 둔 설비예요." : "이미 꺼 둔 설비예요.",
        )
      return change(roomId, role, part, enabled ? "설비 켜기" : "설비 끄기", {
        enabled,
      })
    },
  }
}
