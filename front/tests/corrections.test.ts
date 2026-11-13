import { expect, test } from "vitest"
import { createDemo, type Repair } from "../src/data/demo"
import {
  answerCorrection,
  CLOSED_BY_BOTH_MESSAGE,
  confirmClose,
  confirmDefect,
  disputeDefect,
  requestCorrection,
  requestRepairEdits,
} from "../src/lib/repair-rules"
import {
  answer,
  confirmItem,
  requestItemEdits,
  sessionOf,
} from "../src/lib/inspection-rules"
import { VERSION_CONFLICT_MESSAGE } from "../src/lib/versions"
import { pendingRequest } from "../src/lib/corrections"

const repair = (ack: Repair["ack"]): Repair => ({
  ...structuredClone(createDemo().repairs[0]),
  ack,
  ackDispute: undefined,
  author: "임차인",
  corrections: [],
  version: 1,
  status: "처리 중",
  soloClosed: undefined,
})

test("REP-7a 같이 확인하기 전에는 그냥 고쳐지고 확인 요청은 생기지 않는다", () => {
  const r = repair("확인 대기")
  requestRepairEdits(
    r,
    "임차인",
    { title: "새 제목", description: r.description, zone: r.zone },
    "",
  )
  expect(r.title).toBe("새 제목")
  expect(r.corrections!.map((c) => [c.status, c.mode])).toEqual([
    ["반영", "확인 전"],
  ])
  expect(pendingRequest(r.corrections!)).toBeUndefined()
})

test("상대 미확인은 도장이 아니라 기록한 쪽이 그냥 고칠 수 있다", () => {
  const r = repair("상대 미확인")
  requestRepairEdits(
    r,
    "임차인",
    { title: "새 제목", description: r.description, zone: r.zone },
    "",
  )
  expect(r.title).toBe("새 제목")
})

test("REP-7c 같이 확인한 뒤에는 앞모습이 그대로이고 고친 내용은 확인을 기다린다", () => {
  const r = repair("확인됨")
  const before = r.title
  expect(() =>
    requestRepairEdits(
      r,
      "임차인",
      { title: "새 제목", description: r.description, zone: r.zone },
      "",
    ),
  ).toThrow()
  requestRepairEdits(
    r,
    "임차인",
    { title: "새 제목", description: r.description, zone: r.zone },
    "다시 봤어요",
  )
  expect(r.title).toBe(before)
  expect(r.corrections!.map((c) => c.status)).toEqual(["확인 대기"])
})

test("지불 금액: 상대가 확인하기 전이면 바로 바뀌고, REP-7b 확인한 금액은 혼자 바뀌지 않는다", () => {
  const r = repair("확인 대기")
  r.payments = [
    {
      id: "p1",
      by: "임차인",
      payer: "임차인",
      payee: "업체",
      amount: 10000,
      confirmed: false,
    },
    {
      id: "p2",
      by: "임차인",
      payer: "임차인",
      payee: "업체",
      amount: 82000,
      confirmed: true,
    },
  ]
  requestCorrection(
    r,
    undefined,
    "임차인",
    "금액",
    10000,
    12000,
    "",
    r.payments[0],
  )
  expect(r.payments[0].amount).toBe(12000)
  requestCorrection(
    r,
    undefined,
    "임차인",
    "금액",
    82000,
    92000,
    "영수증 다시 봄",
    r.payments[1],
  )
  expect(r.payments[1].amount).toBe(82000)
  expect(r.payments[1].corrections!.at(-1)!.status).toBe("확인 대기")
})

test("이견이 있는 기록의 고치기: 승인 전에는 원본·이견 유지, 승인 뒤 새 내용은 다시 확인 대기이고 이전 이견은 이력에 남는다", () => {
  const r = repair("확인 대기")
  disputeDefect(r, "임대인", "설명과 다름", "천장이 아니라 벽이에요")
  requestRepairEdits(
    r,
    "임차인",
    { title: r.title, description: r.description, zone: "거실 · 벽" },
    "부위 고치기",
  )
  expect(r.ack).toBe("이견")
  expect(r.ackDispute?.reason).toBe("천장이 아니라 벽이에요")
  answerCorrection(r, "임대인", r.corrections![0].id, true)
  expect(r.zone).toBe("거실 · 벽")
  expect(r.ack).toBe("확인 대기")
  expect(r.corrections![0].priorDispute).toMatch("천장이 아니라 벽이에요")
  expect(r.version).toBe(2)
})

