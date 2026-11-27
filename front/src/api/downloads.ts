import { notConnected } from "./connected"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"

// 내려받기(S-19, DL-1)에 쓰는 서버 모양. 칸 이름은 백엔드 RoomDownload·ViewUrlsResponse와 같아요(06 4장, 4-5).
type Side = "TENANT" | "LANDLORD"
interface Target {
  kind: string
  id: string
}
// 기록.json 안의 사진 한 장. 지운 사진은 file이 null이고 erased에 흔적이 있어요(불변식 16)
export interface RecordPhotoBody {
  photoId: string
  file: string | null
  sha256: string
  uploadedBy: Side | null
  target: Target | null
  createdAt: string
  erased: { side: Side | null; at: string } | null
}
// 기록.json 내용 그대로. 화면은 고치지 않고 그대로 파일로 써요
export interface RecordFileBody {
  format: string
  exportedAt: string
  notice: string
  mySide: Side
  roomStatus: string
  actions: unknown[]
  photos: RecordPhotoBody[]
}
// GET /api/rooms/{id}/download
export interface RoomDownloadBody {
  record: RecordFileBody
  // 받을 사진 ID를 50장씩 묶은 것(지운 사진 빠짐)
  photoBatches: string[][]
  photoCount: number
}
// POST /api/photos/view-urls 한 줄. 받는 도중 지워진 사진은 url 대신 erased가 와요
export interface PhotoUrlBody {
  photoId: string
  url: string | null
  expiresAt: string | null
  erased: { side: Side | null; at: string; reason?: string } | null
}

export interface DownloadsApi {
  // 내려받기 목록(기록.json 내용 + 사진 묶음)
  get(roomId: string): Promise<RoomDownloadBody>
  // 한 묶음(1~50장)의 원본 5분 주소. 요청한 순서대로 와요
  originalUrls(photoIds: readonly string[]): Promise<PhotoUrlBody[]>
}

export function createHttpDownloads(request: HttpRequest): DownloadsApi {
  return {
    get: (roomId) =>
      request<RoomDownloadBody>("GET", endpoints.roomDownload(roomId)),
    // 조회라 멱등 키를 붙이지 않아요(06 4-5: Idempotency-Key 대상 아님)
    originalUrls: async (photoIds) =>
      (
        await request<{ items: PhotoUrlBody[] }>(
          "POST",
          endpoints.photoViewUrls,
          { body: { photoIds, variant: "ORIGINAL" }, idempotent: false },
        )
      ).items,
  }
}

// 목업에는 서버 기록이 없어 내려받기를 만들지 않아요(화면 확인은 HTTP 모드로)
export const mockDownloads: DownloadsApi = {
  get: () => Promise.reject(notConnected()),
  originalUrls: () => Promise.reject(notConnected()),
}
