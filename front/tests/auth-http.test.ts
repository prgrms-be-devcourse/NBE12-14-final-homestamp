import { afterEach, describe, expect, it, vi } from "vitest"
import { createHttpAuth } from "../src/api/auth"
import { createHttpClient } from "../src/api/http-client"
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
}

function setup() {
  let t: string | undefined
  const store = { get: () => t, set: (v?: string) => void (t = v) }
  const auth = createHttpAuth(
    createHttpClient({ baseUrl: "", getToken: store.get }),
    store,
  )
  return { auth, store }
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
      "GET /api/me": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/logout": () => new Response(null, { status: 204 }),
    })
    const { auth, store } = setup()

    await expect(auth.login("a@b.com", "pw")).rejects.toMatchObject({
      code: "LOGIN_REQUIRED",
    })
    expect(calls.map((c) => c.key)).toEqual([
      "POST /api/auth/login",
      "GET /api/me",
      "POST /api/auth/logout",
    ])
    expect(calls[2].headers.Authorization).toBe("Bearer A1")
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
    fakeServer({
      "POST /api/auth/logout": () => problem(401, "LOGIN_REQUIRED"),
      "POST /api/auth/refresh": () => problem(401, "LOGIN_REQUIRED"),
    })
    const { auth, store } = setup()

    await expect(auth.logout()).resolves.toBeUndefined()
    expect(store.get()).toBeUndefined()
  })
})
