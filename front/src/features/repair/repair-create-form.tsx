import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Notice } from "@/components/notice"
import { FormDialog } from "@/components/common/form-dialog"
import { PartField } from "@/components/common/part-field"
import { PhotoField } from "@/components/common/photo-field"
import { useApi } from "@/api/hooks"
import { errorMessage } from "@/api/errors"
import type { Room } from "@/data/demo"
import { readZonePart, ZONE_PART_ERROR } from "@/lib/record-commands"

// 같은 곳 재발이면 이전 수리 건과 그 부위로 시작해요(상태와 규칙 D 재발)
export interface RepairPrefill {
  recurrenceOf: string
  recurrenceTitle?: string
  zone: string
  part: string
}

// 하자 등록(S-11, REP-1). 수리 건 API로 보내요(POST /api/rooms/{id}/repair-cases). 판단은 서버(지금은 목업)가 해요.
// 상대가 아직 없으면 지금 알릴지(now) 혼자 기록할지(later) 골라야 보낼 수 있어요.
export function RepairCreateForm({
  room,
  accountId,
  prefill,
  onCreated,
  onCancel,
}: {
  room: Room
  accountId?: string
  prefill?: RepairPrefill
  onCreated: (repairId: string, share: "now" | "later" | null) => void
  onCancel: () => void
}) {
  const api = useApi()
  const [zone, setZone] = useState(prefill?.zone ?? "욕실")
  const [part, setPart] = useState(prefill?.part ?? "천장")
  const [photos, setPhotos] = useState<string[]>([])
  const [shareChoice, setShareChoice] = useState<"now" | "later" | null>(null)
  const [error, setError] = useState("")
  return (
    <FormDialog
      title="새 하자 기록"
      error={error}
      onError={setError}
      onCancel={onCancel}
      onSubmit={(text) => {
        const place = readZonePart(zone, part, text)
        if (!place) {
          setError(ZONE_PART_ERROR)
          return
        }
        if (!photos.length) {
          setError("수리 전 사진을 1장 이상 첨부해 주세요.")
          return
        }
        if (room.connection === "연결 전" && !shareChoice) {
          setError("상대에게 알릴지, 혼자 기록할지 선택해 주세요.")
          return
        }
        if (!accountId) return
        api.repairCases
          .create({ accountId }, room.id, {
            title: text("title"),
            zone: `${place.zone} · ${place.part}`,
            description: text("description"),
            photos,
            recurrenceOf: prefill?.recurrenceOf || undefined,
          })
          .then((v) => onCreated(v.repair.id, shareChoice))
          .catch((e) => setError(errorMessage(e)))
      }}
    >
      {prefill?.recurrenceOf && (
        <Notice>
          이전 수리 건 「{prefill.recurrenceTitle}」 이후 재발로 새 수리 건을
          등록해요. 이전 건은 다시 열지 않고 참조만 해요.
        </Notice>
      )}
      <PartField
        zone={zone}
        part={part}
        onChange={(z, p) => {
          setZone(z)
          setPart(p)
        }}
      />
      <div className="field">
        <label htmlFor="title">기록 제목</label>
        <Input
          id="title"
          name="title"
          placeholder="예: 욕실 천장에 물 자국이 있어요"
          maxLength={80}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="description">상태 설명</label>
        <Textarea
          id="description"
          name="description"
          placeholder="직접 확인한 상태를 구체적으로 남겨 주세요."
          maxLength={2000}
          required
        />
      </div>
      <PhotoField
        label="수리 전 사진"
        required
        photos={photos}
        onChange={setPhotos}
        onError={setError}
      />
      {room.connection === "연결 전" && (
        <div className="space-y-3">
          <p className="muted">
            상대에게 이 기록을 알릴까요? 기본 선택은 없어요.
          </p>
          {room.phone && (
            <p className="muted">
              이 호실에 등록한 상대 연락처 {room.phone}로 초대를 준비해요.
            </p>
          )}
          <div className="actions">
            <Button
              type="button"
              variant={shareChoice === "now" ? "default" : "outline"}
              onClick={() => setShareChoice("now")}
            >
              알리기 · 번호와 공유 확인
            </Button>
            <Button
              type="button"
              variant={shareChoice === "later" ? "default" : "outline"}
              onClick={() => setShareChoice("later")}
            >
              나중에 · 혼자 기록
            </Button>
          </div>
        </div>
      )}
    </FormDialog>
  )
}
