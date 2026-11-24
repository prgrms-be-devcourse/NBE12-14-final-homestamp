import { CircleCheck, X } from "lucide-react"
import { Button } from "@/components/ui/button"

// 화면 아래에 잠깐 뜨는 한 줄 안내. 문구가 비면 보이지 않아요.
export function Toast({
  message,
  onClose,
}: {
  message: string
  onClose: () => void
}) {
  if (!message) return null
  return (
    <div className="toast" role="status">
      <CircleCheck size={19} />
      {message}
      <Button
        variant="ghost"
        size="icon-xs"
        aria-label="알림 닫기"
        onClick={onClose}
      >
        <X />
      </Button>
    </div>
  )
}
