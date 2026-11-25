import { afterEach, describe, expect, it, vi } from "vitest"
import { createHttpClient } from "../src/api/http-client"
import { CONNECTED_APIS, isConnected } from "../src/api/connected"

afterEach(() => vi.unstubAllGlobals())

describe("HTTP 모드는 연결을 마친 API만 서버로 보내요", () => {
  it("로그인·재발급·로그아웃·내 정보만 연결했어요(회원가입·내 정보 아래 주소는 아직)", () => {
    expect(CONNECTED_APIS.length).toBeGreaterThan(0)
    expect(isConnected("POST", "/api/auth/login")).toBe(true)
    expect(isConnected("POST", "/api/auth/refresh")).toBe(true)
    expect(isConnected("POST", "/api/auth/logout")).toBe(true)
    expect(isConnected("GET", "/api/me")).toBe(true)
    expect(isConnected("GET", "/api/me/home")).toBe(false)
    expect(isConnected("POST", "/api/auth/signup")).toBe(false)
  })
  it("연결 전 API는 서버로 보내지 않고 NOT_CONNECTED로 알려요", async () => {
    const fetch = vi.fn()
    vi.stubGlobal("fetch", fetch)
    const request = createHttpClient({ baseUrl: "" })
    await expect(request("GET", "/api/rooms")).rejects.toMatchObject({
      code: "NOT_CONNECTED",
    })
    expect(fetch).not.toHaveBeenCalled()
  })
  it("연결을 마친 API는 서버로 보내고, Retry-After를 오류에 담아요", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: "RATE_LIMITED", detail: "잠시만" }), {
        status: 429,
        headers: { "Retry-After": "42" },
      }),
    )
    vi.stubGlobal("fetch", fetch)
    const request = createHttpClient({ baseUrl: "", allow: () => true })
    await expect(
      request("POST", "/api/auth/phone-verifications", { body: {} }),
    ).rejects.toMatchObject({ code: "RATE_LIMITED", retryAfter: 42 })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
