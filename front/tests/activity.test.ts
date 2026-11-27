import { describe, expect, test } from "vitest"
import { createDemo, type Repair } from "../src/data/demo"
import { staleReason } from "../src/lib/activity"

// 경과 기록 없이 등록일만으로 날을 세는 수리 건(오늘부터 days일 전)
const repair = (days: number, patch: Partial<Repair> = {}): Repair => {
  const at = new Date(Date.now() - days * 864e5).toISOString()
  return {
    ...structuredClone(createDemo().repairs[0]),
    id: "r-stale",
    date: at,
    status: "처리 중",
    ack: "확인됨",
    closeBy: undefined,
    ...patch,
  }
}

describe("홈 오래 멈춘 건(REP-6a·6e)", () => {
  test("처리 중이고 14일 넘게 행동이 없으면 '처리 중'으로 모인다(REP-6a)", () => {
    expect(staleReason(repair(20), [])).toBe("처리 중")
    expect(staleReason(repair(5), [])).toBeUndefined()
  })
  test("하자가 보충 요청됨(보류)이고 14일 넘게 행동이 없으면 '보충 요청됨'으로 모인다(REP-6e)", () => {
    const held = { status: "확인 전", ack: "보충 요청" } as const
    expect(staleReason(repair(20, held), [])).toBe("보충 요청됨")
    expect(staleReason(repair(5, held), [])).toBeUndefined()
  })
  test("종료 확인 대기(closeBy 있음)는 오래 멈춘 건이 아니다", () => {
    expect(staleReason(repair(20, { closeBy: "임대인" }), [])).toBeUndefined()
  })
  test("처리 완료·확인 전(보류 아님)은 오래 멈춘 건이 아니다", () => {
    expect(staleReason(repair(20, { status: "처리 완료" }), [])).toBeUndefined()
    expect(
      staleReason(repair(20, { status: "확인 전", ack: "확인 대기" }), []),
    ).toBeUndefined()
  })
})
