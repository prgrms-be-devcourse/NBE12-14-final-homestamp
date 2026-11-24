import { LogOut } from "lucide-react"
import { Button } from "@/components/ui/button"
import { FormDialog } from "@/components/common/form-dialog"
import type { Account } from "@/api/auth"
import { maskPhone } from "@/lib/phone"

// 내 계정(이름·이메일·인증한 휴대폰)과 로그아웃. 보내는 칸이 없어 아래 버튼 줄도 없어요.
export function AccountPanel({
  account,
  roomCount,
  invitationCount,
  onLogout,
  onCancel,
}: {
  account: Account
  roomCount: number
  invitationCount: number
  onLogout: () => void
  onCancel: () => void
}) {
  return (
    <FormDialog
      title="내 계정"
      description="세입자 쪽·집주인 쪽은 호실마다 연결로 정해져요."
      error=""
      onError={() => {}}
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
            <dd>{maskPhone(account.phone)}</dd>
          </div>
        </dl>
        <p className="muted">
          연결된 호실 {roomCount}곳 · 받은 초대 {invitationCount}건
        </p>
        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={onLogout}
        >
          <LogOut />
          로그아웃
        </Button>
      </div>
    </FormDialog>
  )
}
