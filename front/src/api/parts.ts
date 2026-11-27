import type { NewRoomPart, RoomPart, RoomPartList } from "@/lib/room-parts"
import type { Session } from "./types"

// 호실 부위 API(06 4-3 `/api/rooms/{id}/parts`). 화면은 이 인터페이스만 불러요.
// 뒤에는 목업(api/mock/parts.ts) 또는 HTTP(api/http-parts.ts)가 들어가요.
// 숨김·다시 보이기·켜고 끔은 그 화면이 생길 때 더해요.
export interface RoomPartsApi {
  // GET /api/rooms/{id}/parts: 숨긴 부위도 hidden: true로 들어 있어요(ROOM-3a). 고르는 칸에서 빼는 것은 화면이 해요
  list(s: Session, roomId: string): Promise<RoomPartList>
  // POST /api/rooms/{id}/parts: 고른 구역 맨 뒤에 더하고(ROOM-3c), 만든 부위를 돌려줘요
  add(s: Session, roomId: string, body: NewRoomPart): Promise<RoomPart>
}
