import { afterEach, describe, expect, it, vi } from "vitest"
import { createHttpAuth, createSession } from "../src/api/auth"
import type { ApiError } from "../src/api/errors"

// 일반 API가 액세스 토큰 만료(auth.access.ttl 15분)로 401이면 재발급하고 한 번만 다시 보내요(#191, 04 4-1, 06 ACC-2).
// 서버 대신 응답을 정해 두는 fetch. 같은 주소의 응답을 순서대로 꺼내고, 보낸 요청을 calls에 남겨요.
type Reply = () => Response | Promise<Response>
function fakeServer(routes: Record<string, Reply | Reply[]>) {
  const calls: { key: string; headers: Record<string, string> }[] = []
  const used: Record<string, number> = {}
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      const key = `${init.method} ${url}`
      calls.push({ key, headers: init.headers as Record<string, string> })
      const route = routes[key]
      if (!route) throw new Error(`예상하지 못한 요청: ${key}`)
      const n = (used[key] = (used[key] ?? 0) + 1)
      const reply = Array.isArray(route)
        ? route[Math.min(n, route.length) - 1]
        : route
      return reply()
    }),
  )
  return calls
}
const json =
  (body: unknown, status = 200) =>
  () =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    })
const problem = (status: number, code: string) =>
  json({ status, code, detail: `${code} 문구` }, status)
const expired = problem(401, "LOGIN_REQUIRED")
const offline = () => {
  throw new TypeError("Failed to fetch")
}
// 응답을 늦게 돌려줘 요청이 겹치게 만들어요
function deferred() {
  let resolve!: (r: Response) => void
  const promise = new Promise<Response>((r) => (resolve = r))
  return { reply: () => promise, resolve }
}
const keys = (calls: { key: string }[]) => calls.map((c) => c.key)
const ROOMS = { items: [], nextCursor: null }

