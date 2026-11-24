import { Camera, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { readPhotos } from "@/lib/photos"

// 사진 칸(하자 등록 · 점검 · 수리 후 기록). 사진 읽기는 lib/photos(수리 건 사진 추가와 같은 규칙).
// 서버가 생기면 업로드 표(PHO-1)로 바뀌는 곳이에요.
export function PhotoField({
  label,
  required,
  photos,
  onChange,
  onError,
}: {
  label: string
  required: boolean
  photos: string[]
  onChange: (photos: string[]) => void
  onError: (message: string) => void
}) {
  const upload = (files: FileList | null) => {
    if (!files) return
    onError("")
    readPhotos(files, photos.length)
      .then((p) => onChange([...photos, ...p]))
      .catch((e) =>
        onError(
          e instanceof Error
            ? e.message
            : "사진을 읽을 수 없어요. 다른 사진을 선택해 주세요.",
        ),
      )
  }
  return (
    <div className="field">
      <label htmlFor="photos">
        {label} <span>{required ? "필수 · 1~10장" : "선택 · 최대 10장"}</span>
      </label>
      <label className="upload-zone" htmlFor="photos">
        <Camera size={25} />
        <strong>사진을 선택하거나 촬영해 주세요</strong>
        <small>JPEG, PNG, WebP · 장당 10MB 이하</small>
        <Input
          id="photos"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(e) => {
            upload(e.target.files)
            // 뺀 사진을 다시 고를 수 있게 비워 둬요(같은 파일이면 change가 다시 오지 않아요)
            e.target.value = ""
          }}
        />
      </label>
      <small className="muted">
        계약서·신분증·통장 사진은 올리지 마세요. 얼굴·우편물도 확인해 주세요.
      </small>
      <div className="preview-grid">
        {photos.map((p, i) => (
          <div key={i}>
            <img src={p} alt={`첨부 사진 ${i + 1}`} />
            <Button
              type="button"
              variant="secondary"
              size="icon-xs"
              aria-label={`첨부 사진 ${i + 1} 제외`}
              onClick={() => onChange(photos.filter((_, n) => n !== i))}
            >
              <X />
            </Button>
          </div>
        ))}
      </div>
    </div>
  )
}
