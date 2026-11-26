import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { FormDialog } from "@/components/common/form-dialog"
import { PhotoField } from "@/components/common/photo-field"
import { useApi } from "@/api/hooks"
import { errorMessage } from "@/api/errors"

// 수리 후 사진 기록(REP-7). 수리 건 API로 보내요(POST /api/repair-cases/{id}/photos).
export function RepairAfterForm({
  repairId,
  accountId,
  onDone,
  onCancel,
}: {
  repairId: string
  accountId?: string
  onDone: () => void
  onCancel: () => void
}) {
  const api = useApi()
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  return (
    <FormDialog
      title="수리 후 사진 기록"
      error={error}
      onError={setError}
      onCancel={onCancel}
      onSubmit={(text) => {
        if (!photos.length) {
          setError("수리 후 사진을 1장 이상 첨부해 주세요.")
          return
        }
        if (!accountId) return
        api.repairCases
          .addPhotos({ accountId }, repairId, {
            photos,
            afterRecord: {
              title: text("title") || "수리 후 사진",
              description: text("description"),
            },
          })
          .then(onDone)
          .catch((e) => setError(errorMessage(e)))
      }}
    >
      <div className="field">
        <label htmlFor="after-title">수리 후 기록 제목</label>
        <Input
          id="after-title"
          name="title"
          defaultValue="수리 후 상태"
          required
        />
      </div>
      <div className="field">
        <label htmlFor="description">보충 설명</label>
        <Textarea
          id="description"
          name="description"
          placeholder="이전 기록에 덧붙일 내용을 적어 주세요."
          required
          maxLength={2000}
        />
      </div>
      <PhotoField
        label="수리 후 사진"
        required
        photos={photos}
        onChange={setPhotos}
        onError={setError}
      />
    </FormDialog>
  )
}
