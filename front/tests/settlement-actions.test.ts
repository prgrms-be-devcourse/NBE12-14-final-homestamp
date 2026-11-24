import { expect, test } from "vitest"
import { createDemo, type Room, type Settlement } from "../src/data/demo"
import {
  settlementAvailableActions,
  settlementItemAvailableActions,
  transferAvailableActions,
} from "../src/api/mock/settlement-actions"

// 정산(S-15) 버튼은 서버가 준 행동 목록을 따른다(06 5-2, 08 10장)
const room = (patch: Partial<Room> = {}): Room => ({
  ...structuredClone(createDemo().rooms[0]),
  status: "거주 종료",
  finishBy: undefined,
  depositOffer: undefined,
  ...patch,
})
const item = (patch: Partial<Settlement> = {}): Settlement => ({
  ...structuredClone(createDemo().settlements[0]),
  by: "임대인",
  status: "합의 대기",
  excludeProposal: undefined,
  autoChange: undefined,
  repairId: undefined,
  ...patch,
})

test("상대가 적은 보증금에는 맞아요 · 아니에요 · 다른 금액으로 답한다", () => {
  const r = room({
    depositOffer: {
      id: "o1",
      by: "임대인",
      amount: 10_000_000,
      reason: "계약서",
      round: 1,
      status: "합의 대기",
      at: "11/20",
    },
  })
  expect(settlementAvailableActions(r, "임차인")).toEqual(
    expect.arrayContaining(["deposit-agree", "deposit-dispute"]),
  )
  expect(settlementAvailableActions(r, "임대인")).not.toContain("deposit-agree")
})

test("정산 끝내기: 제안한 쪽은 기다리고, 상대는 확인한다. 정산 완료 뒤에는 아무것도 없다", () => {
  expect(settlementAvailableActions(room(), "임차인")).toContain("finish")
  const asked = room({ finishBy: "임차인" })
  expect(settlementAvailableActions(asked, "임대인")).toContain(
    "finish-confirm",
  )
  expect(settlementAvailableActions(asked, "임차인")).not.toContain("finish")
  expect(
    settlementAvailableActions(room({ status: "정산 완료" }), "임차인"),
  ).toEqual([])
})

test("정산 항목: 답할 쪽은 동의 · 아니에요 · 다른 안, 수리 건에서 온 항목에는 바꾸기가 없다", () => {
  expect(settlementItemAvailableActions(item(), "임차인")).toEqual([
    "agree",
    "dispute",
    "exclude",
  ])
  expect(settlementItemAvailableActions(item(), "임대인")).toEqual([
    "withdraw",
    "exclude",
  ])
  expect(
    settlementItemAvailableActions(item({ status: "합의됨" }), "임차인"),
  ).toContain("proposals")
  expect(
    settlementItemAvailableActions(
      item({ status: "합의됨", repairId: "leak" }),
      "임차인",
    ),
  ).not.toContain("proposals")
})

test("송금은 기록하지 않은 쪽만 응답한다", () => {
  const t = {
    id: "t1",
    by: "임대인" as const,
    amount: 1000,
    direction: "반환" as const,
    at: "1/5",
    confirmed: false,
  }
  expect(transferAvailableActions(t, "임차인")).toEqual(["respond"])
  expect(transferAvailableActions(t, "임대인")).toEqual([])
})
