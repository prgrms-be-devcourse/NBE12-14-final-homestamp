import { describe, expect, it } from "vitest"
import { isMobilePhone, maskPhone, normalizePhone } from "../src/lib/phone"

describe("휴대폰 번호 한 형식(06 4-0)", () => {
  it("숫자만 남겨요", () => {
    expect(normalizePhone("010-1234-5678")).toBe("01012345678")
    expect(normalizePhone(undefined)).toBe("")
  })
  it("서버(auth/rule/PhoneNumber)와 같이 010은 11자리, 011·016~019는 10~11자리만 받아요", () => {
    expect(isMobilePhone("010-1234-5678")).toBe(true)
    expect(isMobilePhone("0101234567")).toBe(false)
    expect(isMobilePhone("01112345678")).toBe(true)
    expect(isMobilePhone("011-123-4567")).toBe(true)
    expect(isMobilePhone("0191234567")).toBe(true)
    expect(isMobilePhone("011123456")).toBe(false)
    expect(isMobilePhone("01212345678")).toBe(false)
    expect(isMobilePhone("01512345678")).toBe(false)
    expect(isMobilePhone("0212345678")).toBe(false)
  })
  it("가운데 자리를 가려요", () => {
    expect(maskPhone("01012345678")).toBe("010-••••-5678")
    expect(maskPhone("0111234567")).toBe("011-•••-4567")
  })
})
