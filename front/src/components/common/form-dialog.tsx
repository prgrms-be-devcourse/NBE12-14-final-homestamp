import type { FormEvent, ReactNode } from "react"
import { ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { hasResidentId, RESIDENT_ID_MESSAGE } from "@/lib/service-rules"
import type { FieldText } from "@/lib/record-commands"

// 입력 창 하나의 틀: 제목 · 설명 · 칸 · 오류 한 줄 · [취소][보내기].
// 모든 창이 같이 거치는 확인(주민등록번호 금지)은 여기서 하고, 나머지 확인과 보내기는 각 창이 해요.
// 창 바깥(Dialog)은 App이 하나만 두고, 이 틀이 그 안을 채워요.
export function FormDialog({
  title,
  description = "사실을 기록하고, 상대의 응답을 기다려요. 원래 기록은 그대로 남습니다.",
  error,
  onError,
  onSubmit,
  onCancel,
  submitLabel = "기록 남기기",
  cancelLabel = "취소",
  submitDisabled,
  footer = true,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  error: string
  onError: (message: string) => void
  onSubmit?: (text: FieldText, form: FormData) => void
  onCancel: () => void
  submitLabel?: string
  cancelLabel?: string
  submitDisabled?: boolean
  footer?: boolean
  children: ReactNode
}) {
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    if (
      Array.from(f.values()).some(
        (v) => typeof v === "string" && hasResidentId(v),
      )
    ) {
      onError(RESIDENT_ID_MESSAGE)
      return
    }
    onSubmit?.((key) => String(f.get(key) || "").trim(), f)
  }
  return (
    <>
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription>{description}</DialogDescription>
      </DialogHeader>
      <form onSubmit={submit} className="modal-form">
        {children}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {footer && (
          <div className="modal-footer">
            <Button type="button" variant="outline" onClick={onCancel}>
              {cancelLabel}
            </Button>
            <Button type="submit" disabled={submitDisabled}>
              {submitLabel}
              <ArrowRight />
            </Button>
          </div>
        )}
      </form>
    </>
  )
}
