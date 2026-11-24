import { expect, test } from "vitest"
import { createDemo, type Room, type Settlement } from "../src/data/demo"
import {
  confirmedTransferTotal,
  confirmFinish,
  finishAlone,
  finishState,
  pendingTransferTotal,
  rejectFinish,
  requestFinish,
  SOLO_NOT_ALLOWED_MESSAGE,
} from "../src/lib/settlement-rules"
import { settlementAvailableActions } from "../src/api/mock/settlement-actions"
import { VERSION_CONFLICT_MESSAGE } from "../src/lib/versions"

// 정산 끝내기(06 SET-5a~e)와 송금 합계(SET-4b, 03 J, 08 10장)
const NOW = Date.parse("2026-11-13T00:00:00Z")
const HOUR = 3_600_000
const DAY = 24 * HOUR
function setup(): { room: Room; items: Settlement[] } {
  const d = createDemo()
  const room: Room = {
    ...structuredClone(d.rooms.find((r) => r.id === d.settlements[0].roomId)!),
    status: "거주 종료",
    connection: "연결됨",
    finishBy: undefined,
    finishClosed: undefined,
    transfers: [],
    transferAmount: undefined,
  }
  const items = structuredClone(
    d.settlements.filter((s) => s.roomId === d.settlements[0].roomId),
  )
  return { room, items }
}

test("SET-4b 송금 합계는 상대가 확인한 송금만 더하고, 확인 전 송금은 따로 센다", () => {
  const { room } = setup()
  room.transfers = [
    {
      id: "t1",
      by: "임대인",
      amount: 9_000_000,
      direction: "반환",
      at: "1/5",
      confirmed: true,
    },
    {
      id: "t2",
      by: "임대인",
      amount: 845_000,
      direction: "반환",
      at: "1/6",
      confirmed: false,
    },
  ]
  expect(confirmedTransferTotal(room)).toBe(9_000_000)
  expect(pendingTransferTotal(room)).toBe(845_000)
})

test("SET-5a 끝내기는 상대가 확인해야 완료된다", () => {
  const { room, items } = setup()
  requestFinish(room, items, "임차인", NOW)
  expect(room.status).toBe("거주 종료")
  expect(() => confirmFinish(room, items, "임차인", NOW)).toThrow()
  confirmFinish(room, items, "임대인", NOW + HOUR)
  expect(room.status).toBe("정산 완료")
})

test("SET-5b·5e 끝내기를 기다리는 동안 정산이 바뀌면 요청이 닫히고 확인해도 완료되지 않는다", () => {
  const { room, items } = setup()
  requestFinish(room, items, "임차인", NOW)
  items[0].amount += 10_000
  expect(finishState(room, items, NOW).kind).toBe("changed")
  expect(() => confirmFinish(room, items, "임대인", NOW)).toThrow(
    VERSION_CONFLICT_MESSAGE,
  )
  expect(room.status).toBe("거주 종료")
  expect(room.finishClosed?.reason).toBe("changed")
  // 바뀌어 닫힌 요청은 7일 제한 없이 다시 보낼 수 있어요
  requestFinish(room, items, "임차인", NOW + HOUR)
  expect(room.finishBy).toBe("임차인")
})

test("SET-5b 같은 금액으로 다른 안을 제안해도 이전 끝내기 요청이 닫힌다", () => {
  const { room, items } = setup()
  const item = items[0]
  item.status = "합의 대기"
  item.by = "임차인"
  item.round = 1
  item.proposalReason = "처음 사유"
  requestFinish(room, items, "임차인", NOW)

  item.by = "임대인"
  item.round = 2
  item.proposalReason = "같은 금액을 다른 근거로 제안해요"

  expect(finishState(room, items, NOW).kind).toBe("changed")
  expect(() => confirmFinish(room, items, "임대인", NOW)).toThrow(
    VERSION_CONFLICT_MESSAGE,
  )
  expect(room.status).toBe("거주 종료")
})

test("SET-5c 아직 안 끝났어요로 답하면 혼자 끝낼 수 없고 다시 요청은 7일 뒤", () => {
  const { room, items } = setup()
  requestFinish(room, items, "임차인", NOW)
  expect(() => rejectFinish(room, "임대인", " ", NOW)).toThrow()
  rejectFinish(room, "임대인", "관리비 정산이 남았어요", NOW + HOUR)
  expect(() => finishAlone(room, items, "임차인", NOW + 4 * DAY)).toThrow(
    SOLO_NOT_ALLOWED_MESSAGE,
  )
  expect(() => requestFinish(room, items, "임차인", NOW + 2 * DAY)).toThrow(
    "일 뒤 다시 요청할 수 있어요",
  )
  requestFinish(room, items, "임차인", NOW + 8 * DAY)
  expect(room.finishBy).toBe("임차인")
})

test("SET-5d 72시간 답이 없으면 요청한 쪽이 혼자 끝낼 수 있다", () => {
  const { room, items } = setup()
  requestFinish(room, items, "임차인", NOW)
  expect(
    settlementAvailableActions(room, "임차인", {}, items, NOW + HOUR),
  ).not.toContain("finish-alone")
  expect(() => finishAlone(room, items, "임차인", NOW + HOUR)).toThrow()
  const later = NOW + 73 * HOUR
  expect(
    settlementAvailableActions(room, "임차인", {}, items, later),
  ).toContain("finish-alone")
  expect(() => finishAlone(room, items, "임대인", later)).toThrow()
  finishAlone(room, items, "임차인", later)
  expect([room.status, room.finishedAlone]).toEqual(["정산 완료", "임차인"])
})

test("연결 전 상대면 정산을 혼자 정리하고 바로 끝낸다(03 1-4)", () => {
  const { room, items } = setup()
  room.connection = "연결 전"
  requestFinish(room, items, "임차인", NOW)
  expect([room.status, room.finishedAlone]).toEqual(["정산 완료", "임차인"])
})
