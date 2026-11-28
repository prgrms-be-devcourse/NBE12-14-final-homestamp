import type { DemoState, RecordTarget, Role, Room } from "@/data/demo"
import type { Session } from "./types"

// 호실 기록 API: 화면이 읽는 기록과, 기록을 바꾸는 요청이 모두 이곳을 지나요.
// 뒤에는 목업(api/mock/records.ts) 또는 HTTP(api/http-records.ts)가 들어가요.

// 상태를 바꾸는 요청(06 4-0: 모두 POST). 주소는 api/endpoints.ts에서 만들어요.
export interface ApiRequest {
  path: string
  body?: unknown
  // 함께 올릴 사진(화면의 data URL). HTTP는 보내기 전에 호실 업로드 표로 올리고(06 4-5, api/photo-uploads)
  // 받은 표를 본문의 uploadTickets로 넣어요. 목업은 보내지 않아 쓰지 않아요
  photos?: readonly string[]
}
// 06 4장에 아직 주소가 없는 행동. 서버를 붙이기 전에 팀이 주소를 정해야 해요(HTTP에서는 보내지 않고 안내해요).
export interface MissingApi {
  missing: string
}
// 서버가 조회할 때 스스로 남기는 기록(링크 열람 등). HTTP에서는 보낼 것이 없어요.
export interface NoRequest {
  none: string
}
// 사진 여러 장처럼 한 번에 여러 요청을 보내는 행동은 목록으로 줘요(차례로 보내요).
export type RecordRequest = ApiRequest | ApiRequest[] | MissingApi | NoRequest
export const isMissing = (r: RecordRequest): r is MissingApi =>
  !Array.isArray(r) && "missing" in r
export const isNone = (r: RecordRequest): r is NoRequest =>
  !Array.isArray(r) && "none" in r

// 목업이 서버 대신 행동 기록·상대 알림을 남길 때 쓰는 값(어느 호실, 알릴지, 누구만 볼지, 어떤 기록인지)
export interface ActionOptions {
  notify?: boolean
  roomId: string
  connecting?: boolean
  privateTo?: Role
  target?: RecordTarget
}

// 기록 하나를 바꾸는 요청. HTTP는 request만 보내고, 나머지는 목업이 서버 대신 기록을 바꿀 때만 써요.
export interface RecordChange {
  request: RecordRequest
  role: Role
  text: string
  update: (d: DemoState) => void
  options: ActionOptions
}

export interface RecordsApi {
  // 화면이 읽는 기록(목업은 저장소, HTTP는 서버에서 받아 둔 것)
  subscribe(listener: () => void): () => void
  getSnapshot(): DemoState
  // 서버에서 기록을 다시 받아요(목업은 할 일이 없어요)
  load(s: Session): Promise<void>
  // 호실 하나를 다시 받아요(호실에 들어갈 때 상세의 입주일·availableActions를 새로 받아요. 목업은 할 일이 없어요)
  loadRoom(s: Session, roomId: string): Promise<void>
  // 기록을 바꿔요. 목업은 바로 바꾸고, HTTP는 요청을 보낸 뒤 다시 받아요.
  // 만들기 요청이면 서버가 만든 ID를 돌려줘요(06 4-0: 201 + 만든 ID). 목업은 화면이 정한 ID를 그대로 써요.
  send(s: Session, change: RecordChange): Promise<{ id?: string }>
}

// 요청 만들기 도우미: post(주소, 본문), 06에 주소가 없으면 notInSpec(무엇인지)
export const post = (path: string, body?: unknown): ApiRequest => ({
  path,
  body,
})
// 사진을 함께 보내는 요청: postWithPhotos(주소, 본문, 사진). 본문에는 uploadTickets를 넣지 않아요
export const postWithPhotos = (
  path: string,
  body: Record<string, unknown>,
  photos: readonly string[],
): ApiRequest => ({ path, body, photos })
export const notInSpec = (what: string): MissingApi => ({ missing: what })
export const noRequest = (why: string): NoRequest => ({ none: why })
// 요청 본문의 호실 쪽 값(06 4-3 side, 05 party.side). 화면의 임차인·임대인을 서버 값으로 바꿔요
export const roomSide = (r: Role): "TENANT" | "LANDLORD" =>
  r === "임차인" ? "TENANT" : "LANDLORD"
// 목업에는 점검·정산의 서버 ID가 없어 호실 ID로 대신해요(목업은 주소로 보내지 않아요).
// 아직 서버에 연결하지 않은 점검 행동(부위 사진)도 이 자리표를 써요.
export const inspectionIdOf = (roomId: string, kind: "in" | "out") =>
  `${roomId}:${kind}`
// 점검 주소에 쓸 ID. 서버에서 받은 점검이면 서버 점검 ID(06 4-7, #357), 목업이면 자리표예요.
export const inspectionIdIn = (room: Room, kind: "in" | "out") =>
  (kind === "in" ? room.moveIn : room.moveOut)?.server?.id ??
  inspectionIdOf(room.id, kind)
export const settlementIdOf = (roomId: string) => roomId
// 사진도 화면 모델에서는 기록 안의 자리(b:0, a:기록:0)로만 알아서, 서버의 사진 ID가 생기기 전까지 이것으로 대신해요.
export const photoIdOf = (recordId: string, key: string) => `${recordId}:${key}`
// 부담 제안(돈 안건)도 지금 화면 모델에 ID가 없어 '그 기록의 지금 안'으로 대신해요.
// which: 지금 안(current), 합의 뒤 바꾸자는 안(change), 정산 항목 빼기 제안(exclude)
export const proposalIdOf = (
  ownerId: string,
  which: "current" | "change" | "exclude" = "current",
) => `${ownerId}:${which}-proposal`