test("REP-7d·7j 확인할 때 본 모습 번호가 지금과 다르거나, 보던 고친 내용이 다시 고쳐졌으면 다시 보고 확인해야 한다", () => {
  const fresh = repair("확인 대기")
  expect(() => confirmDefect(fresh, "임대인", 0)).toThrow(
    VERSION_CONFLICT_MESSAGE,
  )
  confirmDefect(fresh, "임대인", 1)
  expect(fresh.ack).toBe("확인됨")

  const r = repair("확인됨")
  requestRepairEdits(
    r,
    "임차인",
    { title: "1번 제목", description: r.description, zone: r.zone },
    "고치기",
  )
  const seen = r.corrections![0].id
  requestRepairEdits(
    r,
    "임차인",
    { title: "2번 제목", description: r.description, zone: r.zone },
    "다시 고치기",
  )
  expect(() => answerCorrection(r, "임대인", seen, true)).toThrow(
    VERSION_CONFLICT_MESSAGE,
  )
})

test("점검 항목: 작성 중과 상대가 답하기 전에는 바로 고쳐지고, 상대가 답한 뒤에는 확인을 기다린다", () => {
  const d = createDemo()
  const i = d.inspections.find((x) => x.author === "임차인")!
  i.ack = "확인 대기"
  i.corrections = []
  i.version = 1
  i.publication = "draft"
  requestItemEdits(
    d,
    "임차인",
    i.id,
    { title: "초안 제목", description: i.description, zone: i.zone },
    "",
  )
  expect(i.title).toBe("초안 제목")
  i.publication = "requested"
  const room = d.rooms.find((r) => r.id === i.roomId)!
  const session = sessionOf(room, i.kind)
  if (session) session.status = "확인 요청"
  requestItemEdits(
    d,
    "임차인",
    i.id,
    { title: "답하기 전 제목", description: i.description, zone: i.zone },
    "",
  )
  expect(i.title).toBe("답하기 전 제목")
  expect(() => confirmItem(d, "임대인", i.id, 1)).toThrow(
    VERSION_CONFLICT_MESSAGE,
  )
  confirmItem(d, "임대인", i.id, i.version)
  expect(i.ack).toBe("확인됨")
  requestItemEdits(
    d,
    "임차인",
    i.id,
    { title: "확인 뒤 제목", description: i.description, zone: i.zone },
    "다시 봤어요",
  )
  expect(i.title).toBe("답하기 전 제목")
  answer(d, "임대인", i.id, i.corrections!.at(-1)!.id, true)
  expect(i.title).toBe("확인 뒤 제목")
})

test("저장 규칙 3·REP-7h: 칸마다 기다리는 고친 내용은 하나, 기록의 확인 요청은 하나로 묶인다", () => {
  const r = repair("확인됨")
  requestRepairEdits(
    r,
    "임차인",
    { title: "제목 1", description: r.description, zone: r.zone },
    "첫 고치기",
  )
  requestRepairEdits(
    r,
    "임차인",
    { title: "제목 1", description: "설명 2", zone: r.zone },
    "두 번째",
  )
  requestRepairEdits(
    r,
    "임차인",
    { title: "제목 3", description: "설명 2", zone: r.zone },
    "세 번째",
  )
  const waiting = r.corrections!.filter((c) => c.status === "확인 대기")
  expect(waiting.map((c) => `${c.field}:${c.to}`).sort()).toEqual([
    "설명:설명 2",
    "제목:제목 3",
  ])
  expect(r.corrections!.find((c) => c.to === "제목 1")!.status).toBe("대체됨")
  expect(pendingRequest(r.corrections!)!.items).toHaveLength(2)
})

test("REP-7k·7l 둘 다 종료를 확인하면 내용의 확인 전 고친 내용은 닫히고 더 고칠 수 없다", () => {
  const r = repair("확인됨")
  requestRepairEdits(
    r,
    "임차인",
    { title: "고친 제목", description: r.description, zone: r.zone },
    "다시 봤어요",
  )
  const pending = r.corrections![0].id
  r.closeBy = "임차인"
  confirmClose(r, "임대인")
  expect(r.corrections![0].status).toBe("처리 완료로 닫힘")
  expect(() => answerCorrection(r, "임대인", pending, true)).toThrow(
    CLOSED_BY_BOTH_MESSAGE,
  )
  expect(() =>
    requestRepairEdits(
      r,
      "임차인",
      { title: "또 고친 제목", description: r.description, zone: r.zone },
      "또",
    ),
  ).toThrow(CLOSED_BY_BOTH_MESSAGE)
})
