import { useEffect, useState, type FormEvent } from "react"
import { ArrowLeft, ArrowRight, CircleCheck, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Logo } from "@/components/common/blocks"
import { ApiError, errorMessage } from "@/api/errors"
import {
  DEV_FIXED_CODE,
  PASSWORD_RULE,
  SIGNED_UP_LOGIN_FAILED_MESSAGE,
  SignedUpLoginError,
  VERIFY_RESEND_INTERVAL_S,
  validPassword,
  type Account,
  type AuthApi,
  type VerificationPurpose,
} from "@/api/auth"
import { isMobilePhone, maskPhone, MOBILE_PHONE_MESSAGE } from "@/lib/phone"
import { Notice } from "@/components/common/notice"
import {
  PrivacyNoticePoints,
  TermsSummary,
} from "@/features/member/terms-blocks"
import { TERMS_DESCRIPTION } from "@/features/member/terms-text"

type Mode = "login" | "signup" | "reset"
export const PASSWORD_RESET_DONE_MESSAGE =
  "비밀번호를 다시 정했어요. 새 비밀번호로 로그인해 주세요. 다른 기기에서는 모두 로그아웃됐어요."
// 재설정 표가 만료됐거나 그 번호를 인증한 회원이 없을 때(CODE_EXPIRED). 가입 여부를 따로 알리지 않아요(06 ACC-5c)
export const RESET_CODE_EXPIRED_MESSAGE =
  "인증이 끝났거나 이 번호로 다시 정할 수 있는 계정이 없어요. 가입할 때 인증한 번호인지 확인하고 인증번호를 다시 받아 주세요."
