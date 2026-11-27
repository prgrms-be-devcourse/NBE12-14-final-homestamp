import { useState } from "react"
import { ArrowRight, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { ApiError, errorMessage } from "@/api/errors"
import type { PendingAcknowledgement } from "@/api/auth"
import { ACKNOWLEDGEMENT_LABELS } from "@/features/member/terms-text"
import {
  PrivacyNoticePoints,
  TermsSummary,
} from "@/features/member/terms-blocks"

export const TERMS_RECONFIRMED_MESSAGE =
  "확인했어요. 하려던 일이 있었다면 다시 해 주세요."
export const TERMS_RELOADED_MESSAGE =
  "그사이 문구가 바뀌어 새로 불러왔어요. 다시 확인해 주세요."

// 바뀐 약관·확인 항목 다시 확인(03 6-2 "문구가 바뀌면", 06 CONS-4a). 로그인 뒤 확인할 문구가 있거나
// 상태를 바꾸는 요청이 TERMS_UPDATE_REQUIRED로 거절됐을 때 열려요. 다른 창 위에 따로 떠서 쓰던 입력을 지우지 않아요.
// [나중에]로 닫으면 확인 전까지 보기만 할 수 있어요(내려받기 같은 권리 행사는 확인 없이 돼요, CONS-4b).
// 확인한 뒤 거절됐던 요청은 다시 보내지 않아요(사람이 다시 눌러요).
export function TermsReconfirmDialog({
  open,
  pending,
  onClose,
  onAcknowledge,
  onReload,
  onDone,
}: {
  open: boolean
  pending: PendingAcknowledgement[]
  onClose: () => void
  // 서버가 준 판 그대로 보내요. 성공하면 부르는 쪽이 내 정보를 바꿔요
  onAcknowledge: (items: PendingAcknowledgement[]) => Promise<void>
  // 판이 그사이 바뀌었을 때(VERSION_CONFLICT) 내 정보를 다시 불러와요
  onReload: () => Promise<void>
  onDone: (message: string) => void
}) {
  // 판이 바뀌면 체크를 처음부터 다시 받아요. 다시 불러왔다는 안내는 새 판의 창에도 남아야 해서 여기서 들고 있어요
  const key = pending.map((p) => `${p.kind}:${p.textVersion}`).join(",")
  const [notice, setNotice] = useState("")
  const close = () => {
    setNotice("")
    onClose()
  }
  return (
    <Dialog
      open={open && pending.length > 0}
      onOpenChange={(v) => {
        if (!v) close()
      }}
    >
      <DialogContent className="app-dialog sm:max-w-lg">
        <ReconfirmForm
          key={key}
          pending={pending}
          notice={notice}
          onClose={close}
          onAcknowledge={onAcknowledge}
          onReload={async () => {
            await onReload().catch(() => undefined)
            setNotice(TERMS_RELOADED_MESSAGE)
          }}
          onDone={(m) => {
            setNotice("")
            onDone(m)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}

function ReconfirmForm({
  pending,
  notice,
  onClose,
  onAcknowledge,
  onReload,
  onDone,
}: {
  pending: PendingAcknowledgement[]
  notice: string
  onClose: () => void
  onAcknowledge: (items: PendingAcknowledgement[]) => Promise<void>
  onReload: () => Promise<void>
  onDone: (message: string) => void
}) {
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [termsOpen, setTermsOpen] = useState(false)
  const all = pending.every((p) => checked[p.kind])
  async function submit() {
    setBusy(true)
    setError("")
    try {
      await onAcknowledge(pending)
      onDone(TERMS_RECONFIRMED_MESSAGE)
    } catch (e) {
      if (e instanceof ApiError && e.code === "VERSION_CONFLICT") {
        // 새 판으로 다시 열려요(key가 바뀌어 이 창은 새로 그려지고 체크를 다시 받아요)
        await onReload()
        // 다시 불러와도 같은 판이면 이 창이 그대로라 버튼을 다시 풀어요
        setChecked({})
        setBusy(false)
        return
      }
      setError(errorMessage(e))
      setBusy(false)
    }
  }
  return (
    <>
      <DialogHeader>
        <DialogTitle>바뀐 약관을 확인해 주세요</DialogTitle>
        <DialogDescription>
          확인하기 전에는 기록을 보기만 할 수 있어요. 확인하지 않아도 내 기록은
          보고 내려받을 수 있어요.
        </DialogDescription>
      </DialogHeader>
      <div className="modal-form">
        {pending.map((p) => (
          <div key={p.kind} className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <label className="consent">
                <Checkbox
                  checked={Boolean(checked[p.kind])}
                  onCheckedChange={(v) =>
                    setChecked((c) => ({ ...c, [p.kind]: Boolean(v) }))
                  }
                />
                (필수) {ACKNOWLEDGEMENT_LABELS[p.kind]}
              </label>
              {p.kind === "TERMS_AGREED" && (
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  aria-expanded={termsOpen}
                  onClick={() => setTermsOpen((o) => !o)}
                >
                  {termsOpen ? "접기" : "보기"}
                </Button>
              )}
            </div>
            <p className="muted">판 {p.textVersion}</p>
            {p.kind === "TERMS_AGREED" && termsOpen && <TermsSummary />}
            {p.kind === "PRIVACY_NOTICE_ACK" && (
              <div className="auth-notice">
                <strong>개인정보 처리 안내</strong>
                <PrivacyNoticePoints />
              </div>
            )}
          </div>
        ))}
        {(error || notice) && (
          <p className="form-error" role="alert">
            {error || notice}
          </p>
        )}
        <div className="modal-footer">
          <Button type="button" variant="outline" onClick={onClose}>
            나중에
          </Button>
          <Button
            type="button"
            disabled={busy || !all}
            onClick={() => void submit()}
          >
            {busy && <Loader2 className="animate-spin" />}
            확인하고 계속
            <ArrowRight />
          </Button>
        </div>
      </div>
    </>
  )
}
