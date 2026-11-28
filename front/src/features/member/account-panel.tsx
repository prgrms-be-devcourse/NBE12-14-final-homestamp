import { useState } from "react"
import { Loader2, LogOut } from "lucide-react"
import { ApiError, errorMessage } from "@/api/errors"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { FormDialog } from "@/components/common/form-dialog"
import { Notice } from "@/components/common/notice"
import {
  NAME_MAX,
  NAME_RULE,
  PASSWORD_RULE,
  validPassword,
  type Account,
  type AccountPatch,
  type AcknowledgementKind,
} from "@/api/auth"
import { maskPhone } from "@/lib/phone"
import { ACKNOWLEDGEMENT_LABELS } from "@/features/member/terms-text"

const KINDS = Object.keys(ACKNOWLEDGEMENT_LABELS) as AcknowledgementKind[]

export const NAME_SAVED_MESSAGE = "이름을 바꿨어요."
export const SMS_ON_MESSAGE = "문자를 다시 받아요."
// 본인이 요청한 인증번호 문자는 문자 설정과 상관없이 가요(03 K "인증 문자", 06 NOTI-8b)
export const SMS_OFF_MESSAGE =
  "문자를 껐어요. 알림은 서비스 안에서 받아요. 가입·비밀번호 재설정 같은 인증번호 문자는 계속 가요."
export const PASSWORD_CHANGED_MESSAGE =
  "비밀번호를 바꿨어요. 이 기기는 그대로이고, 다른 기기에서는 로그아웃됐어요."
// 바뀐 약관을 확인하기 전이라 거절됐을 때(CONS-4b). 확인 창은 App이 열고, 거절된 요청은 다시 보내지 않아요(#401)
export const TERMS_FIRST_MESSAGE =
  "바뀐 약관을 확인한 뒤 다시 해 주세요. 문자 끄기와 비밀번호 바꾸기는 확인 전에도 돼요."
// 같은 회원의 비밀번호 변경·재설정·내 정보 수정이 동시에 왔을 때(06 4-1 VERSION_CONFLICT)
export const ACCOUNT_CONFLICT_MESSAGE =
  "그사이 다른 곳에서 계정 정보가 바뀌었어요. 다시 해 주세요."

const accountError = (e: unknown) =>
  e instanceof ApiError && e.code === "TERMS_UPDATE_REQUIRED"
    ? TERMS_FIRST_MESSAGE
    : e instanceof ApiError && e.code === "VERSION_CONFLICT"
      ? ACCOUNT_CONFLICT_MESSAGE
      : errorMessage(e)

type Editing = "name" | "password" | null
type Busy = "logout" | "name" | "sms" | "password"

