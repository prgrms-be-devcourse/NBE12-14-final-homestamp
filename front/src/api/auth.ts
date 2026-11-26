import { API_BASE_URL, API_MODE } from "@/config"
import {
  isMobilePhone,
  MOBILE_PHONE_MESSAGE,
  normalizePhone,
} from "@/lib/phone"
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
// 06 0-1 password.rule: 8자 이상, 영문·숫자 포함, UTF-8 72바이트 이하(해시가 받는 최대 길이, 한글은 한 글자에 3바이트)
export const PASSWORD_RULE =
  "8자 이상, 영문과 숫자를 함께 써 주세요. 72바이트(한글은 한 글자에 3바이트)까지 쓸 수 있어요."
export const PASSWORD_MAX_BYTES = 72
export const validPassword = (p: string) =>
  p.length >= 8 &&
  /[A-Za-z]/.test(p) &&
  /\d/.test(p) &&
  new TextEncoder().encode(p).length <= PASSWORD_MAX_BYTES
// 06 0-1 휴대폰 인증 설정값(verify.*)
export const VERIFY_CODE_TTL_MS = 5 * 60_000
export const VERIFY_RESEND_INTERVAL_S = 60
export const VERIFY_DAILY_PER_PHONE = 5
export const VERIFY_ATTEMPTS_MAX = 5
// 가입 확인 항목의 문구 판(06 4-1 ageTextVersion·termsTextVersion·privacyNoticeTextVersion).
// 서버에 지금 판을 정한 곳이 아직 없어 v1로 보내고, 정식 관리는 CONS-4에서 해요(#208).
export const ACK_TEXT_VERSIONS = {
  age14: "v1",
  terms: "v1",
  privacyNotice: "v1",
} as const
// 가입은 끝났는데 이어서 한 로그인이 실패했을 때(연결 오류·429 등). 인증 표는 이미 쓰여서 다시 가입하면
// CODE_EXPIRED·EMAIL_TAKEN에 막히므로, 화면은 이 오류를 받으면 가입을 되풀이하지 않고 로그인으로 보내요.
export const SIGNED_UP_LOGIN_FAILED_MESSAGE =
  "가입은 끝났어요. 가입한 이메일로 로그인해 주세요."
export class SignedUpLoginError extends Error {
  readonly email: string
  constructor(email: string, options?: ErrorOptions) {
    super(SIGNED_UP_LOGIN_FAILED_MESSAGE, options)
    this.name = "SignedUpLoginError"
    this.email = email
  }
}
// 06 4-10 화면 문구
export const CODE_REPLACED_MESSAGE =
  "새 인증번호를 보냈어요. 가장 최근 문자의 번호를 넣어 주세요. 다른 기기에서 받았다면 그 기기에서 넣어 주세요"
export const DAILY_LIMIT_MESSAGE =
  "오늘은 더 받을 수 없어요. 내일 다시 해 주세요"
export const SMS_SEND_FAILED_MESSAGE =
  "문자를 보내지 못했어요. 잠시 뒤 다시 받아 주세요"

