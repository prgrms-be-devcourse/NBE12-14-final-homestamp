import { useEffect, useState, type FormEvent } from 'react'
import { ArrowLeft, ArrowRight, CircleCheck, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Logo } from '@/components/prototype'
import { errorMessage } from '@/api/errors'
import { DEV_FIXED_CODE, PASSWORD_RULE, maskPhone, validPassword, validPhone, type Account, type AuthApi } from '@/api/auth'
import { Notice } from '@/components/notice'

type Mode = 'login' | 'signup'
// S-01 가입(휴대폰 → 계정 → 확인 항목) · S-02 로그인
export function AuthScreen({ auth, onDone, onBack, initialMode = 'login', notice }: { auth: AuthApi; onDone: (a: Account) => void; onBack?: () => void; initialMode?: Mode; notice?: string }) {
  const [mode, setMode] = useState<Mode>(initialMode)
  return <div className="auth-shell">
    <div className="auth-brand"><Logo /><p>입주부터 퇴거까지, 집의 상태와 수리 · 합의를<br />세입자와 집주인이 같은 기록으로 남겨요.</p>
      <ul><li><CircleCheck size={16} aria-hidden />사진과 함께 남긴 기록은 고치거나 지우지 않고 덧붙여요</li><li><CircleCheck size={16} aria-hidden />상대가 확인한 사실만 정산의 근거가 돼요</li><li><CircleCheck size={16} aria-hidden />HomeStamp는 누가 낼지 판단하지 않아요</li></ul></div>
    <Card className="auth-card">
      <CardHeader>
        {onBack && <Button variant="ghost" size="sm" className="self-start -ml-2" onClick={onBack}><ArrowLeft />공유받은 기록으로</Button>}
        <CardTitle className="text-2xl font-bold">{mode === 'login' ? '로그인' : '회원가입'}</CardTitle>
        <CardDescription>{mode === 'login' ? '이메일과 비밀번호로 로그인해 주세요.' : '휴대폰 인증 한 번으로 가입해요. 임차인·임대인은 호실을 만들거나 연결할 때 정해져요.'}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        <Tabs value={mode} onValueChange={v => setMode(v as Mode)}><TabsList className="w-full"><TabsTrigger value="login">로그인</TabsTrigger><TabsTrigger value="signup">회원가입</TabsTrigger></TabsList></Tabs>
        {notice && <Notice>{notice}</Notice>}
        {auth.mode === 'mock' && <Notice tone="amber">개발 환경 · 서버에 연결되지 않아 계정은 이 브라우저 메모리에만 저장되고, 인증 문자를 보내지 않아요. 인증번호는 {DEV_FIXED_CODE}으로 고정돼 있어요.</Notice>}
        {mode === 'login' ? <LoginForm auth={auth} onDone={onDone} onSignup={() => setMode('signup')} /> : <SignupForm auth={auth} onDone={onDone} />}
      </CardContent>
    </Card>
  </div>
}

function LoginForm({ auth, onDone, onSignup }: { auth: AuthApi; onDone: (a: Account) => void; onSignup: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const f = new FormData(e.currentTarget)
    setBusy(true); setError('')
    try { onDone(await auth.login(String(f.get('email') || ''), String(f.get('password') || ''))) } catch (err) { setError(errorMessage(err)) } finally { setBusy(false) }
  }
  return <form className="space-y-4" onSubmit={submit}>
    <div className="field"><label htmlFor="login-email">이메일</label><Input id="login-email" name="email" type="email" autoComplete="email" required /></div>
    <div className="field"><label htmlFor="login-password">비밀번호</label><Input id="login-password" name="password" type="password" autoComplete="current-password" required /></div>
    {error && <p role="alert" className="form-error">{error}</p>}
    <Button type="submit" size="lg" className="w-full" disabled={busy}>{busy && <Loader2 className="animate-spin" />}로그인</Button>
    <p className="muted text-center text-sm">아직 계정이 없나요? <Button type="button" variant="link" className="px-1" onClick={onSignup}>회원가입</Button></p>
  </form>
}

