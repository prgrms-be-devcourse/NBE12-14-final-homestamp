import { describe, expect, it } from "vitest"
import { isMobilePhone, maskPhone, normalizePhone } from "../src/lib/phone"

describe("휴대폰 번호 한 형식(06 4-0)", () => {
  it("숫자만 남겨요", () => {
    expect(normalizePhone("010-1234-5678")).toBe("01012345678")
    expect(normalizePhone(undefined)).toBe("")
  })
  it("010으로 시작하는 11자리만 휴대폰 번호예요", () => {
    expect(isMobilePhone("010-1234-5678")).toBe(true)
    expect(isMobilePhone("0101234567")).toBe(false)
    expect(isMobilePhone("01112345678")).toBe(false)
  })
  it("가운데 네 자리를 가려요", () => {
    expect(maskPhone("01012345678")).toBe("010-••••-5678")
  })
})
