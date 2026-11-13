import { expect, test } from "vitest"
import { createDemo, type Payment, type Repair } from "../src/data/demo"
import {
  confirmDefect,
  openPaymentDispute,
  reaskWaitDays,
  repairStamped,
  respondDefect,
  respondPayment,
  startRepair,
  supplementDefect,
  supplementPayment,
} from "../src/lib/repair-rules"

// 응답 두 버튼과 보류·보충(08-화면-기본-틀 2장, 03 G '응답과 그 뒤', MONEY-2)
const repair = (): Repair => ({
  ...structuredClone(createDemo().repairs[0]),
  author: "임차인",
  ack: "확인 대기",
  ackDispute: undefined,
  status: "확인 전",
  reaskedAt: undefined,
  version: 1,
})
const NOW = Date.parse("2026-11-13T00:00:00Z")
const DAY = 86_400_000

test("보류(더 필요한 게 있어요)는 도장이고 사유가 필요하며, 맞아요로 바꿀 수 있다", () => {
  const r = repair()
  expect(() => respondDefect(r, "임대인", "보류", " ")).toThrow()
  respondDefect(r, "임대인", "보류", "천장 전체 사진이 필요해요")
  expect(r.ack).toBe("보충 요청")
  expect(repairStamped(r)).toBe(true)
  confirmDefect(r, "임대인", 1)
  expect(r.ack).toBe("확인됨")
  expect(r.notes!.at(-1)!.text).toMatch("천장 전체 사진이 필요해요")
  // 맞아요는 되돌리지 않아요
  expect(() => respondDefect(r, "임대인", "이견", "다시 봤어요")).toThrow()
})

test("보충하고 다시 확인 요청: 근거 하나 이상, 확인 대기로 돌아가고 7일에 한 번", () => {
  const r = repair()
  respondDefect(r, "임대인", "보류", "가까이 찍어 주세요")
  expect(() =>
    supplementDefect(r, "임차인", { text: "", photos: [] }, NOW),
  ).toThrow("하나 이상")
  expect(() =>
    supplementDefect(r, "임대인", { text: "추가", photos: [] }, NOW),
  ).toThrow()
  supplementDefect(r, "임차인", { text: "", photos: ["p.jpg"] }, NOW)
  expect(r.ack).toBe("확인 대기")
  expect(r.photos).toContain("p.jpg")
  expect(reaskWaitDays(r.reaskedAt, NOW + 4 * DAY)).toBe(3)
  respondDefect(r, "임대인", "이견", "여전히 달라요")
  expect(() =>
    supplementDefect(r, "임차인", { text: "또", photos: [] }, NOW + 4 * DAY),
  ).toThrow("3일 뒤 다시 요청할 수 있어요")
  supplementDefect(r, "임차인", { text: "또", photos: [] }, NOW + 7 * DAY)
  expect(r.ack).toBe("확인 대기")
})

test("보충 요청 중에 수리를 시작하면 강행 표시가 남는다(막지 않음)", () => {
  const r = repair()
  respondDefect(r, "임대인", "보류", "사진이 더 필요해요")
  startRepair(r)
  expect(r.status).toBe("처리 중")
  expect(r.startFlag).toBe("보충 요청 중 수리 시작")
})

test("MONEY-2b 지불에도 보류와 보충이 있다", () => {
  const p: Payment = {
    id: "p1",
    by: "임차인",
    payer: "임차인",
    payee: "업체",
    amount: 82000,
    confirmed: false,
  }
  expect(() => respondPayment(p, "임차인", "보류", "영수증")).toThrow()
  respondPayment(p, "임대인", "보류", "영수증을 보여 주세요")
  expect(openPaymentDispute(p)?.kind).toBe("보류")
  supplementPayment(p, "임차인", { text: "", photos: ["r.jpg"] }, NOW)
  expect(openPaymentDispute(p)).toBeUndefined()
  expect(p.disputes).toHaveLength(1)
  expect(p.photos).toContain("r.jpg")
  respondPayment(p, "임대인", "이견", "금액이 달라요")
  expect(() =>
    supplementPayment(p, "임차인", { text: "다시", photos: [] }, NOW + DAY),
  ).toThrow("6일 뒤")
})
