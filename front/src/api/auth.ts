import { API_BASE_URL, API_MODE } from "@/config"
import {
  isMobilePhone,
  MOBILE_PHONE_MESSAGE,
  normalizePhone,
} from "@/lib/phone"
import { ApiError } from "./errors"
import { endpoints } from "./endpoints"
import { createHttpClient, type HttpRequest } from "./http-client"

/* ── 로그인 세션: 액세스 토큰과 재발급 ──
   액세스 토큰은 메모리에만 둡니다(04 4-1: 리프레시는 HttpOnly 쿠키). 계정 요청(createHttpAuth)과
   기록 요청(createHttpApi)이 같은 세션의 request를 써서, 401이면 함께 하나의 재발급을 기다려요(04 4-1 "동시 재발급").
   로그인 세션마다 번호를 붙여요. 로그인·로그아웃·세션 끝(token.set·end)마다 번호가 바뀌고, 재발급은 같은 세션이라 그대로예요.
   요청의 재시도와 재발급 결과는 시작한 세션이 아직 지금 세션일 때만 적용해, 로그아웃·계정 전환 전후의
   요청·토큰이 섞이지 않게 해요(#331 리뷰). */
const isUnauthorized = (e: unknown) => e instanceof ApiError && e.status === 401
export interface Session {
  request: HttpRequest
  // set은 새 로그인 세션을 시작해요(로그인한 토큰, 또는 로그아웃의 undefined)
  token: { get: () => string | undefined; set: (t?: string) => void }
  // 재발급(ACC-2). 같은 세션에서 겹쳐 불러도 서버로는 한 번만 보내요(리프레시 토큰은 한 번 쓰면 바뀌어요).
  // 기다리는 사이 세션이 바뀌면 받은 토큰은 버려요.
  refresh(): Promise<void>
  // 쓰던 세션이 끝났을 때 알려요: 재발급이 LOGIN_REQUIRED·REFRESH_REUSED이거나,
  // 재발급한 토큰으로 다시 보낸 요청도 LOGIN_REQUIRED일 때. 돌려준 함수로 그만 들어요.
  onEnd(listener: (e: ApiError) => void): () => void
  // 바뀐 약관을 확인하기 전이라 상태를 바꾸는 요청이 403 TERMS_UPDATE_REQUIRED로 거절됐을 때 알려요(CONS-4a).
  // 그 요청은 오류로 끝나고 다시 보내지 않아요. 돌려준 함수로 그만 들어요.
  onTermsRequired(listener: (e: ApiError) => void): () => void
}
export function createSession(baseUrl: string): Session {
  let accessToken: string | undefined
  let current = 0
  // 진행 중인 재발급과 그것을 시작한 세션
  let refreshing: { session: number; done: Promise<void> } | undefined
  const listeners = new Set<(e: ApiError) => void>()
  const termsListeners = new Set<(e: ApiError) => void>()
  const token = {
    get: () => accessToken,
    set: (t?: string) => {
      current++
      accessToken = t
    },
  }
  // 그 세션이 아직 지금 세션일 때만 끝내요(이미 로그아웃·다른 계정으로 바뀌었으면 건드리지 않아요).
  // 토큰을 지우고, 로그인한 채 쓰다가 끝났을 때만 알려요(새로고침 뒤 처음 복원할 때는 원래 로그인 전이에요).
  // 동시 요청이 함께 끝내도 첫 번째가 세션을 바꿔서 한 번만 알려요.
  const end = (e: ApiError, session: number) => {
    if (session !== current) return
    const had = accessToken !== undefined
    token.set(undefined)
    if (had) listeners.forEach((l) => l(e))
  }
  const refresh = () => {
    if (refreshing?.session === current) return refreshing.done
    const session = current
    const done = (async () => {
      try {
        const r = await request<{ accessToken: string }>(
          "POST",
          endpoints.refresh,
          { idempotent: false },
        )
        // 기다리는 사이 로그아웃·다른 계정 로그인이 있었으면 이전 세션의 토큰이라 버려요
        if (session === current) accessToken = r.accessToken
      } catch (e) {
        // 쿠키가 없거나 폐기됐으면 이미 끝난 세션이라 토큰을 지워요. 연결 오류·서버 오류는 토큰을 남겨 다시 시도할 수 있게 해요.
        if (isUnauthorized(e)) end(e as ApiError, session)
        throw e
      } finally {
        if (refreshing?.session === session) refreshing = undefined
      }
    })()
    refreshing = { session, done }
    return done
  }
  const request = createHttpClient({
    baseUrl,
    getToken: token.get,
    getSession: () => current,
    refresh,
    endSession: end,
    // 그 요청을 보낸 세션이 아직 지금 세션일 때만 알려요(로그아웃·계정 전환 뒤 늦게 온 거절로 확인 화면을 열지 않게)
    termsRequired: (e, session) => {
      if (session === current) termsListeners.forEach((l) => l(e))
    },
  })
  return {
    request,
    token,
    refresh,
    onEnd(listener) {
      listeners.add(listener)
      return () => void listeners.delete(listener)
    },
    onTermsRequired(listener) {
      termsListeners.add(listener)
      return () => void termsListeners.delete(listener)
    },
  }
}
// 앱 전체가 쓰는 세션(HTTP 모드에서만 서버로 요청해요)
export const session = createSession(API_BASE_URL)
export function createAuth(): AuthApi {
  return API_MODE === "http" ? createHttpAuth(session) : createLocalAuth()
}