function setup(token: string | null = "A1") {
  const session = createSession("")
  session.token.set(token ?? undefined)
  const ended: ApiError[] = []
  session.onEnd((e) => ended.push(e))
  return { session, request: session.request, ended }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("일반 API 401 → 재발급 후 다시 요청", () => {
  it("토큰이 만료돼 401이면 재발급한 토큰으로 한 번 다시 보내 성공한다", async () => {
    const calls = fakeServer({
      "GET /api/rooms": [expired, json(ROOMS)],
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { session, request, ended } = setup()

    await expect(request("GET", "/api/rooms")).resolves.toEqual(ROOMS)
    expect(keys(calls)).toEqual([
      "GET /api/rooms",
      "POST /api/auth/refresh",
      "GET /api/rooms",
    ])
    expect(calls[0].headers.Authorization).toBe("Bearer A1")
    // 재발급은 쿠키로만 가고 Idempotency-Key를 붙이지 않아요
    expect(calls[1].headers.Authorization).toBe("Bearer A1")
    expect(calls[1].headers["Idempotency-Key"]).toBeUndefined()
    expect(calls[2].headers.Authorization).toBe("Bearer A2")
    expect(session.token.get()).toBe("A2")
    expect(ended).toEqual([])
  })

  it("다시 보낼 때 Idempotency-Key와 본문은 처음 요청과 같다", async () => {
    const calls = fakeServer({
      "POST /api/rooms": [expired, json({ id: "r1" }, 201)],
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { request } = setup()
    const fetchMock = vi.mocked(fetch)

    await request("POST", "/api/rooms", { body: { name: "301호" } })
    const first = calls[0].headers["Idempotency-Key"]
    expect(first).toBeTruthy()
    expect(calls[2].headers["Idempotency-Key"]).toBe(first)
    expect(fetchMock.mock.calls[2][1]?.body).toBe(
      fetchMock.mock.calls[0][1]?.body,
    )
  })

  it("여러 요청이 동시에 401이면 재발급은 한 번만 보내고 모두 다시 보낸다", async () => {
    const refresh = deferred()
    const calls = fakeServer({
      "GET /api/rooms": [expired, expired, json(ROOMS)],
      "GET /api/regions": [expired, json([])],
      "POST /api/auth/refresh": refresh.reply,
    })
    const { request } = setup()

    const all = Promise.all([
      request("GET", "/api/rooms"),
      request("GET", "/api/rooms"),
      request("GET", "/api/regions"),
    ])
    await vi.waitFor(() =>
      expect(keys(calls).filter((k) => k.includes("refresh"))).toHaveLength(1),
    )
    refresh.resolve(json({ accessToken: "A2" })())

    await expect(all).resolves.toEqual([ROOMS, ROOMS, []])
    expect(keys(calls).filter((k) => k.includes("refresh"))).toHaveLength(1)
    const retried = calls.filter((c) => c.headers.Authorization === "Bearer A2")
    expect(retried).toHaveLength(3)
  })

  it("다른 요청이 이미 재발급한 뒤에 도착한 401은 재발급하지 않고 새 토큰으로 다시 보낸다", async () => {
    const late = deferred()
    const calls = fakeServer({
      "GET /api/rooms": [expired, json(ROOMS)],
      "GET /api/regions": [late.reply, json([])],
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { request } = setup()

    const slow = request("GET", "/api/regions")
    await request("GET", "/api/rooms")
    late.resolve(expired())

    await expect(slow).resolves.toEqual([])
    expect(keys(calls).filter((k) => k.includes("refresh"))).toHaveLength(1)
    expect(calls.at(-1)).toMatchObject({
      key: "GET /api/regions",
      headers: { Authorization: "Bearer A2" },
    })
  })

  it("다시 보낸 요청도 401 LOGIN_REQUIRED면 재발급을 되풀이하지 않고 세션을 끝낸다", async () => {
    const calls = fakeServer({
      "GET /api/rooms": expired,
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { session, request, ended } = setup()

    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      status: 401,
      code: "LOGIN_REQUIRED",
    })
    expect(keys(calls)).toEqual([
      "GET /api/rooms",
      "POST /api/auth/refresh",
      "GET /api/rooms",
    ])
    expect(session.token.get()).toBeUndefined()
    expect(ended.map((e) => e.code)).toEqual(["LOGIN_REQUIRED"])
  })

  it("동시 요청이 다시 보낸 뒤에도 모두 401이면 세션 끝은 한 번만 알린다", async () => {
    fakeServer({
      "GET /api/rooms": expired,
      "GET /api/regions": expired,
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { request, ended } = setup()

    const results = await Promise.allSettled([
      request("GET", "/api/rooms"),
      request("GET", "/api/regions"),
    ])
    expect(results.map((r) => r.status)).toEqual(["rejected", "rejected"])
    expect(ended).toHaveLength(1)
  })

  it("다시 보낸 요청이 LOGIN_REQUIRED가 아닌 오류면 세션을 끝내지 않는다", async () => {
    fakeServer({
      "GET /api/rooms": [expired, problem(403, "FORBIDDEN")],
      "POST /api/auth/refresh": json({ accessToken: "A2" }),
    })
    const { session, request, ended } = setup()

    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      status: 403,
    })
    expect(session.token.get()).toBe("A2")
    expect(ended).toEqual([])
  })

  it("LOGIN_REQUIRED가 아닌 401·403은 재발급하지 않는다", async () => {
    const calls = fakeServer({
      "POST /api/auth/login": problem(401, "LOGIN_FAILED"),
      "GET /api/rooms": problem(403, "FORBIDDEN"),
    })
    const { request } = setup()

    await expect(request("POST", "/api/auth/login")).rejects.toMatchObject({
      code: "LOGIN_FAILED",
    })
    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      status: 403,
    })
    expect(keys(calls)).toEqual(["POST /api/auth/login", "GET /api/rooms"])
  })

  it("토큰 없이 보낸 요청(로그인 전)의 401은 재발급하지 않는다", async () => {
    const calls = fakeServer({ "GET /api/rooms": expired })
    const { request } = setup(null)

    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      code: "LOGIN_REQUIRED",
    })
    expect(keys(calls)).toEqual(["GET /api/rooms"])
  })
})

describe("재발급 실패", () => {
  it.each(["LOGIN_REQUIRED", "REFRESH_REUSED"])(
    "재발급이 %s면 토큰을 지우고 세션 끝을 알리며, 원래 요청은 그 오류로 실패한다",
    async (code) => {
      const calls = fakeServer({
        "GET /api/rooms": expired,
        "POST /api/auth/refresh": problem(401, code),
      })
      const { session, request, ended } = setup()

      await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
        code,
      })
      // 재발급 요청 자신은 다시 보내지 않아요
      expect(keys(calls)).toEqual(["GET /api/rooms", "POST /api/auth/refresh"])
      expect(session.token.get()).toBeUndefined()
      expect(ended.map((e) => e.code)).toEqual([code])
    },
  )

  it("동시 401에서 재발급이 실패해도 세션 끝은 한 번만 알린다", async () => {
    fakeServer({
      "GET /api/rooms": expired,
      "GET /api/regions": expired,
      "POST /api/auth/refresh": problem(401, "REFRESH_REUSED"),
    })
    const { request, ended } = setup()

    const results = await Promise.allSettled([
      request("GET", "/api/rooms"),
      request("GET", "/api/regions"),
    ])
    expect(results.map((r) => r.status)).toEqual(["rejected", "rejected"])
    expect(ended).toHaveLength(1)
  })

  it("재발급이 연결 오류·서버 오류면 로그아웃으로 보지 않고 토큰을 남긴다", async () => {
    fakeServer({
      "GET /api/rooms": expired,
      "POST /api/auth/refresh": [offline, problem(503, "INTERNAL_ERROR")],
    })
    const { session, request, ended } = setup()

    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      code: "NETWORK",
    })
    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      status: 503,
    })
    expect(session.token.get()).toBe("A1")
    expect(ended).toEqual([])
  })
})

describe("새로고침 뒤 복원(restore)", () => {
  it("리프레시 쿠키가 없으면(401) 로그인 전으로 보고 null, 세션 끝은 알리지 않는다", async () => {
    fakeServer({ "POST /api/auth/refresh": problem(401, "LOGIN_REQUIRED") })
    const { session, ended } = setup(null)

    await expect(createHttpAuth(session).restore()).resolves.toBeNull()
    expect(ended).toEqual([])
  })

  it("연결 오류는 로그아웃으로 보지 않고 실패로 알리며, 다시 부르면 복원된다", async () => {
    fakeServer({
      "POST /api/auth/refresh": [offline, json({ accessToken: "A2" })],
      "GET /api/me": json({
        id: "m1",
        email: "seoyun@example.com",
        displayName: "정서윤",
        phone: "01012345678",
        smsEnabled: true,
      }),
    })
    const { session } = setup(null)
    const auth = createHttpAuth(session)

    await expect(auth.restore()).rejects.toMatchObject({ code: "NETWORK" })
    await expect(auth.restore()).resolves.toMatchObject({ name: "정서윤" })
    expect(session.token.get()).toBe("A2")
  })

  it("서버 오류(5xx)도 로그아웃으로 보지 않는다", async () => {
    fakeServer({ "POST /api/auth/refresh": problem(500, "INTERNAL_ERROR") })
    const { session } = setup(null)

    await expect(createHttpAuth(session).restore()).rejects.toMatchObject({
      status: 500,
    })
  })
})
