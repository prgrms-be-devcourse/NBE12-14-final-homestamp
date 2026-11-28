import type { NewRoomPart, RoomPart, RoomPartList } from "@/lib/room-parts"
import type { Session } from "./types"

// 호실 부위 API(06 4-3 `/api/rooms/{id}/parts`). 화면은 이 인터페이스만 불러요.
// 뒤에는 목업(api/mock/parts.ts) 또는 HTTP(api/http-parts.ts)가 들어가요.
// 바꾸는 요청(더하기·숨김·다시 보이기·켜고 끔)을 누를 수 있는지는 호실 상세의 availableActions `parts`로 정해요(#456).
export interface RoomPartsApi {
  // GET /api/rooms/{id}/parts: 숨긴 부위도 hidden: true로 들어 있어요(ROOM-3a). 고르는 칸에서 빼는 것은 화면이 해요
  list(s: Session, roomId: string): Promise<RoomPartList>
  // POST /api/rooms/{id}/parts: 고른 구역 맨 뒤에 더하고(ROOM-3c), 만든 부위를 돌려줘요
  add(s: Session, roomId: string, body: NewRoomPart): Promise<RoomPart>
  // POST .../parts/{partId}/hide: 숨겨요(ROOM-3a). 이미 숨겼으면 INVALID_STATE. 바꾼 뒤 부위를 돌려줘요
  hide(s: Session, roomId: string, partId: string): Promise<RoomPart>
  // POST .../parts/{partId}/show: 다시 보이게 해요(ROOM-3b). 이미 보이면 INVALID_STATE
  show(s: Session, roomId: string, partId: string): Promise<RoomPart>
  // POST .../parts/{partId}/toggle {enabled}: 원하는 값을 보내요(뒤집기 아님, ROOM-3d).
  // 이미 그 값이면 INVALID_STATE, 설비·가전이 아니면 VALIDATION_FAILED
  toggle(
    s: Session,
    roomId: string,
    partId: string,
    enabled: boolean,
  ): Promise<RoomPart>
}
