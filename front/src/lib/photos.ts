import { exifTakenAt } from "./photo-taken-at"

// 다시 그린 사진(data URL) → 다시 그리기 전에 읽은 촬영 시각(PHO-1f). 화면은 사진을 data URL로만 들고 있어서
// 올릴 때(api/photo-uploads) 여기서 찾아요. 최근 것만 둬요(한 번에 고르는 사진은 10장, upload.batch.max)
const TAKEN_AT_KEEP = 50
const takenAt = new Map<string, string>()
const rememberTakenAt = (dataUrl: string, at: string | null) => {
  if (!at) return
  takenAt.delete(dataUrl)
  takenAt.set(dataUrl, at)
  while (takenAt.size > TAKEN_AT_KEEP)
    takenAt.delete(takenAt.keys().next().value!)
}
export const takenAtOf = (dataUrl: string) => takenAt.get(dataUrl) ?? null

// 사진 읽기: JPEG·PNG·WebP 10장, 장당 10MB. 브라우저에서 다시 그려(긴 변 2048px) 위치 정보(EXIF)를 없앱니다.
// 촬영 시각만 다시 그리기 전에 읽어 둬요(04 "사진" 올리기 ①)
export function readPhotos(
  files: FileList | null,
  already = 0,
): Promise<string[]> {
  if (!files) return Promise.resolve([])
  const list = Array.from(files)
  if (
    list.length + already > 10 ||
    list.some(
      (f) =>
        f.size > 10 * 1024 * 1024 ||
        !["image/jpeg", "image/png", "image/webp"].includes(f.type),
    )
  )
    return Promise.reject(
      new Error(
        "JPEG·PNG·WebP 사진을 10장까지, 장당 10MB 이하로 선택해 주세요.",
      ),
    )
  return Promise.all(
    list.map(
      (file) =>
        new Promise<string>((resolve, reject) => {
          const reader = new FileReader()
          reader.onerror = reject
          reader.onload = () => {
            const img = new Image()
            img.onerror = reject
            img.onload = () => {
              const scale = Math.min(1, 2048 / Math.max(img.width, img.height))
              const canvas = document.createElement("canvas")
              canvas.width = img.width * scale
              canvas.height = img.height * scale
              canvas
                .getContext("2d")!
                .drawImage(img, 0, 0, canvas.width, canvas.height)
              const dataUrl = canvas.toDataURL("image/jpeg", 0.85)
              file
                .arrayBuffer()
                .then(exifTakenAt, () => null)
                .then((at) => rememberTakenAt(dataUrl, at))
                .finally(() => resolve(dataUrl))
            }
            img.src = String(reader.result)
          }
          reader.readAsDataURL(file)
        }),
    ),
  )
}

// 사진 대신 그리는 정해진 표시(대체 그림 파일 없음, 04 "사진" 개인정보 사진 지우기). 지운 사진과, 주소를 못 받았거나 열리지 않는 사진
const placeholder = (text: string) =>
  "data:image/svg+xml;charset=utf-8," +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="360"><rect width="100%" height="100%" fill="#eef1f5"/><text x="50%" y="50%" fill="#6b7280" font-size="28" text-anchor="middle" dominant-baseline="middle" font-family="sans-serif">${text}</text></svg>`,
  )
export const ERASED_PHOTO = placeholder("지운 사진")
export const UNAVAILABLE_PHOTO = placeholder("사진을 불러오지 못했어요")
// <img onError>: 주소가 끝났거나 닿지 않으면(로컬 가짜 주소 .invalid 등) 정해진 표시로 바꿔요. 표시도 실패하면 다시 바꾸지 않아요
export const showUnavailable = (e: { currentTarget: HTMLImageElement }) => {
  if (e.currentTarget.src !== UNAVAILABLE_PHOTO)
    e.currentTarget.src = UNAVAILABLE_PHOTO
}
