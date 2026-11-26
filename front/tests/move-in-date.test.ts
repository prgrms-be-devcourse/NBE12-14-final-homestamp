import { expect, test } from "vitest"
import type { DemoState, Room } from "../src/data/demo"
import {
  ensureAuthor,
  finishSoloInspection,
  requestInspectionAll,
  startInspection,
  MOVE_IN_DATE_REQUIRED,
} from "../src/lib/inspection-rules"
import {
  isLateMoveInInspection,
  moveInBaseDate,
  todayKey,
} from "../src/lib/workflows"

// INSP-2a·b: 입주일이 비면 입주 점검을 시작하지 않고, 늦었는지는 실제 입주일로만 정해요(호실 생성일 + 7일 없음).
const room = (r: Partial<Room> = {}): Room => ({
  id: "r1",
  name: "302호",
  address: "서울시 마포구 연남동",
  since: "",
  status: "거주 중",
  connection: "연결 전",
  received: false,
  createdAt: "2020-01-01",
  ...r,
})
const state = (r: Room) =>
  ({ rooms: [r], inspections: [] }) as unknown as DemoState

test("입주일이 없으면 기준일도 없고, 호실 생성일로 늦음을 판단하지 않는다", () => {
  expect(moveInBaseDate(room())).toBe("")
  expect(isLateMoveInInspection(room())).toBe(false)
})

test("입주일이 없으면 어떤 길로도 입주 점검이 시작되지 않는다", () => {
  expect(() => ensureAuthor(room(), "in", "임차인", false)).toThrow(
    MOVE_IN_DATE_REQUIRED,
  )
  expect(() => ensureAuthor(room(), "in", "임차인", true)).toThrow(
    MOVE_IN_DATE_REQUIRED,
  )
  expect(() =>
    requestInspectionAll(state(room()), "r1", "in", "임차인"),
  ).toThrow(MOVE_IN_DATE_REQUIRED)
  expect(() =>
    finishSoloInspection(state(room()), "r1", "in", "임차인"),
  ).toThrow(MOVE_IN_DATE_REQUIRED)
  expect(() =>
    startInspection(state(room()), "r1", "in", "임차인", ""),
  ).toThrow(MOVE_IN_DATE_REQUIRED)
})

test("퇴거 점검은 입주일과 상관없이 시작된다", () => {
  const r = room()
  ensureAuthor(r, "out", "임차인", false)
  expect(r.moveOut?.status).toBe("작성 중")
})

test("입주일을 받으면 저장과 시작이 한 번에 되고, 지난 입주일이면 입주 후 등록 날짜가 남는다", () => {
  const d = state(room())
  startInspection(d, "r1", "in", "임차인", "2025-11-20")
  expect(d.rooms[0].since).toBe("2025-11-20")
  expect(d.rooms[0].moveIn).toMatchObject({
    author: "임차인",
    status: "작성 중",
    lateOn: todayKey(),
  })
  expect(() => startInspection(d, "r1", "in", "임대인", "2025-11-20")).toThrow(
    /이미 시작된 점검/,
  )
})

test("입주일 당일이나 앞으로의 날짜에 시작하면 입주 후 등록이 붙지 않는다", () => {
  const d = state(room())
  startInspection(d, "r1", "in", "임대인", todayKey())
  expect(d.rooms[0].moveIn?.lateOn).toBeUndefined()
  const r = room({ since: "2999-01-01" })
  ensureAuthor(r, "in", "임차인", false)
  expect(r.moveIn?.lateOn).toBeUndefined()
})
