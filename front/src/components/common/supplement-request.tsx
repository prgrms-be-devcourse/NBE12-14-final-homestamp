import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { ActionDialog } from "@/components/action-dialog"
import { readPhotos } from "@/lib/photos"

// 보류·이견을 받은 쪽의 [보충하고 다시 확인 요청] / (이대로 둘게요)(08-화면-기본-틀 2장, 03 G '응답과 그 뒤').
// 사진이나 설명을 하나 이상 더해야 보내지고, 한 기록에 7일에 한 번이에요. 7일 안이면 버튼 자리에 "N일 뒤 다시 요청할 수 있어요".
export function SupplementRequest({
  waitDays,
  label = "보충하고 다시 확인 요청",
  onSupplement,
}: {
  // 다시 요청할 수 있을 때까지 남은 날(0이면 지금 할 수 있음). 서버가 알려 준 값을 그대로 써요.
  waitDays: number
  // 버튼 이름(고친 내용은 "사진 더해서 다시 요청", 08 3장 장면 4)
  label?: string
  onSupplement: (data: { text: string; photos: string[] }) => string | void
}) {
  const [open, setOpen] = useState(false)
  const [left, setLeft] = useState(false)
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  if (left) return null
  if (waitDays > 0)
    return (
      <p className="muted text-sm">{waitDays}일 뒤 다시 요청할 수 있어요</p>
    )
  return (
    <div className="actions">
      <Button
        onClick={() => {
          setPhotos([])
          setError("")
          setOpen(true)
        }}
      >
        {label}
      </Button>
      {/* 아무것도 바뀌지 않아요. 보류·이견은 표시로 남고 다른 일은 계속할 수 있어요. */}
      <Button variant="link" size="sm" onClick={() => setLeft(true)}>
        (이대로 둘게요)
      </Button>
      <ActionDialog
        open={open}
        title={label}
        description="사진이나 설명을 하나 이상 더해야 보낼 수 있어요. 상대에게 새 확인 요청이 가고, 한 기록에 7일에 한 번만 보낼 수 있어요."
        submitLabel="다시 확인 요청"
        onClose={() => setOpen(false)}
        onSubmit={(f) => {
          const text = String(f.get("text") || "").trim()
          if (!text && !photos.length)
            return "사진이나 설명을 하나 이상 더해 주세요."
          return onSupplement({ text, photos }) || undefined
        }}
      >
        <div className="field">
          <label htmlFor="supplement-text">보충 설명</label>
          <Textarea
            id="supplement-text"
            name="text"
            placeholder="예: 천장 전체가 보이게 다시 찍었어요"
            maxLength={2000}
          />
        </div>
        <div className="field">
          <label htmlFor="supplement-photos">사진 더하기</label>
          <Input
            id="supplement-photos"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={(e) =>
              readPhotos(e.target.files, photos.length)
                .then((p) => setPhotos((old) => [...old, ...p]))
                .catch((err) => setError(err.message))
            }
          />
          {photos.length > 0 && (
            <small className="muted">사진 {photos.length}장 선택됨</small>
          )}
        </div>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
      </ActionDialog>
    </div>
  )
}
