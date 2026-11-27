import { strToU8, zipSync, type Zippable } from "fflate"
import type {
  DownloadsApi,
  PhotoUrlBody,
  RoomDownloadBody,
} from "@/api/downloads"

// 호실 기록 내려받기(S-19, DL-1 최소판). 서버는 사진을 묶지 않아 화면이 ZIP을 만들어요(04 내려받기 줄).
// 기록.json은 서버가 준 record 그대로, 사진은 record.photos[].file 경로(사진/<photoId>.jpg)에 원본을 넣어요.

// 한 묶음에서 받지 못한 사진만 주소를 다시 받아 되풀이하는 횟수(DL-1b, S-19 "실패한 사진만 다시")
export const PHOTO_RETRIES = 2
// 주소가 끝나기 조금 전이면 받지 않고 새 주소를 받아요(받는 도중 끊기지 않게)
const EXPIRY_MARGIN_MS = 10_000

export interface ExportProgress {
  done: number
  total: number
}
export interface ExportResult {
  zip: Uint8Array
  // 받는 도중 지워져 파일 없이 둔 사진(06 4-5 erased)
  erased: string[]
  // 다시 받아도 받지 못한 사진
  missing: string[]
  // 받은 원본의 SHA-256이 기록.json의 지문값과 다른 사진(DL-1a)
  mismatched: string[]
}
export interface ExportDeps {
  api: DownloadsApi
  // 원본 주소에서 바이트를 받아요(서명된 주소라 로그인 토큰을 붙이지 않음). 실패하면 던져요
  fetchPhoto?: (url: string) => Promise<Uint8Array>
  sha256?: (bytes: Uint8Array) => Promise<string>
  now?: () => number
  onProgress?: (p: ExportProgress) => void
}

export const fetchPhotoBytes = async (url: string) => {
  const res = await fetch(url, { credentials: "omit" })
  if (!res.ok) throw new Error(`사진을 받지 못했어요(${res.status})`)
  return new Uint8Array(await res.arrayBuffer())
}
export const sha256Hex = async (bytes: Uint8Array) => {
  const digest = await crypto.subtle.digest("SHA-256", bytes as BufferSource)
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
}

export async function buildRoomZip(
  download: RoomDownloadBody,
  {
    api,
    fetchPhoto = fetchPhotoBytes,
    sha256 = sha256Hex,
    now = Date.now,
    onProgress,
  }: ExportDeps,
): Promise<ExportResult> {
  const files: Zippable = {
    "기록.json": strToU8(JSON.stringify(download.record, null, 2)),
  }
  const byId = new Map(download.record.photos.map((p) => [p.photoId, p]))
  const result: Omit<ExportResult, "zip"> = {
    erased: [],
    missing: [],
    mismatched: [],
  }
  const total = download.photoCount
  let done = 0
  const step = () => onProgress?.({ done: ++done, total })
  onProgress?.({ done, total })
  // 한 장 받기. 주소가 끝났거나(곧 끝나거나) 받기에 실패하면 false → 그 사진만 주소를 다시 받아요
  const take = async (u: PhotoUrlBody) => {
    if (u.erased) {
      result.erased.push(u.photoId)
      step()
      return true
    }
    const expires = u.expiresAt ? Date.parse(u.expiresAt) : NaN
    if (
      !u.url ||
      (!Number.isNaN(expires) && expires - EXPIRY_MARGIN_MS < now())
    )
      return false
    let bytes: Uint8Array
    try {
      bytes = await fetchPhoto(u.url)
    } catch {
      return false
    }
    const photo = byId.get(u.photoId)
    files[photo?.file ?? `사진/${u.photoId}.jpg`] = [bytes, { level: 0 }]
    if (photo && (await sha256(bytes)) !== photo.sha256.toLowerCase())
      result.mismatched.push(u.photoId)
    step()
    return true
  }
  for (const batch of download.photoBatches) {
    let pending = [...batch]
    for (
      let attempt = 0;
      pending.length && attempt <= PHOTO_RETRIES;
      attempt++
    ) {
      const urls = await api.originalUrls(pending)
      const failed: string[] = []
      for (const u of urls) if (!(await take(u))) failed.push(u.photoId)
      pending = failed
    }
    for (const id of pending) {
      result.missing.push(id)
      step()
    }
  }
  // 사진(JPEG)은 이미 압축돼 있어 다시 줄이지 않고, 기록.json만 줄여요
  return { zip: zipSync(files, { level: 6 }), ...result }
}

// 저장할 파일 이름: 홈스탬프-<호실 이름>-<YYYYMMDD>.zip(파일 이름에 못 쓰는 글자는 뺌)
export function exportFileName(roomName: string, at: Date) {
  const safe = roomName.replace(/[\\/:*?"<>|]/g, "").trim() || "호실"
  const ymd = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Seoul",
  })
    .format(at)
    .replaceAll("-", "")
  return `홈스탬프-${safe}-${ymd}.zip`
}
