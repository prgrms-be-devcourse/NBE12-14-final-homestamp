import { afterEach, describe, expect, it, vi } from "vitest"
import {
  createHttpAuth,
  createSession,
  SignedUpLoginError,
} from "../src/api/auth"
import { maskPhone } from "../src/lib/phone"

// 서버 대신 응답을 정해 두는 fetch. 보낸 요청(주소·헤더)을 calls에 남겨요.
function fakeServer(
  routes: Record<string, (init: RequestInit) => Response | Promise<Response>>,
) {
  const calls: { key: string; headers: Record<string, string> }[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      const key = `${init.method} ${url}`
      calls.push({ key, headers: init.headers as Record<string, string> })
      const route = routes[key]
      if (!route) throw new Error(`예상하지 못한 요청: ${key}`)
      return route(init)
    }),
  )
  return calls
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
const problem = (status: number, code: string) =>
  json({ status, code, detail: code }, status)
// GET /api/me 응답 예(06 4-1, 백엔드 feat/back/me 계약)
const ME = {
  id: "0193f5a2-7c1e-7b3a-9d2f-1a2b3c4d5e6f",
  email: "seoyun@example.com",
  displayName: "정서윤",
  phone: "01012345678",
  smsEnabled: true,
  pendingAcknowledgements: [],
}

