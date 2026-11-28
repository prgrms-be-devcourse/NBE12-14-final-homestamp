import { fireEvent, render, screen } from "@testing-library/react"
import { expect, test, vi } from "vitest"
import { RoomFinancials } from "../src/features/settlement/room-financials"
import { httpAvailableActions } from "../src/api/http-available-actions"
import { settlementFromDetail } from "../src/api/http-settlement-view"
import type { RecordRequest } from "../src/api/records"
import type { Role, Room } from "../src/data/demo"

// 정산 송금(SET-4)을 서버 값으로 보여 주는 화면(#442). 합계·상태·[받았어요]·[받았어요(혼자)]
const room: Room = {
  id: "302",
  name: "302호",
  address: "연남동",
  since: "2026-03-01",
  status: "거주 종료",
  connection: "연결됨",
  received: false,
}
const base = {
  direction: "REFUND" as const,
  recordedBySide: "LANDLORD" as const,
  paidOn: "2026-11-27",
  versionNo: 4,
  confirmedAmount: null,
  sameAsTransferId: null,
  receiptConfirmedAt: null,
  createdAt: "2026-11-27T01:00:00Z",
  corrections: [],
}
const serverOf = (
  transfers: Parameters<typeof settlementFromDetail>[0]["transfers"],
) =>
  settlementFromDetail({
    id: "s1",
    roomRecordId: "302",
    mySide: "TENANT",
    depositStatus: "AGREED",
    snapshotNo: 7,
    createdAt: "2026-11-27T00:00:00Z",
    unansweredForMe: 0,
    availableActions: ["transfers"],
    transfers,
    confirmedRefund: 10_000_000,
    confirmedExtra: 0,
  })
function view(role: Role, server: ReturnType<typeof serverOf>) {
  const onAction = vi.fn<(r: RecordRequest, text: string) => void>()
  render(
    <RoomFinancials
      room={room}
      role={role}
      items={[]}
      onAction={onAction}
      onAdd={() => {}}
      actions={httpAvailableActions.settlement(
        { ...room, settlement: server },
        role,
      )}
      itemActionsOf={() => []}
      transferActionsOf={(t) => httpAvailableActions.transfer(t, role)}
      server={server}
    />,
  )
  return onAction
}

test("합계는 서버가 센 확인된 송금이고, 상태는 기록한 측과 답으로 보여요", () => {
  view(
    "임차인",
    serverOf([
      {
        ...base,
        id: "t-1",
        amount: 10_000_000,
        response: "ACK",
        availableActions: [],
      },
      {
        ...base,
        id: "t-2",
        direction: "EXTRA",
        recordedBySide: "LANDLORD",
        amount: 30_000,
        response: "PENDING",
        availableActions: [],
      },
    ]),
  )
  expect(screen.getByText("10,000,000원")).toBeTruthy()
  expect(screen.getByText("세입자 받았어요")).toBeTruthy()
  // 집주인이 남긴 추가 송금 = 받은 쪽 기록([받았어요(혼자)]): 보낸 쪽(세입자) 확인 기다림
  expect(screen.getByText("세입자 확인 기다림")).toBeTruthy()
})

test("보낸 쪽 기록에는 받은 쪽이 [받았어요]로 답하고, 본 판 번호를 보내요", () => {
  const onAction = view(
    "임차인",
    serverOf([
      {
        ...base,
        id: "t-1",
        amount: 9_845_000,
        response: "PENDING",
        availableActions: ["respond"],
      },
    ]),
  )
  // 합계 칸의 "받았는지 확인 중"과 송금 줄의 상태 두 곳
  expect(screen.getAllByText("받았는지 확인 중")).toHaveLength(2)
  fireEvent.click(screen.getByRole("button", { name: "받았어요" }))
  expect(onAction.mock.calls[0][0]).toEqual({
    path: "/api/settlement-transfers/t-1/respond",
    body: { answer: "ACK", seenVersionNo: 4 },
  })
})

test("금액이 달라요로 답한 받은 쪽은 [받았어요(혼자)]로 받은 금액을 묶어 적어요(SET-4e)", () => {
  const onAction = view(
    "임차인",
    serverOf([
      {
        ...base,
        id: "t-1",
        amount: 9_845_000,
        response: "DISPUTE",
        availableActions: ["respond"],
      },
    ]),
  )
  expect(screen.getByText("세입자: 금액이 달라요")).toBeTruthy()
  fireEvent.click(screen.getByRole("button", { name: "받았어요(혼자)" }))
  fireEvent.change(screen.getByLabelText("금액 (원)"), {
    target: { value: "9,800,000" },
  })
  fireEvent.change(screen.getByLabelText("받은 날짜"), {
    target: { value: "2026-11-26" },
  })
  fireEvent.click(screen.getByRole("button", { name: "사실 기록 남기기" }))
  expect(onAction.mock.calls[0][0]).toEqual({
    path: "/api/settlements/s1/transfers",
    body: {
      direction: "REFUND",
      amount: 9_800_000,
      paidOn: "2026-11-26",
      sameAsTransferId: "t-1",
    },
  })
})

test("묶어 적은 받은 금액은 보낸 쪽 기록 아래에 함께 보이고 다시 적을 수 없어요", () => {
  view(
    "임차인",
    serverOf([
      {
        ...base,
        id: "t-1",
        amount: 9_845_000,
        response: "DISPUTE",
        availableActions: ["respond"],
      },
      {
        ...base,
        id: "t-2",
        recordedBySide: "TENANT",
        amount: 9_800_000,
        response: "PENDING",
        sameAsTransferId: "t-1",
        availableActions: [],
      },
    ]),
  )
  expect(screen.getByText(/받은 금액 9,800,000원\(세입자 기록\)/)).toBeTruthy()
  expect(screen.getByText("집주인 확인 기다림")).toBeTruthy()
  expect(screen.queryByRole("button", { name: "받았어요(혼자)" })).toBeNull()
})
