import type { RoomAction } from "@/api/actions"
import type { Role, Room } from "@/data/demo"
import { canEditNumberOnlyPhone } from "@/lib/workflows"

// 서버가 연결되기 전 Mock: GET /api/rooms/{id}의 availableActions를 서버 대신 계산해요.
// 서버가 연결되면 응답에 담긴 값을 그대로 쓰고 이 함수는 쓰지 않아요.
export function roomAvailableActions(
  room: Room,
  role: Role,
  viewer: { guest?: boolean; readOnly?: boolean } = {},
): RoomAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const living = room.status === "거주 중"
  const out: RoomAction[] = []
  const add = (a: RoomAction, when: boolean) => {
    if (when) out.push(a)
  }
  add("invite", room.connection !== "연결됨" && room.creator === role)
  add("counterpart-phone", canEditNumberOnlyPhone(room, role))
  add("repair-cases", living)
  // 거주 종료(ROOM-9): 세입자 쪽은 바로, 집주인 쪽은 요청(이미 요청했으면 답을 기다려요)
  add("move-out", living && !(role === "임대인" && room.endRequested))
  add(
    "move-out-confirm",
    living && Boolean(room.endRequested) && role === "임차인",
  )
  add(
    "move-out-reject",
    living && Boolean(room.endRequested) && role === "임차인",
  )
  add(
    "move-out-alone",
    living &&
      Boolean(room.endRequested) &&
      role === "임대인" &&
      room.endWait === "미확인",
  )
  return out
}