// S-01 가입(휴대폰 → 계정 → 확인 항목) · S-02 로그인, 비밀번호 재설정(휴대폰 → 새 비밀번호)
export function AuthScreen({
  auth,
  onDone,
  onBack,
  initialMode = "login",
  notice,
}: {
  auth: AuthApi
  onDone: (a: Account) => void
  onBack?: () => void
  initialMode?: Exclude<Mode, "reset">
  notice?: string
}) {
  const [mode, setModeState] = useState<Mode>(initialMode)
  // 가입은 됐는데 이어진 로그인이 실패하면 로그인으로 옮기고 가입한 이메일을 채워 둬요
  const [signedUpEmail, setSignedUpEmail] = useState("")
  // 비밀번호를 다시 정하고 로그인으로 돌아왔을 때 한 번 보여 줘요
  const [resetDone, setResetDone] = useState(false)
  const setMode = (m: Mode) => {
    setResetDone(false)
    setModeState(m)
  }
  return (
    <div className="auth-shell">
      <div className="auth-brand">
        <Logo />
        <p>
          입주부터 퇴거까지, 집의 상태와 수리 · 합의를
          <br />
          세입자와 집주인이 같은 기록으로 남겨요.
        </p>
        <ul>
          <li>
            <CircleCheck size={16} aria-hidden />
            사진과 함께 남긴 기록은 고치거나 지우지 않고 덧붙여요
          </li>
          <li>
            <CircleCheck size={16} aria-hidden />
            상대가 확인한 사실만 정산의 근거가 돼요
          </li>
          <li>
            <CircleCheck size={16} aria-hidden />
            HomeStamp는 누가 낼지 판단하지 않아요
          </li>
        </ul>
      </div>
      <Card className="auth-card">
        <CardHeader>
          {onBack && (
            <Button
              variant="ghost"
              size="sm"
              className="self-start -ml-2"
              onClick={onBack}
            >
              <ArrowLeft />
              공유받은 기록으로
            </Button>
          )}
          <CardTitle className="text-2xl font-bold">
            {mode === "login"
              ? "로그인"
              : mode === "signup"
                ? "회원가입"
                : "비밀번호 재설정"}
          </CardTitle>
          <CardDescription>
            {mode === "login"
              ? "이메일과 비밀번호로 로그인해 주세요."
              : mode === "signup"
                ? "휴대폰 인증 한 번으로 가입해요. 세입자 쪽·집주인 쪽은 호실을 만들거나 연결할 때 정해져요."
                : "가입할 때 인증한 휴대폰 번호로 인증하면 새 비밀번호를 정할 수 있어요."}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          {mode === "reset" ? (
            <Button
              variant="ghost"
              size="sm"
              className="-ml-2"
              onClick={() => setMode("login")}
            >
              <ArrowLeft />
              로그인으로
            </Button>
          ) : (
            <Tabs value={mode} onValueChange={(v) => setMode(v as Mode)}>
              <TabsList className="w-full">
                <TabsTrigger value="login">로그인</TabsTrigger>
                <TabsTrigger value="signup">회원가입</TabsTrigger>
              </TabsList>
            </Tabs>
          )}
          {notice && <Notice>{notice}</Notice>}
          {auth.mode === "mock" && (
            <Notice tone="amber">
              개발 환경 · 서버에 연결되지 않아 계정은 이 브라우저 메모리에만
              저장되고, 인증 문자를 보내지 않아요. 인증번호는 {DEV_FIXED_CODE}
              으로 고정돼 있어요.
            </Notice>
          )}
          {mode === "login" ? (
            <LoginForm
              auth={auth}
              onDone={onDone}
              onSignup={() => setMode("signup")}
              onReset={() => setMode("reset")}
              defaultEmail={signedUpEmail}
              resetDone={resetDone}
            />
          ) : mode === "reset" ? (
            <ResetPasswordForm
              auth={auth}
              onReset={() => {
                setModeState("login")
                setResetDone(true)
              }}
            />
          ) : (
            <SignupForm
              auth={auth}
              onDone={onDone}
              onSignedUp={(email) => {
                setSignedUpEmail(email)
                setMode("login")
              }}
            />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function LoginForm({
  auth,
  onDone,
  onSignup,
  onReset,
  defaultEmail = "",
  resetDone = false,
}: {
  auth: AuthApi
  onDone: (a: Account) => void
  onSignup: () => void
  onReset: () => void
  defaultEmail?: string
  resetDone?: boolean
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    setBusy(true)
    setError("")
    try {
      onDone(
        await auth.login(
          String(f.get("email") || ""),
          String(f.get("password") || ""),
        ),
      )
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form className="space-y-4" onSubmit={submit}>
      {defaultEmail && <Notice>{SIGNED_UP_LOGIN_FAILED_MESSAGE}</Notice>}
      {resetDone && <Notice>{PASSWORD_RESET_DONE_MESSAGE}</Notice>}
      <div className="field">
        <label htmlFor="login-email">이메일</label>
        <Input
          id="login-email"
          name="email"
          type="email"
          autoComplete="email"
          defaultValue={defaultEmail}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="login-password">비밀번호</label>
        <Input
          id="login-password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
        />
      </div>
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <Button type="submit" size="lg" className="w-full" disabled={busy}>
        {busy && <Loader2 className="animate-spin" />}로그인
      </Button>
      <p className="muted text-center text-sm">
        <Button type="button" variant="link" className="px-1" onClick={onReset}>
          비밀번호를 잊었나요?
        </Button>
      </p>
      <p className="muted text-center text-sm">
        아직 계정이 없나요?{" "}
        <Button
          type="button"
          variant="link"
          className="px-1"
          onClick={onSignup}
        >
          회원가입
        </Button>
      </p>
    </form>
  )
}

function SignupForm({
  auth,
  onDone,
  onSignedUp,
}: {
  auth: AuthApi
  onDone: (a: Account) => void
  onSignedUp: (email: string) => void
}) {
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [phone, setPhone] = useState("")
  const [phoneToken, setPhoneToken] = useState("")
  const [account, setAccount] = useState({
    email: "",
    password: "",
    password2: "",
    name: "",
  })
  const [agree, setAgree] = useState({
    age14: false,
    terms: false,
    privacyNotice: false,
  })
  const [termsOpen, setTermsOpen] = useState(false)
  const { busy, error, setError, run } = useTask()
  function next(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setError("")
    if (!validPassword(account.password)) {
      setError(`비밀번호는 ${PASSWORD_RULE}`)
      return
    }
    if (account.password !== account.password2) {
      setError("비밀번호 확인이 일치하지 않아요.")
      return
    }
    setStep(3)
  }
  const signup = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (!agree.age14) {
      setError("만 14세 이상만 가입할 수 있어요.")
      return
    }
    run(async () => {
      try {
        onDone(
          await auth.signup({
            email: account.email,
            password: account.password,
            name: account.name,
            phoneToken,
            agreements: agree,
          }),
        )
      } catch (e) {
        // 가입은 끝났고 이어진 로그인만 실패했으면 가입을 되풀이하지 않고 로그인으로 옮겨요
        if (e instanceof SignedUpLoginError) {
          onSignedUp(e.email)
          return
        }
        // 인증 표가 만료됐거나 이미 쓰였으면(CODE_EXPIRED, 06 ACC-1f·1l) 휴대폰 인증부터 다시 해요. 입력한 계정 정보는 남겨요
        if (e instanceof ApiError && e.code === "CODE_EXPIRED") {
          setPhoneToken("")
          setStep(1)
          throw new Error(
            "휴대폰 인증 시간이 지났어요. 인증번호를 다시 받아 주세요.",
            { cause: e },
          )
        }
        throw e
      }
    })
  }

  return (
    <div className="space-y-4">
      <ol className="auth-steps" aria-label="가입 단계">
        {["휴대폰 인증", "계정 정보", "확인 항목"].map((s, i) => (
          <li
            key={s}
            className={
              i + 1 < step ? "is-done" : i + 1 === step ? "is-current" : ""
            }
            aria-current={i + 1 === step ? "step" : undefined}
          >
            <span aria-hidden>{i + 1 < step ? "✓" : i + 1}</span>
            {s}
          </li>
        ))}
      </ol>
      {step === 1 && (
        <PhoneCodeStep
          auth={auth}
          purpose="SIGNUP"
          idPrefix="su"
          phone={phone}
          onPhoneChange={setPhone}
          busy={busy}
          run={run}
          hint="상대가 이 번호로 초대하면 연결할 수 있어요."
          onVerified={(token) => {
            setPhoneToken(token)
            setStep(2)
          }}
        />
      )}
      {step === 2 && (
        <form className="space-y-4" onSubmit={next}>
          <Notice>{maskPhone(phone)} 인증을 마쳤어요.</Notice>
          <div className="field">
            <label htmlFor="su-email">이메일 · 로그인 아이디</label>
            <Input
              id="su-email"
              type="email"
              autoComplete="email"
              required
              value={account.email}
              onChange={(e) =>
                setAccount({ ...account, email: e.target.value })
              }
            />
          </div>
          <div className="field">
            <label htmlFor="su-pw">비밀번호</label>
            <Input
              id="su-pw"
              type="password"
              autoComplete="new-password"
              required
              value={account.password}
              onChange={(e) =>
                setAccount({ ...account, password: e.target.value })
              }
            />
            <small className="muted">{PASSWORD_RULE}</small>
          </div>
          <div className="field">
            <label htmlFor="su-pw2">비밀번호 확인</label>
            <Input
              id="su-pw2"
              type="password"
              autoComplete="new-password"
              required
              value={account.password2}
              onChange={(e) =>
                setAccount({ ...account, password2: e.target.value })
              }
            />
          </div>
          <div className="field">
            <label htmlFor="su-name">이름 · 상대에게는 가려서 보여요</label>
            <Input
              id="su-name"
              autoComplete="name"
              required
              maxLength={20}
              value={account.name}
              onChange={(e) => setAccount({ ...account, name: e.target.value })}
            />
          </div>
          <Button type="submit" size="lg" className="w-full">
            다음
            <ArrowRight />
          </Button>
        </form>
      )}
      {step === 3 && (
        <form className="space-y-4" onSubmit={signup}>
          <label className="consent">
            <Checkbox
              checked={agree.age14}
              onCheckedChange={(v) => setAgree({ ...agree, age14: Boolean(v) })}
            />
            (필수) 만 14세 이상이에요
          </label>
          <div className="flex items-center justify-between gap-2">
            <label className="consent">
              <Checkbox
                checked={agree.terms}
                onCheckedChange={(v) =>
                  setAgree({ ...agree, terms: Boolean(v) })
                }
              />
              (필수) 이용약관에 동의해요
            </label>
            <Button
              type="button"
              variant="link"
              size="sm"
              onClick={() => setTermsOpen(true)}
            >
              보기
            </Button>
          </div>
          <div className="auth-notice">
            <strong>개인정보 처리 안내</strong>
            <PrivacyNoticePoints />
            <label className="consent mt-2">
              <Checkbox
                checked={agree.privacyNotice}
                onCheckedChange={(v) =>
                  setAgree({ ...agree, privacyNotice: Boolean(v) })
                }
              />
              (필수) 확인했어요
            </label>
          </div>
          <div className="flex gap-2">
            <Button type="button" variant="outline" onClick={() => setStep(2)}>
              <ArrowLeft />
              이전
            </Button>
            <Button
              type="submit"
              size="lg"
              className="flex-1"
              disabled={busy || !agree.terms || !agree.privacyNotice}
            >
              {busy && <Loader2 className="animate-spin" />}가입하기
            </Button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <Dialog open={termsOpen} onOpenChange={setTermsOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>이용약관</DialogTitle>
            <DialogDescription>{TERMS_DESCRIPTION}</DialogDescription>
          </DialogHeader>
          <TermsSummary />
          <Button
            onClick={() => {
              setAgree((a) => ({ ...a, terms: true }))
              setTermsOpen(false)
            }}
          >
            동의하고 닫기
          </Button>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// 보내기·확인 같은 일 하나를 돌리며 기다림·오류 한 줄을 들고 있어요(가입·재설정이 같이 써요)
function useTask() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function run(task: () => Promise<void>) {
    setBusy(true)
    setError("")
    try {
      await task()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, setError, run }
}

// 휴대폰 인증 화면(08 "휴대폰 인증 화면" 표). 가입과 비밀번호 재설정이 같이 써요.
// 보낸 뒤 60초는 다시 받기를 기다리고, 하루 횟수를 다 쓰면 [다시 받기]를 숨겨요.
// 인증을 마치면 인증 표를 넘기고, 처음부터 다시 하려면 부르는 쪽이 이 단계를 다시 그려요.
function PhoneCodeStep({
  auth,
  purpose,
  idPrefix,
  phone,
  onPhoneChange,
  busy,
  run,
  hint,
  onVerified,
}: {
  auth: AuthApi
  purpose: VerificationPurpose
  idPrefix: string
  phone: string
  onPhoneChange: (phone: string) => void
  busy: boolean
  run: (task: () => Promise<void>) => Promise<void>
  hint?: string
  onVerified: (phoneToken: string) => void
}) {
  const [code, setCode] = useState("")
  const [verification, setVerification] = useState<{
    id: string
    expiresAt: number
    delivered: boolean
  }>()
  const [now, setNow] = useState(() => Date.now())
  // [다시 받기]를 누를 수 있는 시각(verify.resend.interval)과 하루 횟수를 다 썼는지(DAILY_LIMIT)
  const [resendAt, setResendAt] = useState(0)
  const [dailyLimit, setDailyLimit] = useState(false)
  // 인증번호 남은 시간과 [다시 받기] 대기 시간을 1초마다 다시 그려요. 첫 요청부터 RATE_LIMITED라
  // 아직 인증번호가 없어도 대기 시간은 흘러야 해요(06 4-1 phone-verifications 재발송 간격, #420)
  useEffect(() => {
    if (!verification && resendAt <= Date.now()) return
    const t = window.setInterval(() => {
      const n = Date.now()
      setNow(n)
      if (!verification && resendAt <= n) window.clearInterval(t)
    }, 1000)
    return () => window.clearInterval(t)
  }, [verification, resendAt])
  const left = verification
    ? Math.max(0, Math.ceil((verification.expiresAt - now) / 1000))
    : 0
  const resendLeft = Math.max(0, Math.ceil((resendAt - now) / 1000))
  const sendCode = () =>
    run(async () => {
      if (!isMobilePhone(phone)) throw new Error(MOBILE_PHONE_MESSAGE)
      try {
        const v = await auth.requestPhoneCode(phone, purpose)
        setVerification({
          id: v.verificationId,
          expiresAt: Date.parse(v.expiresAt),
          delivered: v.delivered,
        })
        setResendAt(Date.now() + VERIFY_RESEND_INTERVAL_S * 1000)
        setNow(Date.now())
        setCode("")
      } catch (e) {
        if (e instanceof ApiError && e.code === "RATE_LIMITED")
          setResendAt(
            Date.now() + (e.retryAfter ?? VERIFY_RESEND_INTERVAL_S) * 1000,
          )
        // 보내지 못했으면 바로 다시 받을 수 있어요(ACC-1j)
        if (e instanceof ApiError && e.code === "SMS_SEND_FAILED")
          setResendAt(0)
        if (e instanceof ApiError && e.code === "DAILY_LIMIT")
          setDailyLimit(true)
        setNow(Date.now())
        throw e
      }
    })
  const verify = () =>
    run(async () => {
      if (!verification) return
      const r = await auth.verifyPhoneCode(verification.id, code)
      onVerified(r.phoneToken)
    })
  return (
    <div className="space-y-4">
      <div className="field">
        <label htmlFor={`${idPrefix}-phone`}>휴대폰 번호</label>
        <div className="flex gap-2">
          <Input
            id={`${idPrefix}-phone`}
            type="tel"
            inputMode="numeric"
            autoComplete="tel"
            placeholder="01012345678"
            value={phone}
            disabled={busy}
            onChange={(e) => {
              onPhoneChange(e.target.value)
              setVerification(undefined)
            }}
          />
          {!dailyLimit && (
            <Button
              type="button"
              variant="outline"
              className="shrink-0"
              disabled={busy || !phone || resendLeft > 0}
              onClick={sendCode}
            >
              {verification ? "다시 받기" : "인증번호 받기"}
            </Button>
          )}
        </div>
        {resendLeft > 0 && !dailyLimit && (
          <small className="muted" aria-live="polite">
            {resendLeft}초 뒤 다시 받을 수 있어요
          </small>
        )}
        {hint && <small className="muted">{hint}</small>}
      </div>
      {verification && (
        <>
          <Notice tone={verification.delivered ? "info" : "amber"}>
            {verification.delivered
              ? `${maskPhone(phone)}로 인증번호를 보냈어요 · 5분 안에 넣어 주세요. 문자가 오지 않으면 다시 받아 주세요.`
              : `개발 환경이라 문자를 보내지 않았어요. 인증번호 ${DEV_FIXED_CODE}을 5분 안에 넣어 주세요.`}
          </Notice>
          <div className="field">
            <label htmlFor={`${idPrefix}-code`}>
              인증번호 6자리{" "}
              <span className="muted">
                {left
                  ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")} 남음`
                  : "시간 초과 · 다시 받아 주세요"}
              </span>
            </label>
            <Input
              id={`${idPrefix}-code`}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            />
          </div>
          <Button
            type="button"
            className="w-full"
            size="lg"
            disabled={busy || code.length !== 6 || !left}
            onClick={verify}
          >
            {busy && <Loader2 className="animate-spin" />}인증하기
          </Button>
        </>
      )}
    </div>
  )
}

// 비밀번호 재설정(06 S-02, ACC-5b·5c): 휴대폰 인증(RESET) → 새 비밀번호. 끝나면 모든 기기가 로그아웃되고 로그인으로 돌아가요.
// 회원이 없는 번호도 인증 문자는 똑같이 가고, 재설정에서 CODE_EXPIRED라 가입 여부를 따로 알리지 않아요.
function ResetPasswordForm({
  auth,
  onReset,
}: {
  auth: AuthApi
  onReset: () => void
}) {
  const [phone, setPhone] = useState("")
  const [phoneToken, setPhoneToken] = useState("")
  const [password, setPassword] = useState({ next: "", next2: "" })
  const { busy, error, setError, run } = useTask()
  const step = phoneToken ? 2 : 1
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setError("")
    if (!validPassword(password.next)) {
      setError(`비밀번호는 ${PASSWORD_RULE}`)
      return
    }
    if (password.next !== password.next2) {
      setError("비밀번호 확인이 일치하지 않아요.")
      return
    }
    run(async () => {
      try {
        await auth.resetPassword(phoneToken, password.next)
      } catch (e) {
        // 표가 만료됐거나 이미 쓰였거나, 그 번호를 인증한 회원이 없어요. 인증부터 다시 하고 넣은 비밀번호는 남겨요
        if (e instanceof ApiError && e.code === "CODE_EXPIRED") {
          setPhoneToken("")
          throw new Error(RESET_CODE_EXPIRED_MESSAGE, { cause: e })
        }
        throw e
      }
      onReset()
    })
  }
  return (
    <div className="space-y-4">
      <ol className="auth-steps" aria-label="재설정 단계">
        {["휴대폰 인증", "새 비밀번호"].map((s, i) => (
          <li
            key={s}
            className={
              i + 1 < step ? "is-done" : i + 1 === step ? "is-current" : ""
            }
            aria-current={i + 1 === step ? "step" : undefined}
          >
            <span aria-hidden>{i + 1 < step ? "✓" : i + 1}</span>
            {s}
          </li>
        ))}
      </ol>
      {step === 1 ? (
        <PhoneCodeStep
          auth={auth}
          purpose="RESET"
          idPrefix="rs"
          phone={phone}
          onPhoneChange={setPhone}
          busy={busy}
          run={run}
          hint="가입할 때 인증한 번호를 넣어 주세요."
          onVerified={setPhoneToken}
        />
      ) : (
        <form className="space-y-4" onSubmit={submit}>
          <Notice>{maskPhone(phone)} 인증을 마쳤어요.</Notice>
          <div className="field">
            <label htmlFor="rs-pw">새 비밀번호</label>
            <Input
              id="rs-pw"
              type="password"
              autoComplete="new-password"
              required
              value={password.next}
              onChange={(e) =>
                setPassword({ ...password, next: e.target.value })
              }
            />
            <small className="muted">{PASSWORD_RULE}</small>
          </div>
          <div className="field">
            <label htmlFor="rs-pw2">새 비밀번호 확인</label>
            <Input
              id="rs-pw2"
              type="password"
              autoComplete="new-password"
              required
              value={password.next2}
              onChange={(e) =>
                setPassword({ ...password, next2: e.target.value })
              }
            />
          </div>
          <small className="muted">
            다시 정하면 모든 기기에서 로그아웃돼요.
          </small>
          <Button type="submit" size="lg" className="w-full" disabled={busy}>
            {busy && <Loader2 className="animate-spin" />}비밀번호 다시 정하기
          </Button>
        </form>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
    </div>
  )
}