function setup() {
  const session = createSession("")
  return { auth: createHttpAuth(session), store: session.token }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("createHttpAuth", () => {
  it("로그인하면 받은 토큰으로 내 정보를 불러오고 displayName을 name으로 바꾼다", async () => {
    const calls = fakeServer({
      "POST /api/auth/login": () => json({ accessToken: "A1" }),
      "GET /api/me": () => json(ME),
    })
    const { auth, store } = setup()

    await expect(auth.login("a@b.com", "pw")).resolves.toEqual({
      id: ME.id,
      email: ME.email,
      name: "정서윤",
      phone: "01012345678",
      pendingAcknowledgements: [],
    })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/login",
      "GET /api/me",
    ])
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
    expect(calls[1].headers.Authorization).toBe("Bearer A1")
    expect(store.get()).toBe("A1")
  })

  it("인증한 번호가 없으면(phone null) 빈 문자열로 바꾼다", async () => {
    fakeServer({
      "POST /api/auth/login": () => json({ accessToken: "A1" }),
      "GET /api/me": () => json({ ...ME, phone: null }),
    })
    const { auth } = setup()

    const account = await auth.login("a@b.com", "pw")
    expect(account.phone).toBe("")
    expect(maskPhone(account.phone)).toBe("")
    expect(maskPhone(null)).toBe("")
  })

  it("내 정보를 못 받으면 로그아웃을 보내고 토큰을 지워 로그인을 되돌린다", async () => {
    const calls = fakeServer({
      "POST /api/auth/login": () => json({ accessToken: "A1" }),
      "GET /api/me": () => problem(500, "INTERNAL_ERROR"),
      "POST /api/auth/logout": () => new Response(null, { status: 204 }),
    })
    const { auth, store } = setup()

    await expect(auth.login("a@b.com", "pw")).rejects.toMatchObject({
      status: 500,
    })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/login",
      "GET /api/me",
      "POST /api/auth/logout",
    ])
    expect(calls[2].headers.Authorization).toBe("Bearer A1")
    expect(store.get()).toBeUndefined()
  })

  it("재발급해도 내 정보가 401이면(탈퇴한 회원의 남은 토큰) 세션이 끝나 로그아웃 없이 로그인을 되돌린다", async () => {
    const calls = fakeServer({
      "POST /api/auth/login": () => json({ accessToken: "A1" }),
      "GET /api/me": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/refresh": () => json({ accessToken: "A2" }),
    })
    const { auth, store } = setup()

    await expect(auth.login("a@b.com", "pw")).rejects.toMatchObject({
      code: "LOGIN_REQUIRED",
    })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/login",
      "GET /api/me",
      "POST /api/auth/refresh",
      "GET /api/me",
    ])
    expect(store.get()).toBeUndefined()
  })

  it("새로고침하면 재발급한 토큰으로 내 정보를 불러온다", async () => {
    const calls = fakeServer({
      "POST /api/auth/refresh": () => json({ accessToken: "A2" }),
      "GET /api/me": () => json(ME),
    })
    const { auth, store } = setup()

    await expect(auth.restore()).resolves.toMatchObject({ name: "정서윤" })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/refresh",
      "GET /api/me",
    ])
    expect(calls[1].headers.Authorization).toBe("Bearer A2")
    expect(store.get()).toBe("A2")
  })

  it("로그인 실패(LOGIN_FAILED)는 그대로 알리고 토큰을 남기지 않는다", async () => {
    fakeServer({
      "POST /api/auth/login": () => problem(401, "LOGIN_FAILED"),
    })
    const { auth, store } = setup()

    await expect(auth.login("a@b.com", "x")).rejects.toMatchObject({
      status: 401,
      code: "LOGIN_FAILED",
    })
    expect(store.get()).toBeUndefined()
  })

  it("restore가 겹쳐 불려도 재발급은 한 번만 보낸다", async () => {
    const calls = fakeServer({
      "POST /api/auth/refresh": () => problem(401, "LOGIN_REQUIRED"),
    })
    const { auth } = setup()

    const [a, b] = await Promise.all([auth.restore(), auth.restore()])
    expect(a).toBeNull()
    expect(b).toBeNull()
    expect(calls.map((c) => c.key)).toEqual(["POST /api/auth/refresh"])
  })

  it("로그아웃은 액세스 토큰으로 보내고 토큰을 지운다", async () => {
    const calls = fakeServer({
      "POST /api/auth/logout": () => new Response(null, { status: 204 }),
    })
    const { auth, store } = setup()
    store.set("A1")

    await auth.logout()
    expect(calls.map((c) => c.headers.Authorization)).toEqual(["Bearer A1"])
    expect(store.get()).toBeUndefined()
  })

  it("액세스 토큰이 만료돼 로그아웃이 401이면 재발급한 토큰으로 한 번 더 보낸다", async () => {
    let logouts = 0
    const calls = fakeServer({
      "POST /api/auth/logout": () =>
        ++logouts === 1
          ? problem(401, "LOGIN_REQUIRED")
          : new Response(null, { status: 204 }),
      "POST /api/auth/refresh": () => json({ accessToken: "A2" }),
    })
    const { auth, store } = setup()
    store.set("A1-expired")

    await auth.logout()
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/logout",
      "POST /api/auth/refresh",
      "POST /api/auth/logout",
    ])
    expect(calls[2].headers.Authorization).toBe("Bearer A2")
    expect(store.get()).toBeUndefined()
  })

  it("리프레시 쿠키도 없으면 로그아웃은 오류 없이 끝난다", async () => {
    const calls = fakeServer({
      "POST /api/auth/logout": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/refresh": () => problem(401, "LOGIN_REQUIRED"),
    })
    const { auth, store } = setup()
    store.set("A1-expired")

    await expect(auth.logout()).resolves.toBeUndefined()
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/logout",
      "POST /api/auth/refresh",
    ])
    expect(store.get()).toBeUndefined()
  })
})

