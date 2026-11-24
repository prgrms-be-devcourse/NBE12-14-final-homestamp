import { useState } from "react"
import { Button } from "@/components/ui/button"

const LIMIT = 120

// 긴 사유는 120자까지만 보이고 펼칠 수 있어요. 이견 댓글과 부위·사진 변경 요청 댓글이 함께 씁니다.
export function LongText({
  text,
  more = "이견 사유 펼치기",
}: {
  text: string
  more?: string
}) {
  const [open, setOpen] = useState(false)
  const long = text.length > LIMIT
  return (
    <>
      <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">
        {long && !open ? text.slice(0, LIMIT) + "…" : text}
      </p>
      {long && (
        <Button
          type="button"
          variant="link"
          className="h-auto p-0"
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? "접기" : more}
        </Button>
      )}
    </>
  )
}