/* ── 백엔드 연결 ── */
const isUnauthorized = (e: unknown) => e instanceof ApiError && e.status === 401
// GET /api/me 응답(06 4-1). smsEnabled(ACC-6)는 아직 쓰는 화면이 없어 Account로 옮기지 않아요.
interface MeResponse {
  id: string
  email: string
  displayName: string
  phone: string | null
  smsEnabled: boolean
}
export function createHttpAuth(
  request: HttpRequest,
  token: { get: () => string | undefined; set: (t?: string) => void },
): AuthApi {
  // 내 정보(06 4-1 GET /api/me). 서버의 displayName을 화면의 name으로 바꿔요.
  // phone은 다른 계정이 같은 번호를 인증하면(ACC-3a) null이라, 화면이 "번호 없음"으로 쓰는 ""로 바꿔요.
  const me = async (): Promise<Account> => {
    const r = await request<MeResponse>("GET", endpoints.me)
    return {
      id: r.id,
      email: r.email,
      name: r.displayName,
      phone: r.phone ?? "",
    }
  }
  // 재발급(ACC-2): 리프레시 쿠키(HttpOnly, Path=/api/auth/refresh)로 새 액세스 토큰을 받아요. 쿠키는 브라우저가 붙여요.
  const refresh = async () => {
    const r = await request<{ accessToken: string }>(
      "POST",
      endpoints.refresh,
      { idempotent: false },
    )
    token.set(r.accessToken)
  }
  // 로그아웃: 서버가 액세스 토큰에 담긴 토큰 묶음을 폐기하고 리프레시 쿠키를 지워요.
  const revoke = () =>
    request<void>("POST", endpoints.logout, { idempotent: false })
  // 개발 모드(StrictMode)처럼 restore가 겹쳐 불려도 재발급은 한 번만 보내요(리프레시 토큰은 한 번 쓰면 바뀌어요).
  let restoring: Promise<Account | null> | undefined
  return {
    mode: "http",
    getToken: token.get,
    // 인증 문자(06 4-1): 서버는 201 + 인증 ID(id)만 줘요. 만료 시각은 받은 때부터 verify.code.ttl(5분)로 계산해요.
    // 가입·인증 문자는 행동 기록을 남기지 않아 Idempotency-Key를 보내지 않아요(06 4-0).
    async requestPhoneCode(phone) {
      const r = await request<{ id: string }>(
        "POST",
        endpoints.phoneVerifications,
        {
          body: { phone: normalizePhone(phone), purpose: "SIGNUP" },
          idempotent: false,
        },
      )
      return {
        verificationId: r.id,
        expiresAt: new Date(Date.now() + VERIFY_CODE_TTL_MS).toISOString(),
        delivered: true,
      }
    },
    // 인증번호 확인: 서버의 인증 표(ticket)를 화면의 phoneToken으로 넘겨요
    async verifyPhoneCode(vid, code) {
      const r = await request<{ ticket: string; expiresAt: string }>(
        "POST",
        endpoints.verifyPhone(vid),
        { body: { code: code.trim() }, idempotent: false },
      )
      return { phoneToken: r.ticket }
    },
    // 가입(06 4-1): 201 + 회원 ID만 오고 로그인은 되지 않아서, 같은 이메일·비밀번호로 이어서 로그인해요
    async signup(input) {
      const email = input.email.trim()
      await request<{ id: string }>("POST", endpoints.signup, {
        body: {
          email,
          password: input.password,
          displayName: input.name.trim(),
          verificationTicket: input.phoneToken,
          ageConfirmed: input.agreements.age14,
          ageTextVersion: ACK_TEXT_VERSIONS.age14,
          termsAgreed: input.agreements.terms,
          termsTextVersion: ACK_TEXT_VERSIONS.terms,
          privacyNoticeAcknowledged: input.agreements.privacyNotice,
          privacyNoticeTextVersion: ACK_TEXT_VERSIONS.privacyNotice,
        },
        idempotent: false,
      })
      try {
        return await this.login(email, input.password)
      } catch (e) {
        throw new SignedUpLoginError(email, { cause: e })
      }
    },
    async login(email, password) {
      const r = await request<{ accessToken: string }>(
        "POST",
        endpoints.login,
        { body: { email, password }, idempotent: false },
      )
      token.set(r.accessToken)
      try {
        return await me()
      } catch (e) {
        // 내 정보를 못 받으면 로그인하지 않은 상태로 되돌려요(토큰만 남아 반쯤 로그인된 상태를 막아요)
        await revoke().catch(() => undefined)
        token.set(undefined)
        throw e
      }
    },
    restore() {
      restoring ??= (async () => {
        try {
          await refresh()
          return await me()
        } catch {
          token.set(undefined)
          return null
        } finally {
          restoring = undefined
        }
      })()
      return restoring
    },
    // 로그아웃이 서버에서 끝났을 때만 토큰을 지워요. 연결 오류·서버 오류로 실패하면 토큰을 남기고 오류를 그대로 알려,
    // 화면이 로그인 상태를 지키고 다시 누를 수 있게 해요(실패를 삼키면 리프레시 쿠키가 남아 새로고침할 때 다시 로그인돼요).
    async logout() {
      try {
        await revoke()
      } catch (e) {
        if (!isUnauthorized(e)) throw e
        // 액세스 토큰이 없거나 만료(auth.access.ttl 15분)되면 401이에요. 리프레시 쿠키로 새 토큰을 받아 한 번 더 보내요.
        try {
          await refresh()
        } catch (e2) {
          // 재발급도 401(LOGIN_REQUIRED·REFRESH_REUSED)이면 쿠키가 없거나 폐기된 것이라 이미 끝난 세션이에요
          if (!isUnauthorized(e2)) throw e2
          token.set(undefined)
          return
        }
        await revoke()
      }
      token.set(undefined)
    },
  }
}