// 가입·휴대폰 인증(06 4-1, #208): 서버 계약 모양으로 보내고 받은 값을 화면 모양으로 바꿔요
describe("가입·휴대폰 인증", () => {
  const bodyOf = (init: RequestInit) => JSON.parse(String(init.body))
  const VID = "0193f5a2-0000-7000-8000-000000000001"

  it("인증 문자는 숫자만 남긴 번호와 SIGNUP으로 보내고, id를 인증 ID로 받아 5분 뒤 만료로 계산한다", async () => {
    vi.useFakeTimers({ now: new Date("2026-11-25T10:00:00Z") })
    let sent: unknown
    const calls = fakeServer({
      "POST /api/auth/phone-verifications": (init) => {
        sent = bodyOf(init)
        return json({ id: VID }, 201)
      },
    })
    const { auth } = setup()

    await expect(auth.requestPhoneCode("011-123-4567")).resolves.toEqual({
      verificationId: VID,
      expiresAt: "2026-11-25T10:05:00.000Z",
      delivered: true,
    })
    expect(sent).toEqual({ phone: "0111234567", purpose: "SIGNUP" })
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
    vi.useRealTimers()
  })

  it("인증번호 확인은 서버의 인증 표(ticket)를 phoneToken으로 넘긴다", async () => {
    let sent: unknown
    const calls = fakeServer({
      [`POST /api/auth/phone-verifications/${VID}/verify`]: (init) => {
        sent = bodyOf(init)
        return json({ ticket: "T1", expiresAt: "2026-11-25T10:15:00Z" })
      },
    })
    const { auth } = setup()

    await expect(auth.verifyPhoneCode(VID, " 123456 ")).resolves.toEqual({
      phoneToken: "T1",
    })
    expect(sent).toEqual({ code: "123456" })
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
  })

  it("틀린 인증번호(CODE_INVALID)는 그대로 알린다", async () => {
    fakeServer({
      [`POST /api/auth/phone-verifications/${VID}/verify`]: () =>
        problem(400, "CODE_INVALID"),
    })
    const { auth } = setup()

    await expect(auth.verifyPhoneCode(VID, "000000")).rejects.toMatchObject({
      code: "CODE_INVALID",
    })
  })

  it("가입은 06 4-1 칸 이름과 문구 판 v1로 보내고, 이어서 로그인한다", async () => {
    let sent: unknown
    const calls = fakeServer({
      "POST /api/auth/signup": (init) => {
        sent = bodyOf(init)
        return json({ id: ME.id }, 201)
      },
      "POST /api/auth/login": () => json({ accessToken: "A1" }),
      "GET /api/me": () => json(ME),
    })
    const { auth, store } = setup()

    await expect(
      auth.signup({
        email: " seoyun@example.com ",
        password: "abcd1234",
        name: " 정서윤 ",
        phoneToken: "T1",
        agreements: { age14: true, terms: true, privacyNotice: true },
      }),
    ).resolves.toMatchObject({ id: ME.id, name: "정서윤" })
    expect(sent).toEqual({
      email: "seoyun@example.com",
      password: "abcd1234",
      displayName: "정서윤",
      verificationTicket: "T1",
      ageConfirmed: true,
      ageTextVersion: "v1",
      termsAgreed: true,
      termsTextVersion: "v1",
      privacyNoticeAcknowledged: true,
      privacyNoticeTextVersion: "v1",
    })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/signup",
      "POST /api/auth/login",
      "GET /api/me",
    ])
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
    expect(store.get()).toBe("A1")
  })

  it("가입은 됐는데 이어진 로그인이 실패하면 가입한 이메일을 담은 SignedUpLoginError로 알린다", async () => {
    const calls = fakeServer({
      "POST /api/auth/signup": () => json({ id: ME.id }, 201),
      "POST /api/auth/login": () => {
        throw new TypeError("Failed to fetch")
      },
    })
    const { auth, store } = setup()

    const err = await auth
      .signup({
        email: " seoyun@example.com ",
        password: "abcd1234",
        name: "정서윤",
        phoneToken: "T1",
        agreements: { age14: true, terms: true, privacyNotice: true },
      })
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(SignedUpLoginError)
    expect(err).toMatchObject({
      email: "seoyun@example.com",
      cause: { code: "NETWORK" },
    })
    // 가입은 한 번만 보내요(같은 인증 표로 다시 보내지 않음)
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/signup",
      "POST /api/auth/login",
    ])
    expect(store.get()).toBeUndefined()
  })

  it("인증 표가 만료돼 가입이 CODE_EXPIRED면 로그인하지 않고 그대로 알린다", async () => {
    const calls = fakeServer({
      "POST /api/auth/signup": () => problem(400, "CODE_EXPIRED"),
    })
    const { auth, store } = setup()

    await expect(
      auth.signup({
        email: "a@b.com",
        password: "abcd1234",
        name: "가",
        phoneToken: "T-old",
        agreements: { age14: true, terms: true, privacyNotice: true },
      }),
    ).rejects.toMatchObject({ code: "CODE_EXPIRED" })
    expect(calls.map((c) => c.key)).toEqual(["POST /api/auth/signup"])
    expect(store.get()).toBeUndefined()
  })
})

