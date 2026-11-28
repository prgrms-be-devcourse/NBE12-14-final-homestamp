import { useState } from "react"
import { Loader2, LogOut } from "lucide-react"
import { errorMessage } from "@/api/errors"
import { Button } from "@/components/ui/button"
import { FormDialog } from "@/components/common/form-dialog"
import type { Account, AcknowledgementKind } from "@/api/auth"
import { maskPhone } from "@/lib/phone"
import { ACKNOWLEDGEMENT_LABELS } from "@/features/member/terms-text"

const KINDS = Object.keys(ACKNOWLEDGEMENT_LABELS) as AcknowledgementKind[]

// 내 계정(이름·이메일·인증한 휴대폰), 약관·확인 항목, 로그아웃. 보내는 칸이 없어 아래 버튼 줄도 없어요.
export function AccountPanel({
  account,
  roomCount,
  invitationCount,
  onLogout,
  onOpenTerms,
  onCancel,
}: {
  account: Account
  roomCount: number
  invitationCount: number
  onLogout: () => Promise<void>
  // 약관 다시 확인 창을 열어요(06 S-18 [다시 확인], CONS-4)
  onOpenTerms: () => void
  onCancel: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  // 로그아웃에 성공하면 App이 로그인 화면으로 바꿔요. 실패하면 이 창에 남아 안내하고, 같은 버튼으로 다시 시도해요.
  async function logout() {
    setBusy(true)
    setError("")
    try {
      await onLogout()
    } catch (e) {
      setError(`로그아웃하지 못했어요. ${errorMessage(e)}`)
      setBusy(false)
    }
  }
  const pending = account.pendingAcknowledgements
  return (
    <FormDialog
      title="내 계정"
      description="세입자 쪽·집주인 쪽은 호실마다 연결로 정해져요."
      error={error}
      onError={setError}
      onCancel={onCancel}
      footer={false}
    >
      <div className="space-y-4">
        <dl className="svc-facts">
          <div>
            <dt>이름</dt>
            <dd>{account.name}</dd>
          </div>
          <div>
            <dt>이메일</dt>
            <dd>{account.email}</dd>
          </div>
          <div>
            <dt>인증한 휴대폰</dt>
            <dd>{maskPhone(account.phone) || "인증한 번호가 없어요"}</dd>
          </div>
        </dl>
        <p className="muted">
          연결된 호실 {roomCount}곳 · 받은 초대 {invitationCount}건
        </p>
        {/* 06 S-18 "약관·처리방침". 서버가 알려 주는 것은 다시 확인할 새 판뿐이라 그것만 판을 보여 줘요.
            옛 판·바뀐 점은 실제로 제공된 문구가 없어 보여 주지 않아요 */}
        <section aria-labelledby="account-terms" className="space-y-2">
          <h3 id="account-terms" className="font-medium">
            약관·확인 항목
          </h3>
          <dl className="svc-facts">
            {KINDS.map((kind) => {
              const p = pending.find((x) => x.kind === kind)
              return (
                <div key={kind}>
                  <dt>{ACKNOWLEDGEMENT_LABELS[kind]}</dt>
                  <dd>
                    {p ? `새 판 ${p.textVersion} · 확인 전` : "확인했어요"}
                  </dd>
                </div>
              )
            })}
          </dl>
          {pending.length > 0 && (
            <Button type="button" className="w-full" onClick={onOpenTerms}>
              다시 확인
            </Button>
          )}
        </section>
        <Button
          type="button"
          variant="outline"
          className="w-full"
          disabled={busy}
          onClick={() => void logout()}
        >
          {busy ? <Loader2 className="animate-spin" /> : <LogOut />}
          로그아웃
        </Button>
      </div>
    </FormDialog>
  )
}