// 계정·인증(명세 4-1, S-01·S-02). 화면은 이 모양만 씁니다.
export interface Account {
  id: string
  email: string
  name: string
  phone: string
  // 문자 받기(06 4-1 smsEnabled, ACC-6). 꺼도 서비스 안 알림은 받아요(03 K)
  smsEnabled: boolean
  // 다시 확인해야 할 문구(06 4-1 GET /api/me, CONS-4). 비어 있지 않으면 로그인 뒤 확인 화면을 먼저 띄워요
  pendingAcknowledgements: PendingAcknowledgement[]
}
// 확인 항목 종류(05 member_acknowledgement.kind). 처리방침은 확인 대상이 아니에요(CONS-1a)
export type AcknowledgementKind =
  "AGE_14_CONFIRMED" | "TERMS_AGREED" | "PRIVACY_NOTICE_ACK"
// 서버가 준 판 그대로 다시 확인에 보내요(화면이 판을 만들지 않아요)
export interface PendingAcknowledgement {
  kind: AcknowledgementKind
  textVersion: string
}
export interface PhoneVerification {
  verificationId: string
  expiresAt: string
  delivered: boolean
}
// 휴대폰 인증 목적(06 4-1 purpose). 화면이 쓰는 것은 가입과 비밀번호 재설정이에요(아이디 찾기·번호 변경은 아직 화면이 없어요)
export type VerificationPurpose = "SIGNUP" | "RESET"
// 내 정보 수정(06 4-1 POST /api/me): 보낸 칸만 바뀌어요
export interface AccountPatch {
  name?: string
  smsEnabled?: boolean
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
  requestPhoneCode(
    phone: string,
    purpose?: VerificationPurpose,
  ): Promise<PhoneVerification>
  verifyPhoneCode(
    verificationId: string,
    code: string,
  ): Promise<{ phoneToken: string }>
  signup(input: SignupInput): Promise<Account>
  login(email: string, password: string): Promise<Account>
  // 새로고침 뒤 로그인 유지: 리프레시 쿠키로 다시 받은 토큰으로 내 정보를 불러와요.
  // 로그인 전이면(401) null. 연결 오류·서버 오류는 로그아웃으로 보지 않고 그대로 던져 화면이 다시 시도하게 해요.
  restore(): Promise<Account | null>
  logout(): Promise<void>
  getToken(): string | undefined
  // 쓰던 세션이 끝났을 때(토큰 만료 뒤 재발급도 실패) 알려요. 화면은 로그인으로 보내요. 돌려준 함수로 그만 들어요.
  onSessionEnd(listener: (e: Error) => void): () => void
  // 내 정보를 다시 불러와요(다시 확인할 문구가 바뀌었을 수 있을 때)
  reload(): Promise<Account>
  // 바뀐 문구 다시 확인(06 4-1 POST /api/me/agreements, CONS-4). 서버가 준 판 그대로 보내고 바뀐 내 정보를 받아요.
  // 그사이 판이 바뀌었으면 VERSION_CONFLICT라 화면이 reload로 다시 불러와요
  acknowledge(items: PendingAcknowledgement[]): Promise<Account>
  // 상태를 바꾸는 요청이 403 TERMS_UPDATE_REQUIRED로 거절됐을 때 알려요. 화면은 확인 화면을 열어요(CONS-4a)
  onTermsRequired(listener: (e: Error) => void): () => void
  // 이름·문자 설정 바꾸기(06 4-1 POST /api/me, ACC-4·6). 바뀐 내 정보를 받아요.
  // 바뀐 약관을 확인하기 전에는 문자 끄기만 되고, 이름 바꾸기·문자 켜기는 TERMS_UPDATE_REQUIRED예요(CONS-4b)
  updateAccount(patch: AccountPatch): Promise<Account>
  // 비밀번호 변경(06 4-1 POST /api/me/password, ACC-4a). 지금 기기는 로그인이 그대로고 다른 기기는 로그아웃돼요
  changePassword(currentPassword: string, newPassword: string): Promise<void>
  // 비밀번호 재설정(06 4-1 POST /api/auth/reset-password, ACC-5b). RESET 인증 표로 부르고 모든 기기가 로그아웃돼요.
  // 그 번호를 인증한 회원이 없어도 가입 여부를 따로 알리지 않고 CODE_EXPIRED예요(ACC-5c)
  resetPassword(phoneToken: string, newPassword: string): Promise<void>
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
// 이름 규칙(06 4-1 POST /api/me displayName: 비어 있지 않고 50자 이하, 05 member.display_name)
export const NAME_MAX = 50
export const NAME_RULE = `이름은 1~${NAME_MAX}자로 넣어 주세요.`
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
// GET /api/me 응답(06 4-1)
interface MeResponse {
  id: string
  email: string
  displayName: string
  phone: string | null
  smsEnabled: boolean
  pendingAcknowledgements: PendingAcknowledgement[]
}
export function createHttpAuth({
  request,
  token,
  refresh,
  onEnd,
  onTermsRequired,
}: Session): AuthApi {
  // 내 정보(06 4-1 GET /api/me). 서버의 displayName을 화면의 name으로 바꿔요.
  // phone은 다른 계정이 같은 번호를 인증하면(ACC-3a) null이라, 화면이 "번호 없음"으로 쓰는 ""로 바꿔요.
  const toAccount = (r: MeResponse): Account => ({
    id: r.id,
    email: r.email,
    name: r.displayName,
    phone: r.phone ?? "",
    smsEnabled: r.smsEnabled,
    pendingAcknowledgements: r.pendingAcknowledgements ?? [],
  })
  const me = async (): Promise<Account> =>
    toAccount(await request<MeResponse>("GET", endpoints.me))
  // 로그아웃: 서버가 액세스 토큰에 담긴 토큰 묶음을 폐기하고 리프레시 쿠키를 지워요.
  const revoke = () =>
    request<void>("POST", endpoints.logout, { idempotent: false })
  // 개발 모드(StrictMode)처럼 restore가 겹쳐 불려도 재발급·내 정보는 한 번만 보내요.
  let restoring: Promise<Account | null> | undefined
  return {
    mode: "http",
    getToken: token.get,
    onSessionEnd: onEnd,
    onTermsRequired,
    reload: me,
    // 다시 확인(06 4-1): 행동 기록이 아니라 Idempotency-Key를 보내지 않아요(06 4-0). 200 + 내 정보
    async acknowledge(items) {
      const r = await request<MeResponse>("POST", endpoints.agreements, {
        body: {
          acknowledgements: items.map(({ kind, textVersion }) => ({
            kind,
            textVersion,
          })),
        },
        idempotent: false,
      })
      return toAccount(r)
    },
    // 내 정보 수정(06 4-1): 보낸 칸만 바뀌어요. 행동 기록이 아니라 Idempotency-Key를 보내지 않아요(06 4-0)
    async updateAccount(patch) {
      const body: { displayName?: string; smsEnabled?: boolean } = {}
      if (patch.name !== undefined) body.displayName = patch.name.trim()
      if (patch.smsEnabled !== undefined) body.smsEnabled = patch.smsEnabled
      return toAccount(
        await request<MeResponse>("POST", endpoints.me, {
          body,
          idempotent: false,
        }),
      )
    },
    // 비밀번호 변경·재설정(06 4-1): 204. 행동 기록이 아니라 Idempotency-Key를 보내지 않아요
    async changePassword(currentPassword, newPassword) {
      await request<void>("POST", endpoints.password, {
        body: { currentPassword, newPassword },
        idempotent: false,
      })
    },
    async resetPassword(phoneToken, newPassword) {
      await request<void>("POST", endpoints.resetPassword, {
        body: { verificationTicket: phoneToken, newPassword },
        idempotent: false,
      })
    },
    // 인증 문자(06 4-1): 서버는 201 + 인증 ID(id)만 줘요. 만료 시각은 받은 때부터 verify.code.ttl(5분)로 계산해요.
    // 가입·인증 문자는 행동 기록을 남기지 않아 Idempotency-Key를 보내지 않아요(06 4-0).
    async requestPhoneCode(phone, purpose = "SIGNUP") {
      const r = await request<{ id: string }>(
        "POST",
        endpoints.phoneVerifications,
        {
          body: { phone: normalizePhone(phone), purpose },
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
        // 내 정보를 못 받으면 로그인하지 않은 상태로 되돌려요(토큰만 남아 반쯤 로그인된 상태를 막아요).
        // 재발급해도 LOGIN_REQUIRED라 세션이 이미 끝났으면(토큰 없음) 서버도 받지 않으니 로그아웃을 보내지 않아요
        if (token.get()) await revoke().catch(() => undefined)
        token.set(undefined)
        throw e
      }
    },
    restore() {
      restoring ??= (async () => {
        try {
          // 리프레시 쿠키(HttpOnly, Path=/api/auth/refresh)는 브라우저가 붙여요
          await refresh()
          return await me()
        } catch (e) {
          // 쿠키가 없거나 폐기됐으면(재발급 401), 또는 탈퇴한 회원의 토큰이면(내 정보 401) 로그인 전이에요.
          // 연결 오류·서버 오류(429·5xx)는 로그아웃하지 않고 그대로 알려요(#191)
          if (!isUnauthorized(e)) throw e
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
    // 액세스 토큰이 만료(auth.access.ttl 15분)됐으면 요청 함수가 재발급한 뒤 한 번 더 보내요(06 4-1 logout).
    async logout() {
      try {
        await revoke()
      } catch (e) {
        // 재발급도 401(LOGIN_REQUIRED·REFRESH_REUSED)이면 쿠키가 없거나 폐기된 것이라 이미 끝난 세션이에요
        if (!isUnauthorized(e)) throw e
      }
      token.set(undefined)
    },
  }
}

/* ── 서버 없이 개발할 때: 브라우저 메모리 계정 저장소 ──
   문자를 실제로 보내지 않으므로 delivered=false를 돌려주고, 화면은 '문자를 보내지 않았어요'를 그대로 알립니다.
   인증번호는 개발자 콘솔에만 남깁니다. 규칙(5분·5회, 이메일·번호 중복, 비밀번호)은 명세와 같게 검사해요. */
interface LocalAccount extends Omit<
  Account,
  "pendingAcknowledgements" | "smsEnabled"
> {
  password: string
  smsEnabled?: boolean
}
export function createLocalAuth(): AuthApi {
  const accounts: LocalAccount[] = DEV_ACCOUNTS.map((a) => ({ ...a }))
  const codes = new Map<
    string,
    {
      phone: string
      purpose: VerificationPurpose
      code: string
      expires: number
      tries: number
      replaced?: boolean
    }
  >()
  // 같은 번호의 인증 진행(ACC-1g): 마지막 보낸 시각, 오늘 보낸 횟수, 지금 쓸 수 있는 인증
  // 같은 인증 진행은 (번호, 목적)이에요(05 phone_verification)
  const sends = new Map<
    string,
    { lastAt: number; day: string; count: number; current?: string }
  >()
  const tokens = new Map<
    string,
    { phone: string; purpose: VerificationPurpose }
  >()
  // 인증 표는 목적이 같은 API에만 써요(06 4-1 verify)
  const ticketPhone = (t: string, purpose: VerificationPurpose) => {
    const v = tokens.get(t)
    return v?.purpose === purpose ? v.phone : undefined
  }
  let current: Account | null = null
  const fail = (code: string, detail: string, status = 400): never => {
    throw new ApiError({ status, code, detail })
  }
  // 서버 없이 개발할 때는 문구 판이 바뀌지 않아 다시 확인할 문구가 없어요(가입 판 = 지금 판, ACK_TEXT_VERSIONS)
  const strip = (a: LocalAccount): Account => ({
    id: a.id,
    email: a.email,
    name: a.name,
    phone: a.phone,
    smsEnabled: a.smsEnabled ?? true,
    pendingAcknowledgements: [],
  })
  const signedIn = () =>
    current ??
    fail("LOGIN_REQUIRED", "로그인이 필요해요. 다시 로그인해 주세요.", 401)
  const signedInAccount = () => {
    const id = signedIn().id
    return accounts.find((a) => a.id === id)!
  }
  return {
    mode: "mock",
    getToken: () => current?.id,
    // 서버 없이 개발할 때는 토큰이 만료되지 않아요
    onSessionEnd: () => () => undefined,
    // 서버 없이 개발할 때는 약관 확인 전 막기가 없어요
    onTermsRequired: () => () => undefined,
    async reload() {
      return signedIn()
    },
    async acknowledge() {
      return signedIn()
    },
    async requestPhoneCode(phone, purpose = "SIGNUP") {
      const n = normalizePhone(phone)
      if (!isMobilePhone(n)) fail("VALIDATION_FAILED", MOBILE_PHONE_MESSAGE)
      // 이미 가입에 쓰인 번호도 막지 않아요. 새 계정이 인증하면 이전 계정의 번호 인증이 풀려요(ACC-3a)
      const now = Date.now()
      const day = new Date(now).toDateString()
      const key = `${purpose}:${n}`
      const s = sends.get(key)
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
      sends.set(key, {
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
        purpose,
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
      tokens.set(phoneToken, { phone: v!.phone, purpose: v!.purpose })
      return { phoneToken }
    },
    async signup(input) {
      const phone = ticketPhone(input.phoneToken, "SIGNUP")
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
    async updateAccount(patch) {
      const a = signedInAccount()
      if (patch.name !== undefined) {
        const name = patch.name.trim()
        if (!name || name.length > NAME_MAX)
          fail("VALIDATION_FAILED", NAME_RULE)
        a.name = name
      }
      if (patch.smsEnabled !== undefined) a.smsEnabled = patch.smsEnabled
      current = strip(a)
      return current
    },
    async changePassword(currentPassword, newPassword) {
      const a = signedInAccount()
      if (a.password !== currentPassword)
        fail("PASSWORD_MISMATCH", "지금 비밀번호가 맞지 않아요")
      if (!validPassword(newPassword))
        fail("VALIDATION_FAILED", `비밀번호는 ${PASSWORD_RULE}`)
      a.password = newPassword
    },
    async resetPassword(phoneToken, newPassword) {
      if (!validPassword(newPassword))
        fail("VALIDATION_FAILED", `비밀번호는 ${PASSWORD_RULE}`)
      const phone = ticketPhone(phoneToken, "RESET")
      const a = phone ? accounts.find((x) => x.phone === phone) : undefined
      // 회원이 없는 번호도 가입 여부를 따로 알리지 않고 CODE_EXPIRED, 표는 쓰지 않아요(ACC-5c)
      if (!a) fail("CODE_EXPIRED", "휴대폰 인증을 다시 해 주세요.")
      a!.password = newPassword
      tokens.delete(phoneToken)
      // 모든 기기가 로그아웃돼요
      if (current?.id === a!.id) current = null
    },
    async logout() {
      current = null
    },
  }
}