/* ── 서버 없이 개발할 때: 브라우저 메모리 계정 저장소 ──
   문자를 실제로 보내지 않으므로 delivered=false를 돌려주고, 화면은 '문자를 보내지 않았어요'를 그대로 알립니다.
   인증번호는 개발자 콘솔에만 남깁니다. 규칙(5분·5회, 이메일·번호 중복, 비밀번호)은 명세와 같게 검사해요. */
interface LocalAccount extends Account {
  password: string
}
export function createLocalAuth(): AuthApi {
  const accounts: LocalAccount[] = DEV_ACCOUNTS.map((a) => ({ ...a }))
  const codes = new Map<
    string,
    {
      phone: string
      code: string
      expires: number
      tries: number
      replaced?: boolean
    }
  >()
  // 같은 번호의 인증 진행(ACC-1g): 마지막 보낸 시각, 오늘 보낸 횟수, 지금 쓸 수 있는 인증
  const sends = new Map<
    string,
    { lastAt: number; day: string; count: number; current?: string }
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
      if (!isMobilePhone(n)) fail("VALIDATION_FAILED", MOBILE_PHONE_MESSAGE)
      // 이미 가입에 쓰인 번호도 막지 않아요. 새 계정이 인증하면 이전 계정의 번호 인증이 풀려요(ACC-3a)
      const now = Date.now()
      const day = new Date(now).toDateString()
      const s = sends.get(n)
      const count = s && s.day === day ? s.count : 0
      if (count >= VERIFY_DAILY_PER_PHONE)
        fail("DAILY_LIMIT", DAILY_LIMIT_MESSAGE, 429)
      const wait = s
        ? Math.ceil((s.lastAt + VERIFY_RESEND_INTERVAL_S * 1000 - now) / 1000)
        : 0
      if (wait > 0)
        throw new ApiError({
          status: 429,
          code: "RATE_LIMITED",
          detail: `${wait}초 뒤 다시 받을 수 있어요.`,
          retryAfter: wait,
        })
      // 다시 받으면 이전 번호는 쓸 수 없어요(ACC-1g)
      const prev = s?.current ? codes.get(s.current) : undefined
      if (prev) prev.replaced = true
      const verificationId = crypto.randomUUID()
      sends.set(n, {
        lastAt: now,
        day,
        count: count + 1,
        current: verificationId,
      })
      // 임시(개발용): 확인을 쉽게 하려고 인증번호를 123456으로 고정해요. 서버 연결 전에 아래 무작위 코드로 되돌려 주세요.
      // const code = String(Math.floor(100000 + Math.random() * 900000))
      const code = DEV_FIXED_CODE
      codes.set(verificationId, {
        phone: n,
        code,
        expires: now + VERIFY_CODE_TTL_MS,
        tries: 0,
      })
      console.info(`[HomeStamp 개발 서버] ${n} 인증번호: ${code}`)
      return {
        verificationId,
        expiresAt: new Date(now + VERIFY_CODE_TTL_MS).toISOString(),
        delivered: false,
      }
    },
    async verifyPhoneCode(vid, code) {
      const v = codes.get(vid)
      // 다시 받은 뒤 이전 번호로 넣으면 틀린 횟수는 그대로 두고 알려요(ACC-1g)
      if (v?.replaced) fail("CODE_REPLACED", CODE_REPLACED_MESSAGE)
      if (!v || v.expires < Date.now())
        fail(
          "CODE_EXPIRED",
          "인증번호 유효 시간(5분)이 지났어요. 다시 받아 주세요.",
        )
      if (v!.tries >= VERIFY_ATTEMPTS_MAX)
        fail(
          "CODE_EXPIRED",
          `${VERIFY_ATTEMPTS_MAX}번 틀려서 이 인증번호는 쓸 수 없어요. 다시 받아 주세요.`,
        )
      if (v!.code !== code.trim()) {
        v!.tries++
        fail(
          "CODE_INVALID",
          `인증번호가 맞지 않아요. (${v!.tries}/${VERIFY_ATTEMPTS_MAX})`,
        )
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
      // 같은 번호로 인증한 다른 계정은 번호 인증이 풀려요(ACC-3a)
      accounts.forEach((a) => {
        if (a.phone === account.phone) a.phone = ""
      })
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