// 내 계정(06 S-18을 만들기 전까지 이 창, decisions 2026-11-28): 이름·문자 설정·비밀번호 바꾸기(ACC-4·6),
// 인증한 휴대폰, 약관·확인 항목, 로그아웃. 칸마다 따로 보내서 아래 공통 버튼 줄은 없어요.
export function AccountPanel({
  account,
  roomCount,
  invitationCount,
  onLogout,
  onOpenTerms,
  onUpdate,
  onChangePassword,
  onCancel,
}: {
  account: Account
  roomCount: number
  invitationCount: number
  onLogout: () => Promise<void>
  // 약관 다시 확인 창을 열어요(06 S-18 [다시 확인], CONS-4)
  onOpenTerms: () => void
  // 이름·문자 설정을 보내요(POST /api/me). 성공하면 부르는 쪽이 내 정보를 바꿔요
  onUpdate: (patch: AccountPatch) => Promise<void>
  // 비밀번호 변경(POST /api/me/password)
  onChangePassword: (current: string, next: string) => Promise<void>
  onCancel: () => void
}) {
  const [busy, setBusy] = useState<Busy>()
  const [error, setError] = useState("")
  const [done, setDone] = useState("")
  const [editing, setEditing] = useState<Editing>(null)
  const [name, setName] = useState(account.name)
  const [pw, setPw] = useState({ current: "", next: "", next2: "" })

  async function run(kind: Busy, task: () => Promise<string | void>) {
    setBusy(kind)
    setError("")
    setDone("")
    try {
      const message = await task()
      if (message) setDone(message)
    } catch (e) {
      setError(accountError(e))
    } finally {
      setBusy(undefined)
    }
  }
  const edit = (next: Editing) => {
    setEditing(next)
    setError("")
    setDone("")
    setName(account.name)
    setPw({ current: "", next: "", next2: "" })
  }

  // 로그아웃에 성공하면 App이 로그인 화면으로 바꿔요. 실패하면 이 창에 남아 안내하고, 같은 버튼으로 다시 시도해요.
  const logout = () =>
    run("logout", async () => {
      try {
        await onLogout()
      } catch (e) {
        throw new Error(`로그아웃하지 못했어요. ${errorMessage(e)}`, {
          cause: e,
        })
      }
    })
  // 이름(06 4-1 displayName: 비어 있지 않고 50자 이하). 같은 이름이면 보내지 않아요
  const saveName = () =>
    run("name", async () => {
      const next = name.trim()
      if (!next || next.length > NAME_MAX) throw new Error(NAME_RULE)
      const changed = next !== account.name
      if (changed) await onUpdate({ name: next })
      setEditing(null)
      return changed ? NAME_SAVED_MESSAGE : undefined
    })
  // 문자 설정(ACC-6). 꺼도 번호는 지우지 않고, 서비스 안 알림은 그대로 받아요(03 K)
  const toggleSms = () =>
    run("sms", async () => {
      const next = !account.smsEnabled
      await onUpdate({ smsEnabled: next })
      return next ? SMS_ON_MESSAGE : SMS_OFF_MESSAGE
    })
  const changePassword = () =>
    run("password", async () => {
      if (!pw.current) throw new Error("지금 비밀번호를 넣어 주세요.")
      if (!validPassword(pw.next))
        throw new Error(`새 비밀번호는 ${PASSWORD_RULE}`)
      if (pw.next !== pw.next2)
        throw new Error("새 비밀번호 확인이 일치하지 않아요.")
      try {
        await onChangePassword(pw.current, pw.next)
      } catch (e) {
        // 지금 비밀번호가 틀렸으면 그 칸만 비워 다시 넣게 해요
        if (e instanceof ApiError && e.code === "PASSWORD_MISMATCH")
          setPw((p) => ({ ...p, current: "" }))
        throw e
      }
      setEditing(null)
      setPw({ current: "", next: "", next2: "" })
      return PASSWORD_CHANGED_MESSAGE
    })

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
        {done && <Notice>{done}</Notice>}
        <section aria-labelledby="account-info" className="space-y-2">
          <h3 id="account-info" className="font-medium">
            내 정보
          </h3>
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
          {editing === "name" ? (
            <div className="field">
              <label htmlFor="account-name">새 이름</label>
              <Input
                id="account-name"
                autoComplete="name"
                maxLength={NAME_MAX}
                value={name}
                disabled={busy === "name"}
                onChange={(e) => setName(e.target.value)}
              />
              <small className="muted">상대에게는 가려서 보여요.</small>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => edit(null)}
                >
                  취소
                </Button>
                <Button
                  type="button"
                  className="flex-1"
                  disabled={busy !== undefined}
                  onClick={() => void saveName()}
                >
                  {busy === "name" && <Loader2 className="animate-spin" />}
                  이름 저장
                </Button>
              </div>
            </div>
          ) : (
            <Button
              type="button"
              variant="outline"
              className="w-full"
              disabled={busy !== undefined}
              onClick={() => edit("name")}
            >
              이름 바꾸기
            </Button>
          )}
        </section>
        <p className="muted">
          연결된 호실 {roomCount}곳 · 받은 초대 {invitationCount}건
        </p>
        {/* 문자 설정(06 ACC-6). 꺼도 응답 요청은 서비스 안 알림으로 받고, 기다림은 알림이 생긴 때부터 세요(ACC-6a) */}
        <section aria-labelledby="account-sms" className="space-y-2">
          <h3 id="account-sms" className="font-medium">
            문자 설정
          </h3>
          <dl className="svc-facts">
            <div>
              <dt>문자 받기</dt>
              <dd>{account.smsEnabled ? "켜 둠" : "꺼 둠"}</dd>
            </div>
          </dl>
          <small className="muted">
            꺼도 알림은 서비스 안에서 받아요. 인증번호 문자는 설정과 상관없이
            가요.
          </small>
          <Button
            type="button"
            variant="outline"
            className="w-full"
            disabled={busy !== undefined}
            onClick={() => void toggleSms()}
          >
            {busy === "sms" && <Loader2 className="animate-spin" />}
            {account.smsEnabled ? "문자 끄기" : "문자 켜기"}
          </Button>
        </section>
        <section aria-labelledby="account-password" className="space-y-2">
          <h3 id="account-password" className="font-medium">
            로그인 비밀번호
          </h3>
          {editing === "password" ? (
            <div className="space-y-3">
              <div className="field">
                <label htmlFor="account-pw-current">지금 비밀번호</label>
                <Input
                  id="account-pw-current"
                  type="password"
                  autoComplete="current-password"
                  value={pw.current}
                  onChange={(e) => setPw({ ...pw, current: e.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="account-pw-next">새 비밀번호</label>
                <Input
                  id="account-pw-next"
                  type="password"
                  autoComplete="new-password"
                  value={pw.next}
                  onChange={(e) => setPw({ ...pw, next: e.target.value })}
                />
                <small className="muted">{PASSWORD_RULE}</small>
              </div>
              <div className="field">
                <label htmlFor="account-pw-next2">새 비밀번호 확인</label>
                <Input
                  id="account-pw-next2"
                  type="password"
                  autoComplete="new-password"
                  value={pw.next2}
                  onChange={(e) => setPw({ ...pw, next2: e.target.value })}
                />
              </div>
              <small className="muted">
                바꾸면 이 기기를 뺀 다른 기기에서는 로그아웃돼요.
              </small>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => edit(null)}
                >
                  취소
                </Button>
                <Button
                  type="button"
                  className="flex-1"
                  disabled={busy !== undefined}
                  onClick={() => void changePassword()}
                >
                  {busy === "password" && <Loader2 className="animate-spin" />}
                  비밀번호 저장
                </Button>
              </div>
            </div>
          ) : (
            <Button
              type="button"
              variant="outline"
              className="w-full"
              disabled={busy !== undefined}
              onClick={() => edit("password")}
            >
              비밀번호 바꾸기
            </Button>
          )}
        </section>
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
          disabled={busy !== undefined}
          onClick={() => void logout()}
        >
          {busy === "logout" ? (
            <Loader2 className="animate-spin" />
          ) : (
            <LogOut />
          )}
          로그아웃
        </Button>
      </div>
    </FormDialog>
  )
}
