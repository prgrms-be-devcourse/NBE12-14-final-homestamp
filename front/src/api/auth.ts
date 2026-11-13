import { API_BASE_URL, API_MODE } from "@/config"
import { ApiError } from "./errors"
import { endpoints } from "./endpoints"
import { createHttpClient, type HttpRequest } from "./http-client"

// 액세스 토큰은 메모리에만 둡니다(명세: 리프레시는 HttpOnly 쿠키).
let accessToken: string | undefined
export const tokenStore = {
  get: () => accessToken,
  set: (t?: string) => {
    accessToken = t
  },
}
export function createAuth(): AuthApi {
  return API_MODE === "http"
    ? createHttpAuth(
        createHttpClient({ baseUrl: API_BASE_URL, getToken: tokenStore.get }),
        tokenStore,
      )
    : createLocalAuth()
}

// 계정·인증(명세 4-1, S-01·S-02). 화면은 이 모양만 씁니다.
export interface Account {
  id: string
  email: string
  name: string
  phone: string
}
export interface PhoneVerification {
  verificationId: string
  expiresAt: string
  delivered: boolean
}
export interface SignupInput {
  email: string
  password: string
  name: string
  phoneToken: string
  // CONS-1: 확인 기록 세 줄(AGE_14_CONFIRMED·TERMS_AGREED·PRIVACY_NOTICE_ACK)
  agreements: { age14: boolean; terms: boolean; privacyNotice: boolean }
}
export interface AuthApi {
  mode: "mock" | "http"
  requestPhoneCode(phone: string): Promise<PhoneVerification>
  verifyPhoneCode(
    verificationId: string,
    code: string,
  ): Promise<{ phoneToken: string }>
  signup(input: SignupInput): Promise<Account>
  login(email: string, password: string): Promise<Account>
  // 새로고침 뒤 로그인 유지: 리프레시 쿠키로 다시 받은 토큰으로 내 정보를 불러와요. 없으면 null.
  restore(): Promise<Account | null>
  logout(): Promise<void>
  getToken(): string | undefined
}

// 임시(개발용) 고정 인증번호. 서버 없이 개발할 때(createLocalAuth)만 쓰여요.
export const DEV_FIXED_CODE = "123456"
// 임시(개발용) 확인 계정. 서버 없이 개발할 때(createLocalAuth)만 미리 만들어 둬요. 호실 쪽은 개발용 호실 연결(api/mock/dev-seed)로 정해져요.
export const DEV_ACCOUNTS = [
  {
    id: "dev-tenant",
    email: "test@gmail.com",
    password: "aaaa1111",
    name: "김냥냥",
    phone: "01011112222",
  },
  {
    id: "dev-landlord",
    email: "test1@gmail.com",
    password: "aaaa1111",
    name: "박주인",
    phone: "01012341234",
  },
] as const
export const PASSWORD_RULE = "8자 이상, 영문과 숫자를 함께 써 주세요."
export const validPassword = (p: string) =>
  p.length >= 8 && /[A-Za-z]/.test(p) && /\d/.test(p)
export const normalizePhone = (p: string) => p.replace(/\D/g, "")
export const validPhone = (p: string) => /^010\d{8}$/.test(normalizePhone(p))
export const maskPhone = (p: string) => {
  const n = normalizePhone(p)
  return n.length === 11 ? `${n.slice(0, 3)}-••••-${n.slice(7)}` : n
}

/* ── 백엔드 연결 ── */
export function createHttpAuth(
  request: HttpRequest,
  token: { get: () => string | undefined; set: (t?: string) => void },
): AuthApi {
  const me = () => request<Account>("GET", endpoints.me)
  return {
    mode: "http",
    getToken: token.get,
    async requestPhoneCode(phone) {
      const r = await request<{ verificationId: string; expiresAt: string }>(
        "POST",
        endpoints.phoneVerifications,
        { body: { phone: normalizePhone(phone), purpose: "SIGNUP" } },
      )
      return { ...r, delivered: true }
    },
    verifyPhoneCode: (vid, code) =>
      request<{ phoneToken: string }>("POST", endpoints.verifyPhone(vid), {
        body: { code },
      }),
    async signup(input) {
      await request("POST", endpoints.signup, { body: input })
      return this.login(input.email, input.password)
    },
    async login(email, password) {
      const r = await request<{ accessToken: string }>(
        "POST",
        endpoints.login,
        { body: { email, password }, idempotent: false },
      )
      token.set(r.accessToken)
      return me()
    },
    async restore() {
      try {
        const r = await request<{ accessToken: string }>(
          "POST",
          endpoints.refresh,
          { idempotent: false },
        )
        token.set(r.accessToken)
        return await me()
      } catch {
        token.set(undefined)
        return null
      }
    },
    async logout() {
      try {
        await request("POST", endpoints.logout, { idempotent: false })
      } finally {
        token.set(undefined)
      }
    },
  }
}

