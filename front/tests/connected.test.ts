import { afterEach, describe, expect, it, vi } from "vitest"
import { createHttpClient } from "../src/api/http-client"
import { CONNECTED_APIS, isConnected } from "../src/api/connected"

afterEach(() => vi.unstubAllGlobals())

describe("HTTP 모드는 연결을 마친 API만 서버로 보내요", () => {
  it("로그인·재발급·로그아웃·내 정보와 가입·휴대폰 인증만 연결했어요(내 정보 아래 주소는 아직)", () => {
    expect(CONNECTED_APIS.length).toBeGreaterThan(0)
    expect(isConnected("POST", "/api/auth/login")).toBe(true)
    expect(isConnected("POST", "/api/auth/refresh")).toBe(true)
    expect(isConnected("POST", "/api/auth/logout")).toBe(true)
    expect(isConnected("GET", "/api/me")).toBe(true)
    expect(isConnected("GET", "/api/me/home")).toBe(false)
    expect(isConnected("POST", "/api/auth/signup")).toBe(true)
    expect(isConnected("POST", "/api/auth/phone-verifications")).toBe(true)
    expect(
      isConnected("POST", "/api/auth/phone-verifications/abc-123/verify"),
    ).toBe(true)
    expect(
      isConnected("POST", "/api/auth/phone-verifications/a/b/verify"),
    ).toBe(false)
  })
  it("연결 전 API는 서버로 보내지 않고 NOT_CONNECTED로 알려요", async () => {
    const fetch = vi.fn()
    vi.stubGlobal("fetch", fetch)
    const request = createHttpClient({ baseUrl: "" })
    await expect(request("GET", "/api/me/notifications")).rejects.toMatchObject(
      {
        code: "NOT_CONNECTED",
      },
    )
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
  it("법정동 찾기·단계 선택(06 4-3)은 GET만 서버로 보내요", () => {
    expect(isConnected("GET", "/api/regions/search?q=%EC%B2%AD%EC%9A%B4")).toBe(
      true,
    )
    expect(isConnected("GET", "/api/regions")).toBe(true)
    expect(isConnected("GET", "/api/regions?parent=11")).toBe(true)
    expect(isConnected("POST", "/api/regions")).toBe(false)
    expect(isConnected("GET", "/api/regions/11")).toBe(false)
  })
})