// 로그아웃이 실패했는데 화면만 로그아웃되면 리프레시 쿠키·서버 세션이 남아 새로고침하면 다시 로그인돼요.
// 이미 끝난 세션(재발급도 401)만 로그아웃으로 보고, 일시적인 실패는 오류로 알려 다시 누를 수 있게 해요.
describe("로그아웃 실패와 이미 끝난 세션", () => {
  const offline = () => {
    throw new TypeError("Failed to fetch")
  }
  const noContent = () => new Response(null, { status: 204 })

  it("연결이 끊겨 로그아웃을 못 보내면 실패로 알리고 토큰을 남겨, 다시 누르면 같은 토큰으로 보낸다", async () => {
    let logouts = 0
    const calls = fakeServer({
      "POST /api/auth/logout": () =>
        ++logouts === 1 ? offline() : noContent(),
    })
    const { auth, store } = setup()
    store.set("A1")

    await expect(auth.logout()).rejects.toMatchObject({ code: "NETWORK" })
    expect(store.get()).toBe("A1")

    await auth.logout()
    expect(calls.map((c) => c.headers.Authorization)).toEqual([
      "Bearer A1",
      "Bearer A1",
    ])
    expect(store.get()).toBeUndefined()
  })

  it("서버 오류(500)도 실패로 알리고 토큰을 남긴다", async () => {
    fakeServer({
      "POST /api/auth/logout": () => problem(500, "INTERNAL_ERROR"),
    })
    const { auth, store } = setup()
    store.set("A1")

    await expect(auth.logout()).rejects.toMatchObject({ status: 500 })
    expect(store.get()).toBe("A1")
  })

  it("토큰 만료(401) 뒤 재발급이 연결 오류로 실패하면 세션이 끝났다고 보지 않고 실패로 알린다", async () => {
    fakeServer({
      "POST /api/auth/logout": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/refresh": offline,
    })
    const { auth, store } = setup()
    store.set("A1-expired")

    await expect(auth.logout()).rejects.toMatchObject({ code: "NETWORK" })
    expect(store.get()).toBe("A1-expired")
  })

  it("재발급은 됐는데 다시 보낸 로그아웃이 실패하면 실패로 알리고 새 토큰으로 다시 보낼 수 있다", async () => {
    let logouts = 0
    const calls = fakeServer({
      "POST /api/auth/logout": () => {
        logouts++
        if (logouts === 1) return problem(401, "LOGIN_REQUIRED")
        if (logouts === 2) return offline()
        return noContent()
      },
      "POST /api/auth/refresh": () => json({ accessToken: "A2" }),
    })
    const { auth, store } = setup()
    store.set("A1-expired")

    await expect(auth.logout()).rejects.toMatchObject({ code: "NETWORK" })
    expect(store.get()).toBe("A2")

    await auth.logout()
    expect(calls.at(-1)).toMatchObject({
      key: "POST /api/auth/logout",
      headers: { Authorization: "Bearer A2" },
    })
    expect(store.get()).toBeUndefined()
  })

  it("재발급이 REFRESH_REUSED(401)면 이미 끝난 세션이라 로그아웃된 것으로 본다", async () => {
    fakeServer({
      "POST /api/auth/logout": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/refresh": () => problem(401, "REFRESH_REUSED"),
    })
    const { auth, store } = setup()
    store.set("A1-expired")

    await expect(auth.logout()).resolves.toBeUndefined()
    expect(store.get()).toBeUndefined()
  })
})
