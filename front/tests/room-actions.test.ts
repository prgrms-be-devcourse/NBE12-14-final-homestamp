import { expect, test } from "vitest"
import { createDemo, type Room } from "../src/data/demo"
import { roomAvailableActions } from "../src/api/mock/room-actions"

// 호실 기록(S-06) 버튼은 서버가 준 행동 목록을 따른다(06 4장 GET /api/rooms/{id})
const room = (patch: Partial<Room> = {}): Room => ({
  ...structuredClone(createDemo().rooms[0]),
  status: "거주 중",
  connection: "연결됨",
  creator: "임차인",
  endRequested: false,
  endWait: undefined,
  ...patch,
})

test("초대는 호실을 만든 쪽이 상대와 연결되기 전에만 한다", () => {
  expect(roomAvailableActions(room(), "임차인")).not.toContain("invite")
  const before = room({ connection: "연결 전" })
  expect(roomAvailableActions(before, "임차인")).toContain("invite")
  expect(roomAvailableActions(before, "임대인")).not.toContain("invite")
})

test("거주 종료: 집주인이 요청하면 세입자는 맞아요·아직 살고 있어요로 답하고, 답이 없으면 집주인이 혼자 종료할 수 있다", () => {
  const asked = room({ endRequested: true })
  expect(roomAvailableActions(asked, "임차인")).toEqual(
    expect.arrayContaining(["move-out-confirm", "move-out-reject"]),
  )
  expect(roomAvailableActions(asked, "임대인")).not.toContain("move-out")
  expect(
    roomAvailableActions({ ...asked, endWait: "미확인" }, "임대인"),
  ).toContain("move-out-alone")
})

test("거주가 끝났거나 읽기 전용이면 하자 등록·거주 종료가 없다", () => {
  expect(
    roomAvailableActions(room({ status: "거주 종료" }), "임차인"),
  ).not.toContain("repair-cases")
  expect(roomAvailableActions(room(), "임차인", { readOnly: true })).toEqual([])
})
