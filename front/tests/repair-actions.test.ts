import { expect, test } from "vitest"
import { createDemo, type Repair, type Room } from "../src/data/demo"
import { repairAvailableActions } from "../src/api/mock/repair-actions"

// 서버 대신 계산하는 availableActions(06 4장, 08 0장 원칙 6). 화면은 이 목록에 있는 버튼만 그려요.
const setup = (patch: Partial<Repair> = {}, roomPatch: Partial<Room> = {}) => {
  const d = createDemo()
  const r: Repair = {
    ...structuredClone(d.repairs[0]),
    author: "임차인",
    ack: "확인 대기",
    status: "확인 전",
    closeBy: undefined,
    soloClosed: undefined,
    ...patch,
  }
  const room: Room = {
    ...structuredClone(d.rooms.find((x) => x.id === r.roomId)!),
    status: "거주 중",
    connection: "연결됨",
    counterpartLeft: false,
    optedOut: false,
    ...roomPatch,
  }
  return { r, room }
}

test("링크 열람자와 읽기 전용에는 행동이 없다", () => {
  const { r, room } = setup()
  expect(repairAvailableActions(r, room, "임대인", { guest: true })).toEqual([])
  expect(repairAvailableActions(r, room, "임대인", { readOnly: true })).toEqual(
    [],
  )
})

test("확인 기다림: 상대 쪽은 응답할 수 있고, 등록한 쪽은 응답 대신 고치기를 할 수 있다", () => {
  const { r, room } = setup()
  const them = repairAvailableActions(r, room, "임대인")
  const mine = repairAvailableActions(r, room, "임차인")
  expect(them).toContain("respond")
  expect(them).not.toContain("corrections")
  expect(mine).not.toContain("respond")
  expect(mine).toContain("corrections")
  expect(mine).toContain("start")
})

test("처리 중: 상대가 있으면 종료 요청, 상대가 없으면 혼자 종료", () => {
  const { r, room } = setup({ status: "처리 중", ack: "확인됨" })
  expect(repairAvailableActions(r, room, "임차인")).toContain("request-close")
  const alone = setup(
    { status: "처리 중", ack: "확인됨" },
    { connection: "연결 전" },
  )
  const acts = repairAvailableActions(alone.r, alone.room, "임차인")
  expect(acts).toContain("close-alone")
  expect(acts).not.toContain("request-close")
})

test("종료 요청을 받은 쪽만 종료 확인 · 아직 안 됐어요를 누를 수 있다", () => {
  const { r, room } = setup({ status: "처리 중", closeBy: "임차인" })
  expect(repairAvailableActions(r, room, "임대인")).toEqual(
    expect.arrayContaining(["confirm-close", "not-yet"]),
  )
  expect(repairAvailableActions(r, room, "임차인")).not.toContain(
    "confirm-close",
  )
})

test("둘 다 확인해 끝난 건은 고칠 수 없고 재발만 남길 수 있다(REP-7k)", () => {
  const { r, room } = setup({ status: "처리 완료", ack: "확인됨" })
  const acts = repairAvailableActions(r, room, "임차인")
  expect(acts).not.toContain("corrections")
  expect(acts).toContain("recur")
})

test("혼자 종료된 건은 확인하지 않은 쪽이 종료 확인하거나 다시 열 수 있다", () => {
  const { r, room } = setup({
    status: "처리 완료",
    ack: "확인됨",
    soloClosed: "임차인",
  })
  expect(repairAvailableActions(r, room, "임대인")).toEqual(
    expect.arrayContaining(["confirm-close", "reopen"]),
  )
  expect(repairAvailableActions(r, room, "임차인")).not.toContain("reopen")
})
