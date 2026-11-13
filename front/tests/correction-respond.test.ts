import { expect, test } from "vitest"
import { createDemo, type Inspection, type Repair } from "../src/data/demo"
import {
  answerCorrection,
  requestRepairEdits,
  resubmitRepairCorrection,
} from "../src/lib/repair-rules"
import { answer } from "../src/lib/inspection-rules"
import { correctionAvailableActions } from "../src/api/mock/correction-actions"
import { NEW_EVIDENCE_REQUIRED } from "../src/lib/reask"

// 고친 내용 응답과 [사진 더해서 다시 요청](03 G표, 08 3장 장면 4)
const NOW = Date.parse("2026-11-13T00:00:00Z")
const DAY = 86_400_000
const stamped = (): Repair => ({
  ...structuredClone(createDemo().repairs[0]),
  author: "임차인",
  ack: "확인됨",
  status: "처리 중",
  soloClosed: undefined,
  corrections: [],
  version: 1,
})

test("고친 내용에 보류로 답하면 앞모습은 그대로이고, 고친 쪽은 근거를 더해 다시 요청할 수 있다", () => {
  const r = stamped()
  const before = r.title
  requestRepairEdits(
    r,
    "임차인",
    { title: "고친 제목", description: r.description, zone: r.zone },
    "다시 봤어요",
  )
  const c = r.corrections![0]
  expect(correctionAvailableActions(c, "임대인", r.corrections!)).toEqual([
    "respond",
  ])
  answerCorrection(r, "임대인", c.id, false, "사진이 필요해요", "보류")
  expect(r.title).toBe(before)
  expect([c.status, c.answerKind]).toEqual(["거절", "보류"])
  expect(correctionAvailableActions(c, "임차인", r.corrections!)).toEqual([
    "resubmit",
  ])
  // 같은 이유만으로는 다시 요청이 되지 않아요
  expect(() =>
    resubmitRepairCorrection(
      r,
      "임차인",
      c.id,
      { text: "다시 봤어요", photos: [] },
      NOW,
    ),
  ).toThrow(NEW_EVIDENCE_REQUIRED)
  resubmitRepairCorrection(
    r,
    "임차인",
    c.id,
    { text: "", photos: ["e.jpg"] },
    NOW,
  )
  expect(c.status).toBe("확인 대기")
  expect(c.answers).toHaveLength(1)
  answerCorrection(r, "임대인", c.id, false, "여전히 달라요", "이견")
  expect(() =>
    resubmitRepairCorrection(
      r,
      "임차인",
      c.id,
      { text: "또 근거", photos: [] },
      NOW + 2 * DAY,
    ),
  ).toThrow("5일 뒤")
})

test("같은 칸을 새로 고쳤으면 지난 고친 내용은 다시 요청하지 않는다", () => {
  const r = stamped()
  requestRepairEdits(
    r,
    "임차인",
    { title: "첫 제목", description: r.description, zone: r.zone },
    "첫 이유",
  )
  const first = r.corrections![0]
  answerCorrection(r, "임대인", first.id, false, "달라요", "이견")
  requestRepairEdits(
    r,
    "임차인",
    { title: "둘째 제목", description: r.description, zone: r.zone },
    "둘째 이유",
  )
  expect(correctionAvailableActions(first, "임차인", r.corrections!)).toEqual(
    [],
  )
})

test("빠뜨린 하자 더하기에 이견으로 답해도 항목은 남고 이견이 붙는다(새 기록, 03 H)", () => {
  const d = createDemo()
  const room = d.rooms[0]
  const item: Inspection = {
    id: "add1",
    roomId: room.id,
    kind: "in",
    title: "방충망 찢어짐",
    zone: "베란다 · 방충망",
    description: "찢어져 있어요",
    photos: [],
    ack: "확인 대기",
    author: "임대인",
    art: "wall",
    publication: "requested",
    addRequest: {
      id: "req1",
      by: "임대인",
      field: "빠진 하자 추가",
      from: "기록 없음",
      to: "베란다 · 방충망 · 방충망 찢어짐",
      reason: "빠뜨렸어요",
      at: "11/30",
      status: "확인 대기",
    },
  }
  d.inspections.push(item)
  answer(d, "임차인", "add1", "req1", false, "입주 때는 멀쩡했어요", "이견")
  expect(item.addRequest!.status).toBe("반영")
  expect(item.ack).toBe("이견")
  expect(item.disputes!.at(-1)).toMatchObject({ kind: "이견" })
})
