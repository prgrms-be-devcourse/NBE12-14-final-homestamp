// 사진 읽기(목업): JPEG·PNG·WebP 10장, 장당 10MB. 브라우저에서 다시 그려(긴 변 2048px) 위치 정보(EXIF)를 없앱니다.
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
              resolve(canvas.toDataURL("image/jpeg", 0.85))
            }
            img.src = String(reader.result)
          }
          reader.readAsDataURL(file)
        }),
    ),
  )
}
