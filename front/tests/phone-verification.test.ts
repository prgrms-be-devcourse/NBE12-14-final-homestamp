import { afterEach, describe, expect, it, vi } from "vitest"
import { createLocalAuth, DEV_FIXED_CODE, validPassword } from "../src/api/auth"
import { ApiError } from "../src/api/errors"

const codeOf = async (p: Promise<unknown>) => {
  try {
    await p
  } catch (e) {
    return e instanceof ApiError ? e : undefined
  }
}
const agreements = { age14: true, terms: true, privacyNotice: true }

afterEach(() => vi.useRealTimers())

describe("휴대폰 인증 목업(06 ACC-1e·1g·1i, 0-1 verify.*)", () => {
  it("인증번호는 5분 동안 쓸 수 있어요", async () => {
    vi.useFakeTimers({ now: new Date("2026-11-24T10:00:00+09:00") })
    const auth = createLocalAuth()
    const v = await auth.requestPhoneCode("010-3333-4444")
    expect(Date.parse(v.expiresAt) - Date.now()).toBe(5 * 60_000)
  })
  it("60초 안에 다시 받으면 RATE_LIMITED와 남은 초를 줘요", async () => {
    vi.useFakeTimers({ now: new Date("2026-11-24T10:00:00+09:00") })
    const auth = createLocalAuth()
    await auth.requestPhoneCode("01033334444")
    vi.advanceTimersByTime(20_000)
    const e = await codeOf(auth.requestPhoneCode("01033334444"))
    expect(e?.code).toBe("RATE_LIMITED")
    expect(e?.retryAfter).toBe(40)
  })
  it("다시 받은 뒤 이전 인증으로 넣으면 CODE_REPLACED예요", async () => {
    vi.useFakeTimers({ now: new Date("2026-11-24T10:00:00+09:00") })
    const auth = createLocalAuth()
    const first = await auth.requestPhoneCode("01033334444")
    vi.advanceTimersByTime(61_000)
    const second = await auth.requestPhoneCode("01033334444")
    const e = await codeOf(
      auth.verifyPhoneCode(first.verificationId, DEV_FIXED_CODE),
    )
    expect(e?.code).toBe("CODE_REPLACED")
    await expect(
      auth.verifyPhoneCode(second.verificationId, DEV_FIXED_CODE),
    ).resolves.toHaveProperty("phoneToken")
  })
  it("한 번호는 하루 5번까지 받아요", async () => {
    vi.useFakeTimers({ now: new Date("2026-11-24T10:00:00+09:00") })
    const auth = createLocalAuth()
    for (let i = 0; i < 5; i++) {
      await auth.requestPhoneCode("01033334444")
      vi.advanceTimersByTime(61_000)
    }
    const e = await codeOf(auth.requestPhoneCode("01033334444"))
    expect(e?.code).toBe("DAILY_LIMIT")
  })
  it("이미 가입에 쓰인 번호도 인증할 수 있고, 이전 계정의 번호 인증은 풀려요(ACC-3a)", async () => {
    const auth = createLocalAuth()
    // 개발용 세입자 계정(test@gmail.com)의 번호
    const v = await auth.requestPhoneCode("01011112222")
    const { phoneToken } = await auth.verifyPhoneCode(
      v.verificationId,
      DEV_FIXED_CODE,
    )
    const made = await auth.signup({
      email: "new@example.com",
      password: "abcd1234",
      name: "새 계정",
      phoneToken,
      agreements,
    })
    expect(made.phone).toBe("01011112222")
    const old = await auth.login("test@gmail.com", "aaaa1111")
    expect(old.phone).toBe("")
  })
})

describe("비밀번호 규칙(06 0-1 password.rule)", () => {
  it("UTF-8 72바이트까지만 받아요", () => {
    expect(validPassword("가".repeat(23) + "a12")).toBe(true) // 72바이트
    expect(validPassword("가".repeat(23) + "a123")).toBe(false) // 73바이트
    expect(validPassword("12345678")).toBe(false)
  })
})
