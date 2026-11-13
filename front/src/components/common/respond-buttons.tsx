import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { ActionDialog } from "@/components/action-dialog"

// 응답 두 버튼(08-화면-기본-틀 2장, 09 5장 "같은 뜻은 한 부품").
// 하자, 지불 금액, 점검 항목, 송금, 고친 내용, 상황 변화 모두 이 부품 하나로 답해요.
// API는 기록마다 respond 하나: ACK(맞아요) / NEED_MORE(보류) / DISPUTE(이견). 보류·이견은 사유가 필수예요.
export type RespondAnswer = "ACK" | "NEED_MORE" | "DISPUTE"
const answerWord: Record<Exclude<RespondAnswer, "ACK">, string> = {
  NEED_MORE: "더 필요한 게 있어요",
  DISPUTE: "사실과 달라요",
}

export function RespondButtons({
  onRespond,
  answered,
  note,
  size = "default",
}: {
  // 오류 문구를 돌려주면 창이나 버튼 옆에 보여요
  onRespond: (answer: RespondAnswer, reason: string) => string | void
  // 이미 보류·이견으로 답했으면 (맞아요로 답 바꾸기)만 보여요. [맞아요]는 되돌리는 버튼이 없어요.
  answered?: "NEED_MORE" | "DISPUTE"
  // 버튼 아래 한 줄(예: 하자의 "수리비는 따로 정해요")
  note?: string
  size?: "default" | "sm"
}) {
  const [open, setOpen] = useState(false)
  const [answer, setAnswer] = useState<"NEED_MORE" | "DISPUTE" | "">("")
  const [error, setError] = useState("")
  const ack = () => setError(onRespond("ACK", "") || "")
  if (answered)
    return (
      <div className="space-y-1">
        <Button variant="link" size="sm" className="px-0" onClick={ack}>
          (맞아요로 답 바꾸기)
        </Button>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
      </div>
    )
  return (
    <div className="space-y-1.5">
      <div className="actions">
        <Button size={size} onClick={ack}>
          맞아요
        </Button>
        <Button
          size={size}
          variant="outline"
          onClick={() => {
            setAnswer("")
            setError("")
            setOpen(true)
          }}
        >
          아니에요 · 더 필요해요
        </Button>
      </div>
      {note && <p className="muted text-sm">{note}</p>}
      {error && !open && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={open}
        title="어떤 건가요?"
        description="원래 기록은 그대로 두고, 고른 답과 사유가 옆에 나란히 남아요."
        submitLabel="보내기"
        onClose={() => setOpen(false)}
        onSubmit={(f) => {
          if (!answer) return "더 필요한 게 있는지, 사실과 다른지 골라 주세요."
          const reason = String(f.get("reason") || "").trim()
          if (!reason) return "사유를 입력해 주세요."
          return onRespond(answer, reason) || undefined
        }}
      >
        <RadioGroup
          aria-label="답 고르기"
          value={answer}
          onValueChange={(v) => setAnswer(v as "NEED_MORE" | "DISPUTE")}
        >
          {(["NEED_MORE", "DISPUTE"] as const).map((a) => (
            <label key={a} className="flex cursor-pointer items-start gap-2.5">
              <RadioGroupItem value={a} className="mt-1" />
              <span>
                <strong className="font-semibold">{answerWord[a]}</strong>
                {a === "NEED_MORE" && (
                  <small className="muted block">사진, 근거, 원인 확인</small>
                )}
              </span>
            </label>
          ))}
        </RadioGroup>
        <div className="field">
          <label htmlFor="respond-reason">사유 · 필수</label>
          <Textarea id="respond-reason" name="reason" required />
        </div>
      </ActionDialog>
    </div>
  )
}
