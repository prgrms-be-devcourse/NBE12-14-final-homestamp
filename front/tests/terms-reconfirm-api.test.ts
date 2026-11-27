import { afterEach, describe, expect, it, vi } from "vitest"
import {
  createHttpAuth,
  createSession,
  type PendingAcknowledgement,
} from "../src/api/auth"
import { ApiError } from "../src/api/errors"

// 바뀐 약관 다시 확인의 API 쪽(06 4-1 GET /api/me·POST /api/me/agreements, CONS-4a, #332)
type Route = (init: RequestInit) => Response | Promise<Response>
function fakeServer(routes: Record<string, Route>) {
  const calls: {
    key: string
    headers: Record<string, string>
    body?: unknown
  }[] = []
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      const key = `${init.method} ${url}`
      calls.push({
        key,
        headers: init.headers as Record<string, string>,
        body: init.body ? JSON.parse(String(init.body)) : undefined,
      })
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
const TERMS_UPDATE = {
  status: 403,
  code: "TERMS_UPDATE_REQUIRED",
  detail: "약관이 바뀌었어요. 확인하고 계속해 주세요",
}
const PENDING: PendingAcknowledgement[] = [
  { kind: "TERMS_AGREED", textVersion: "2026-12-01" },
]
const ME = {
  id: "0193f5a2-7c1e-7b3a-9d2f-1a2b3c4d5e6f",
  email: "seoyun@example.com",
  displayName: "정서윤",
  phone: "01012345678",
  smsEnabled: true,
  pendingAcknowledgements: PENDING,
}

afterEach(() => {
  vi.unstubAllGlobals()
})

async function loggedIn(routes: Record<string, Route>) {
  const calls = fakeServer({
    "POST /api/auth/login": () => json({ accessToken: "A1" }),
    "GET /api/me": () => json(ME),
    ...routes,
  })
  const session = createSession("")
  const auth = createHttpAuth(session)
  const account = await auth.login("a@b.com", "pw")
  return { calls, session, auth, account }
}

describe("약관 다시 확인 API", () => {
  it("내 정보의 다시 확인할 문구를 서버가 준 판 그대로 받는다", async () => {
    const { account } = await loggedIn({})

    expect(account.pendingAcknowledgements).toEqual(PENDING)
  })

  it("다시 확인은 서버가 준 판 그대로 Idempotency-Key 없이 보내고 바뀐 내 정보를 받는다", async () => {
    const { calls, auth } = await loggedIn({
      "POST /api/me/agreements": () =>
        json({ ...ME, pendingAcknowledgements: [] }),
    })

    const after = await auth.acknowledge(PENDING)

    const sent = calls.find((c) => c.key === "POST /api/me/agreements")!
    expect(sent.body).toEqual({ acknowledgements: PENDING })
    expect(sent.headers["Idempotency-Key"]).toBeUndefined()
    expect(sent.headers.Authorization).toBe("Bearer A1")
    expect(after.pendingAcknowledgements).toEqual([])
  })

  it("상태를 바꾸는 요청이 TERMS_UPDATE_REQUIRED면 한 번 알리고, 다시 보내지 않고 오류로 끝낸다", async () => {
    const { calls, session, auth } = await loggedIn({
      "POST /api/rooms": () => json(TERMS_UPDATE, 403),
    })
    const heard: Error[] = []
    auth.onTermsRequired((e) => heard.push(e))

    const failed = await session
      .request("POST", "/api/rooms", { body: {} })
      .catch((e: unknown) => e)

    expect(failed).toBeInstanceOf(ApiError)
    expect((failed as ApiError).code).toBe("TERMS_UPDATE_REQUIRED")
    expect(heard).toHaveLength(1)
    expect(calls.filter((c) => c.key === "POST /api/rooms")).toHaveLength(1)
  })

  it("다른 403이나 다른 오류는 확인 창을 열라고 알리지 않는다", async () => {
    const { session, auth } = await loggedIn({
      "POST /api/rooms": () =>
        json({ status: 403, code: "FORBIDDEN", detail: "x" }, 403),
    })
    const heard: Error[] = []
    auth.onTermsRequired((e) => heard.push(e))

    await session
      .request("POST", "/api/rooms", { body: {} })
      .catch(() => undefined)

    expect(heard).toHaveLength(0)
  })

  it("거절을 기다리는 사이 로그아웃했으면 알리지 않는다(앞 계정의 거절로 확인 창을 열지 않음)", async () => {
    let answer: ((r: Response) => void) | undefined
    const { session, auth } = await loggedIn({
      "POST /api/rooms": () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    })
    const heard: Error[] = []
    auth.onTermsRequired((e) => heard.push(e))

    const pending = session
      .request("POST", "/api/rooms", { body: {} })
      .catch(() => undefined)
    await vi.waitFor(() => expect(answer).toBeDefined())
    session.token.set(undefined)
    answer!(json(TERMS_UPDATE, 403))
    await pending

    expect(heard).toHaveLength(0)
  })
})
