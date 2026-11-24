import { expect, test } from "vitest"
import { createDemo, type DemoState, type Inspection } from "../src/data/demo"
import {
  confirmItem,
  inspectionStatus,
  respondItem,
  supplementItem,
} from "../src/lib/inspection-rules"
import {
  inspectionAvailableActions,
  inspectionItemAvailableActions,
} from "../src/api/mock/inspection-actions"
import { NEW_EVIDENCE_REQUIRED } from "../src/lib/reask"

// 점검 항목 응답·보충(03 H, 06 INSP-1, 08 9장)
const NOW = Date.parse("2026-11-13T00:00:00Z")
function setup(count = 2) {
  const d: DemoState = createDemo()
  const room = d.rooms[0]
  room.status = "거주 중"
  room.connection = "연결됨"
  room.since = "2026-03-02"
  room.moveIn = { author: "임차인", status: "확인 요청", startedAt: "3/2" }
  d.inspections = Array.from({ length: count }, (_, n): Inspection => ({
    id: `i${n}`,
    roomId: room.id,
    kind: "in",
    title: `하자 ${n}`,
    zone: "욕실 · 벽·타일",
    description: `설명 ${n}`,
    photos: [`p${n}.jpg`],
    ack: "확인 대기",
    author: "임차인",
    art: "wall",
    publication: "requested",
    version: 1,
  }))
  const status = () => inspectionStatus(room, d.inspections, "in")
  return { d, room, status }
}

test("보류도 답한 것으로 쳐서, 모든 항목에 답이 붙으면 잠긴다", () => {
  const { d, status } = setup()
  confirmItem(d, "임대인", "i0", 1)
  expect(status()).toBe("확인 요청")
  respondItem(d, "임대인", "i1", "보류", "가까이 찍어 주세요")
  expect(d.inspections[1].ack).toBe("보충 요청")
  expect(status()).toBe("잠김")
})

test("보충 요청된 항목은 잠긴 뒤에도 보충하고 다시 요청할 수 있고, 점검은 잠긴 채로 남는다", () => {
  const { d, status } = setup(1)
  respondItem(d, "임대인", "i0", "보류", "가까이 찍어 주세요")
  expect(status()).toBe("잠김")
  expect(() =>
    supplementItem(d, "임차인", "i0", { text: "설명 0", photos: [] }, NOW),
  ).toThrow(NEW_EVIDENCE_REQUIRED)
  supplementItem(d, "임차인", "i0", { text: "", photos: ["new.jpg"] }, NOW)
  expect(d.inspections[0].ack).toBe("확인 대기")
  expect(status()).toBe("잠김")
})

test("보류·이견·확인 없음이던 항목도 맞아요로 답할 수 있지만, 맞아요는 되돌리지 않는다", () => {
  const { d } = setup(3)
  respondItem(d, "임대인", "i0", "이견", "도색 뒤라 없었어요")
  d.inspections[1].ack = "상대 미확인"
  confirmItem(d, "임대인", "i0", 1)
  confirmItem(d, "임대인", "i1", 1)
  expect(d.inspections.slice(0, 2).map((i) => i.ack)).toEqual([
    "확인됨",
    "확인됨",
  ])
  expect(() => respondItem(d, "임대인", "i0", "보류", "다시")).toThrow()
  expect(() => respondItem(d, "임차인", "i2", "보류", "내 항목")).toThrow()
})

test("점검 버튼은 행동 목록을 따른다", () => {
  const { d, room } = setup(1)
  expect(inspectionAvailableActions(d, room, "in", "임대인")).toEqual([
    "items",
    "confirm-all",
  ])
  expect(inspectionAvailableActions(d, room, "in", "임차인")).toEqual(["items"])
  expect(
    inspectionAvailableActions(d, room, "in", "임대인", { readOnly: true }),
  ).toEqual([])
  const i = d.inspections[0]
  expect(inspectionItemAvailableActions(i, "임대인")).toEqual(["respond"])
  expect(inspectionItemAvailableActions(i, "임차인")).toEqual([
    "corrections",
    "photos",
  ])
  respondItem(d, "임대인", "i0", "보류", "더 필요해요")
  expect(inspectionItemAvailableActions(i, "임차인")).toContain("supplement")
})

test("입주일이 비어 있으면 점검 시작(입주일 받기)만 할 수 있다(INSP-2b)", () => {
  const { d, room } = setup(0)
  room.moveIn = undefined
  room.since = undefined
  expect(inspectionAvailableActions(d, room, "in", "임차인")).toEqual(["start"])
})
