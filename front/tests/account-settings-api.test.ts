import { afterEach, describe, expect, it, vi } from "vitest"
import { createHttpAuth, createSession } from "../src/api/auth"
import { ApiError } from "../src/api/errors"

// 내 정보 수정·문자 설정·비밀번호 변경·재설정의 API 쪽(06 4-1 POST /api/me·/api/me/password·/api/auth/reset-password, #416)
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
const noContent = () => new Response(null, { status: 204 })
const problem = (status: number, code: string, detail = code) =>
  json({ status, code, detail }, status)
const ME = {
  id: "0193f5a2-7c1e-7b3a-9d2f-1a2b3c4d5e6f",
  email: "seoyun@example.com",
  displayName: "정서윤",
  phone: "01012345678",
  smsEnabled: true,
  pendingAcknowledgements: [],
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
  calls.length = 0
  return { calls, session, auth, account }
}

describe("내 정보 수정·문자 설정", () => {
  it("내 정보의 문자 설정을 그대로 받는다", async () => {
    const { account } = await loggedIn({})

    expect(account.smsEnabled).toBe(true)
  })

  it("이름은 앞뒤 빈칸을 지운 displayName 칸만 보내고 바뀐 내 정보를 받는다", async () => {
    const { calls, auth } = await loggedIn({
      "POST /api/me": () => json({ ...ME, displayName: "정서윤2" }),
    })

    const account = await auth.updateAccount({ name: "  정서윤2 " })

    expect(account.name).toBe("정서윤2")
    expect(calls).toHaveLength(1)
    expect(calls[0].body).toEqual({ displayName: "정서윤2" })
    // 행동 기록이 아니라 Idempotency-Key를 보내지 않아요(06 4-0)
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
    expect(calls[0].headers.Authorization).toBe("Bearer A1")
  })

  it("문자 끄기는 smsEnabled 칸만 보낸다", async () => {
    const { calls, auth } = await loggedIn({
      "POST /api/me": () => json({ ...ME, smsEnabled: false }),
    })

    const account = await auth.updateAccount({ smsEnabled: false })

    expect(account.smsEnabled).toBe(false)
    expect(calls[0].body).toEqual({ smsEnabled: false })
  })

  it("약관 확인 전이라 거절되면 확인 화면을 알리고 요청을 다시 보내지 않는다(CONS-4b, #401)", async () => {
    const { calls, session, auth } = await loggedIn({
      "POST /api/me": () =>
        problem(403, "TERMS_UPDATE_REQUIRED", "약관이 바뀌었어요"),
    })
    const heard: unknown[] = []
    session.onTermsRequired((e) => heard.push(e))

    await expect(auth.updateAccount({ name: "새 이름" })).rejects.toMatchObject(
      { code: "TERMS_UPDATE_REQUIRED" },
    )
    expect(heard).toHaveLength(1)
    expect(calls).toHaveLength(1)
  })
})

describe("비밀번호 변경·재설정", () => {
  it("변경은 지금·새 비밀번호를 보내고 204를 받는다", async () => {
    const { calls, auth, session } = await loggedIn({
      "POST /api/me/password": () => noContent(),
    })

    await expect(
      auth.changePassword("old-pass1", "new-pass1"),
    ).resolves.toBeUndefined()

    expect(calls[0].key).toBe("POST /api/me/password")
    expect(calls[0].body).toEqual({
      currentPassword: "old-pass1",
      newPassword: "new-pass1",
    })
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
    // 지금 기기는 로그인 그대로예요
    expect(session.token.get()).toBe("A1")
  })

  it("지금 비밀번호가 틀리면 PASSWORD_MISMATCH를 그대로 알린다", async () => {
    const { auth } = await loggedIn({
      "POST /api/me/password": () =>
        problem(400, "PASSWORD_MISMATCH", "지금 비밀번호가 맞지 않아요"),
    })

    const e = await auth
      .changePassword("wrong1234", "new-pass1")
      .catch((x) => x)

    expect(e).toBeInstanceOf(ApiError)
    expect(e.code).toBe("PASSWORD_MISMATCH")
  })

  it("재설정 인증 문자는 RESET 목적으로 받는다", async () => {
    const calls = fakeServer({
      "POST /api/auth/phone-verifications": () => json({ id: "v1" }, 201),
    })
    const auth = createHttpAuth(createSession(""))

    await auth.requestPhoneCode("010-1111-2222", "RESET")

    expect(calls[0].body).toEqual({ phone: "01011112222", purpose: "RESET" })
  })

  it("재설정은 인증 표를 verificationTicket으로 보내고 로그인 없이 부른다", async () => {
    const calls = fakeServer({
      "POST /api/auth/reset-password": () => noContent(),
    })
    const auth = createHttpAuth(createSession(""))

    await auth.resetPassword("ticket-1", "new-pass1")

    expect(calls[0].body).toEqual({
      verificationTicket: "ticket-1",
      newPassword: "new-pass1",
    })
    expect(calls[0].headers.Authorization).toBeUndefined()
    expect(calls[0].headers["Idempotency-Key"]).toBeUndefined()
  })
})