/* ── 서버 없이 개발할 때: 브라우저 메모리 계정 저장소 ──
   문자를 실제로 보내지 않으므로 delivered=false를 돌려주고, 화면은 '문자를 보내지 않았어요'를 그대로 알립니다.
   인증번호는 개발자 콘솔에만 남깁니다. 규칙(3분·5회, 이메일·번호 중복, 비밀번호)은 명세와 같게 검사해요. */
interface LocalAccount extends Account {
  password: string
}
export function createLocalAuth(): AuthApi {
  const accounts: LocalAccount[] = DEV_ACCOUNTS.map((a) => ({ ...a }))
  const codes = new Map<
    string,
    { phone: string; code: string; expires: number; tries: number }
  >()
  const tokens = new Map<string, string>()
  let current: Account | null = null
  const fail = (code: string, detail: string, status = 400): never => {
    throw new ApiError({ status, code, detail })
  }
  const strip = (a: LocalAccount): Account => ({
    id: a.id,
    email: a.email,
    name: a.name,
    phone: a.phone,
  })
  return {
    mode: "mock",
    getToken: () => current?.id,
    async requestPhoneCode(phone) {
      const n = normalizePhone(phone)
      if (!validPhone(n))
        fail(
          "VALIDATION_FAILED",
          "010으로 시작하는 휴대폰 번호 11자리를 입력해 주세요.",
        )
      if (accounts.some((a) => a.phone === n))
        fail(
          "PHONE_TAKEN",
          "이미 가입에 쓰인 번호예요. 로그인하거나 비밀번호를 재설정해 주세요.",
          409,
        )
      const verificationId = crypto.randomUUID()
      // 임시(개발용): 확인을 쉽게 하려고 인증번호를 123456으로 고정해요. 서버 연결 전에 아래 무작위 코드로 되돌려 주세요.
      // const code = String(Math.floor(100000 + Math.random() * 900000))
      const code = DEV_FIXED_CODE
      codes.set(verificationId, {
        phone: n,
        code,
        expires: Date.now() + 3 * 60_000,
        tries: 0,
      })
      console.info(`[HomeStamp 개발 서버] ${n} 인증번호: ${code}`)
      return {
        verificationId,
        expiresAt: new Date(Date.now() + 3 * 60_000).toISOString(),
        delivered: false,
      }
    },
    async verifyPhoneCode(vid, code) {
      const v = codes.get(vid)
      if (!v || v.expires < Date.now())
        fail(
          "CODE_EXPIRED",
          "인증번호 유효 시간(3분)이 지났어요. 다시 받아 주세요.",
        )
      if (v!.tries >= 5)
        fail(
          "CODE_EXPIRED",
          "5번 틀려서 이 인증번호는 쓸 수 없어요. 다시 받아 주세요.",
        )
      if (v!.code !== code.trim()) {
        v!.tries++
        fail("CODE_INVALID", `인증번호가 맞지 않아요. (${v!.tries}/5)`)
      }
      codes.delete(vid)
      const phoneToken = crypto.randomUUID()
      tokens.set(phoneToken, v!.phone)
      return { phoneToken }
    },
    async signup(input) {
      const phone = tokens.get(input.phoneToken)
      if (!phone) fail("CODE_EXPIRED", "휴대폰 인증을 다시 해 주세요.")
      if (!input.agreements.age14)
        fail("AGE_CONFIRM_REQUIRED", "만 14세 이상만 가입할 수 있어요.")
      if (!input.agreements.terms || !input.agreements.privacyNotice)
        fail(
          "TERMS_REQUIRED",
          "이용약관 동의와 개인정보 처리 안내 확인이 필요해요.",
        )
      const email = input.email.trim().toLowerCase()
      if (accounts.some((a) => a.email === email))
        fail("EMAIL_TAKEN", "이미 가입한 이메일이에요.", 409)
      if (!validPassword(input.password))
        fail("VALIDATION_FAILED", `비밀번호는 ${PASSWORD_RULE}`)
      const account: LocalAccount = {
        id: crypto.randomUUID(),
        email,
        name: input.name.trim(),
        phone: phone!,
        password: input.password,
      }
      accounts.push(account)
      tokens.delete(input.phoneToken)
      current = strip(account)
      return current
    },
    async login(email, password) {
      const a = accounts.find(
        (x) =>
          x.email === email.trim().toLowerCase() && x.password === password,
      )
      if (!a) fail("LOGIN_FAILED", "이메일이나 비밀번호가 맞지 않아요.", 401)
      current = strip(a!)
      return current
    },
    async restore() {
      return current
    },
    async logout() {
      current = null
    },
  }
}
