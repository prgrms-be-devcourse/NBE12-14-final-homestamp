import { endpoints } from "./endpoints"
import { ApiError } from "./errors"
import type { HttpRequest } from "./http-client"

// 사진 올리기(06 4-5 POST /api/rooms/{id}/upload-tickets, PHO-1, 04 "사진" 표 #365).
// ① 화면이 다시 그린 사진(lib/photos, 긴 변 2048px JPEG, 위치 정보 빠짐)과 썸네일(긴 변 480px)의 크기·SHA-256을 만들고
// ② 업로드 표와 presigned POST를 받아 ③ 썸네일 먼저 S3에 직접 올린 뒤 ④ 행동 본문의 uploadTickets에 넣을 표를 돌려줘요.

// 서버 모양. 칸 이름은 백엔드 UploadTicketsRequest·UploadTicketsResponse와 같아요
export interface UploadFileBody {
  originalBytes: number
  thumbBytes: number
  originalSha256: string
  thumbSha256: string
  deviceTakenAt: string | null
}
export interface PresignedPostBody {
  url: string
  fields: Record<string, string>
  expiresAt: string
}
export interface UploadSlotBody {
  index: number
  ticket: string
  ticketExpiresAt: string
  original: PresignedPostBody
  thumb: PresignedPostBody
}

// 올릴 준비를 마친 사진 한 장(다시 그린 JPEG 두 개와 그 지문)
export interface PreparedPhoto {
  original: Blob
  thumb: Blob
  originalSha256: string
  thumbSha256: string
}

export interface PhotoUploadsApi {
  // 고른 사진(화면의 data URL)을 올리고, 같은 순서의 업로드 표를 돌려줘요. 사진이 없으면 부르지 않아도 돼요
  upload(roomId: string, photos: readonly string[]): Promise<string[]>
}

// 한 번에 받는 업로드 표(06 0-1 upload.batch.max)
export const UPLOAD_BATCH_MAX = 10
// 썸네일 긴 변(04 "사진" 올리기)
const THUMB_EDGE = 480

export const photoUploadFailed = () =>
  new ApiError({
    status: 0,
    code: "PHOTO_UPLOAD_FAILED",
    detail: "사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  })

// SHA-256을 S3 체크섬 모양(base64, 44자)으로(04 "지문값")
export async function sha256Base64(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer())
  let text = ""
  for (const b of new Uint8Array(digest)) text += String.fromCharCode(b)
  return btoa(text)
}

// data URL → Blob. 화면이 이미 다시 그린 원본은 그대로 올려요(다시 그리면 지문이 바뀌어요)
export async function blobOf(dataUrl: string): Promise<Blob> {
  return (await fetch(dataUrl)).blob()
}

const loadImage = (src: string) =>
  new Promise<HTMLImageElement>((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error("사진을 읽을 수 없어요."))
    img.src = src
  })

async function thumbOf(dataUrl: string): Promise<Blob> {
  const img = await loadImage(dataUrl)
  const scale = Math.min(1, THUMB_EDGE / Math.max(img.width, img.height))
  const canvas = document.createElement("canvas")
  canvas.width = Math.max(1, Math.round(img.width * scale))
  canvas.height = Math.max(1, Math.round(img.height * scale))
  canvas.getContext("2d")!.drawImage(img, 0, 0, canvas.width, canvas.height)
  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error("사진을 읽을 수 없어요."))),
      "image/jpeg",
      0.8,
    ),
  )
}

export async function preparePhoto(dataUrl: string): Promise<PreparedPhoto> {
  const [original, thumb] = await Promise.all([
    blobOf(dataUrl),
    thumbOf(dataUrl),
  ])
  const [originalSha256, thumbSha256] = await Promise.all([
    sha256Base64(original),
    sha256Base64(thumb),
  ])
  return { original, thumb, originalSha256, thumbSha256 }
}

// 로컬 가짜 업로드 폼(백엔드 FakeUploadPostSigner)은 닿지 않는 .invalid 주소예요. 받을 곳이 없어 올리기만 건너뛰어요
export const isFakeStorage = (url: string) => {
  try {
    return new URL(url).hostname.endsWith(".invalid")
  } catch {
    return false
  }
}

// presigned POST: 받은 필드를 그대로 넣고 파일은 맨 끝에(S3는 file 뒤의 필드를 보지 않아요)
export async function postToStorage(
  post: PresignedPostBody,
  file: Blob,
): Promise<void> {
  if (isFakeStorage(post.url)) return
  const form = new FormData()
  for (const [k, v] of Object.entries(post.fields)) form.append(k, v)
  form.append("file", file)
  let res: Response
  try {
    res = await fetch(post.url, { method: "POST", body: form })
  } catch {
    throw photoUploadFailed()
  }
  if (!res.ok) throw photoUploadFailed()
}

export function createHttpPhotoUploads(
  request: HttpRequest,
  deps: {
    prepare?: (dataUrl: string) => Promise<PreparedPhoto>
    send?: (post: PresignedPostBody, file: Blob) => Promise<void>
  } = {},
): PhotoUploadsApi {
  const prepare = deps.prepare ?? preparePhoto
  const send = deps.send ?? postToStorage
  return {
    async upload(roomId, photos) {
      if (!photos.length) return []
      if (photos.length > UPLOAD_BATCH_MAX)
        throw new ApiError({
          status: 0,
          code: "VALIDATION_FAILED",
          detail: `사진은 한 번에 ${UPLOAD_BATCH_MAX}장까지 올릴 수 있어요.`,
        })
      const prepared = await Promise.all(photos.map((p) => prepare(p)))
      // 촬영 시각(PHO-1f)은 다시 그리기 전 파일에서 읽어야 해서, 화면이 원본 파일을 들고 있게 바뀐 뒤 채워요
      const files: UploadFileBody[] = prepared.map((p) => ({
        originalBytes: p.original.size,
        thumbBytes: p.thumb.size,
        originalSha256: p.originalSha256,
        thumbSha256: p.thumbSha256,
        deviceTakenAt: null,
      }))
      const { slots } = await request<{ slots: UploadSlotBody[] }>(
        "POST",
        endpoints.uploadTickets(roomId),
        { body: { files } },
      )
      const ordered = [...slots].sort((a, b) => a.index - b.index)
      if (ordered.length !== prepared.length) throw photoUploadFailed()
      await Promise.all(
        ordered.map(async (slot, i) => {
          // 썸네일 먼저(올리는 중 화면에 바로 보이게, 04 "사진" 올리기)
          await send(slot.thumb, prepared[i].thumb)
          await send(slot.original, prepared[i].original)
        }),
      )
      return ordered.map((s) => s.ticket)
    },
  }
}

// 목업: 서버가 없어 표 모양의 글자만 돌려줘요(목업 기록 규칙은 사진 data URL을 그대로 써요)
export const mockPhotoUploads: PhotoUploadsApi = {
  upload: async (_roomId, photos) => photos.map((_, i) => `mock-ticket-${i}`),
}