const TERMS = ['같은 호실의 상대 쪽에게 내가 남긴 사진·설명·행동 기록과 가린 이름(예: 정○○)이 보여요.', '기록은 고치거나 지우지 않고 덧붙이며, 정산 완료 뒤 1년 동안 읽기 전용으로 보관한 뒤 삭제해요.', '탈퇴하면 혼자 쓴 기록과 계정은 지워지고, 상대와 함께 만든 기록은 이름을 가린 채 보관 기간까지 남아요.', '주민등록번호는 받지 않아요. 계약서·신분증·통장 사진은 올리지 마세요.']

function SignupForm({ auth, onDone }: { auth: AuthApi; onDone: (a: Account) => void }) {
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [verification, setVerification] = useState<{ id: string; expiresAt: number; delivered: boolean }>()
  const [phoneToken, setPhoneToken] = useState('')
  const [account, setAccount] = useState({ email: '', password: '', password2: '', name: '' })
  const [agree, setAgree] = useState({ age14: false, terms: false, privacyNotice: false })
  const [termsOpen, setTermsOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { if (!verification || phoneToken) return; const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t) }, [verification, phoneToken])
  const left = verification ? Math.max(0, Math.ceil((verification.expiresAt - now) / 1000)) : 0
  async function run(task: () => Promise<void>) { setBusy(true); setError(''); try { await task() } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) } }
  const sendCode = () => run(async () => {
    if (!validPhone(phone)) throw new Error('010으로 시작하는 휴대폰 번호 11자리를 입력해 주세요.')
    const v = await auth.requestPhoneCode(phone)
    setVerification({ id: v.verificationId, expiresAt: Date.parse(v.expiresAt), delivered: v.delivered }); setNow(Date.now()); setCode('')
  })
  const verify = () => run(async () => { if (!verification) return; const r = await auth.verifyPhoneCode(verification.id, code); setPhoneToken(r.phoneToken); setStep(2) })
  function next(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setError('')
    if (!validPassword(account.password)) { setError(`비밀번호는 ${PASSWORD_RULE}`); return }
    if (account.password !== account.password2) { setError('비밀번호 확인이 일치하지 않아요.'); return }
    setStep(3)
  }
  const signup = (e: FormEvent<HTMLFormElement>) => { e.preventDefault(); if (!agree.age14) { setError('만 14세 이상만 가입할 수 있어요.'); return } run(async () => onDone(await auth.signup({ email: account.email, password: account.password, name: account.name, phoneToken, agreements: agree }))) }

  return <div className="space-y-4">
    <ol className="auth-steps" aria-label="가입 단계">{['휴대폰 인증', '계정 정보', '확인 항목'].map((s, i) => <li key={s} className={i + 1 < step ? 'is-done' : i + 1 === step ? 'is-current' : ''} aria-current={i + 1 === step ? 'step' : undefined}><span aria-hidden>{i + 1 < step ? '✓' : i + 1}</span>{s}</li>)}</ol>
    {step === 1 && <div className="space-y-4">
      <div className="field"><label htmlFor="su-phone">휴대폰 번호</label><div className="flex gap-2"><Input id="su-phone" type="tel" inputMode="numeric" autoComplete="tel" placeholder="01012345678" value={phone} disabled={busy} onChange={e => { setPhone(e.target.value); setVerification(undefined) }} /><Button type="button" variant="outline" className="shrink-0" disabled={busy || !phone} onClick={sendCode}>{verification ? '다시 받기' : '인증번호 받기'}</Button></div><small className="muted">번호 하나로는 계정 하나만 만들 수 있어요. 상대가 이 번호로 초대하면 연결할 수 있어요.</small></div>
      {verification && <>
        <Notice tone={verification.delivered ? 'info' : 'amber'}>{verification.delivered ? `${maskPhone(phone)}로 인증 문자를 보냈어요.` : `개발 환경이라 문자를 보내지 않았어요. 인증번호 ${DEV_FIXED_CODE}을 입력해 주세요.`}</Notice>
        <div className="field"><label htmlFor="su-code">인증번호 6자리 <span className="muted">{left ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} 남음` : '시간 초과 · 다시 받아 주세요'}</span></label><Input id="su-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} /></div>
        <Button type="button" className="w-full" size="lg" disabled={busy || code.length !== 6 || !left} onClick={verify}>{busy && <Loader2 className="animate-spin" />}인증하기</Button>
      </>}
    </div>}
    {step === 2 && <form className="space-y-4" onSubmit={next}>
      <Notice>{maskPhone(phone)} 인증을 마쳤어요.</Notice>
      <div className="field"><label htmlFor="su-email">이메일 · 로그인 아이디</label><Input id="su-email" type="email" autoComplete="email" required value={account.email} onChange={e => setAccount({ ...account, email: e.target.value })} /></div>
      <div className="field"><label htmlFor="su-pw">비밀번호</label><Input id="su-pw" type="password" autoComplete="new-password" required value={account.password} onChange={e => setAccount({ ...account, password: e.target.value })} /><small className="muted">{PASSWORD_RULE}</small></div>
      <div className="field"><label htmlFor="su-pw2">비밀번호 확인</label><Input id="su-pw2" type="password" autoComplete="new-password" required value={account.password2} onChange={e => setAccount({ ...account, password2: e.target.value })} /></div>
      <div className="field"><label htmlFor="su-name">이름 · 상대에게는 가려서 보여요</label><Input id="su-name" autoComplete="name" required maxLength={20} value={account.name} onChange={e => setAccount({ ...account, name: e.target.value })} /></div>
      <Button type="submit" size="lg" className="w-full">다음<ArrowRight /></Button>
    </form>}
    {step === 3 && <form className="space-y-4" onSubmit={signup}>
      <label className="consent"><Checkbox checked={agree.age14} onCheckedChange={v => setAgree({ ...agree, age14: Boolean(v) })} />(필수) 만 14세 이상이에요</label>
      <div className="flex items-center justify-between gap-2"><label className="consent"><Checkbox checked={agree.terms} onCheckedChange={v => setAgree({ ...agree, terms: Boolean(v) })} />(필수) 이용약관에 동의해요</label><Button type="button" variant="link" size="sm" onClick={() => setTermsOpen(true)}>보기</Button></div>
      <div className="auth-notice"><strong>개인정보 처리 안내</strong><ul>{TERMS.map(t => <li key={t}>{t}</li>)}</ul><label className="consent mt-2"><Checkbox checked={agree.privacyNotice} onCheckedChange={v => setAgree({ ...agree, privacyNotice: Boolean(v) })} />(필수) 확인했어요</label></div>
      <div className="flex gap-2"><Button type="button" variant="outline" onClick={() => setStep(2)}><ArrowLeft />이전</Button><Button type="submit" size="lg" className="flex-1" disabled={busy || !agree.terms || !agree.privacyNotice}>{busy && <Loader2 className="animate-spin" />}가입하기</Button></div>
    </form>}
    {error && <p role="alert" className="form-error">{error}</p>}
    <Dialog open={termsOpen} onOpenChange={setTermsOpen}><DialogContent><DialogHeader><DialogTitle>이용약관</DialogTitle><DialogDescription>HomeStamp는 세입자와 집주인이 같은 기록을 함께 보도록 돕는 서비스예요.</DialogDescription></DialogHeader>
      <div className="space-y-2 text-sm leading-relaxed"><p>HomeStamp는 누가 얼마를 낼지 판단하지 않아요. 사람이 입력하고 상대가 확인한 사실만 남겨요.</p><p>합의는 양쪽이 같은 안에 동의해야만 생기고, 사실(고쳤다·냈다·받았다)은 혼자서도 남길 수 있어요.</p><p>약관 전문은 서비스 정식 공개 때 이 화면에 게시돼요.</p></div>
      <Button onClick={() => { setAgree(a => ({ ...a, terms: true })); setTermsOpen(false) }}>동의하고 닫기</Button></DialogContent></Dialog>
  </div>
}
