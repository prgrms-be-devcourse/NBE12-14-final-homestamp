import { describe, expect, test } from "vitest"
import {
  repairFromDetail,
  repairFromListItem,
  type RepairCaseDetailBody,
  type RepairCaseListItemBody,
} from "../src/api/http-repair-view"
import { repairSummary } from "../src/features/repair/repair-summary"
import { myTurn, theirWaits, waitText } from "../src/lib/turn"
import type { Room } from "../src/data/demo"

// 수리 목록의 열린 응답 요청(06 4-6 waiting)으로 '내 차례'·'확인 기다림'을 보여요(#364)
const item = (
  patch: Partial<RepairCaseListItemBody> = {},
): RepairCaseListItemBody => ({
  id: "c1",
  registeredBy: "TENANT",
  createdAt: "2026-11-20T01:00:00Z",
  content: { no: 1, partName: "천장", title: "천장 누수" },
  status: "NOT_STARTED",
  lastActionAt: "2026-11-24T03:00:00Z",
  closeRequestedBy: null,
  closedBy: null,
  defect: { ack: "PENDING" },
  money: { status: "UNDECIDED" },
  waiting: [],
  ...patch,
})
const wait = (
  patch: Partial<RepairCaseListItemBody["waiting"][number]> = {},
): RepairCaseListItemBody["waiting"][number] => ({
  kind: "DEFECT_CONFIRM",
  answeringSide: "LANDLORD",
  status: "WAITING",
  dueAt: null,
  soloReason: null,
  ...patch,
})

describe("목록 waiting → 내 차례", () => {
  test("답할 측이 나면 그 요청 종류가 내 차례예요", () => {
    const r = repairFromListItem(item({ waiting: [wait()] }), "r1")
    expect(myTurn(r, "임대인")).toBe("하자 확인")
    expect(myTurn(r, "임차인")).toBeUndefined()
  })

  test("서버가 요청을 주지 않으면 상태가 확인 대기여도 내 차례가 아니에요(공유를 그만둔 뒤 등)", () => {
    const r = repairFromListItem(item(), "r1")
    expect(r.ack).toBe("확인 대기")
    expect(myTurn(r, "임대인")).toBeUndefined()
  })

  test("확인 없음이 돼도 늦게 답할 수 있어 내 차례는 그대로예요", () => {
    const r = repairFromListItem(
      item({
        status: "CLOSE_WAIT",
        closeRequestedBy: "TENANT",
        waiting: [
          wait({
            kind: "CLOSE_CONFIRM",
            status: "UNCONFIRMED",
            soloReason: "UNCONFIRMED",
          }),
        ],
      }),
      "r1",
    )
    expect(myTurn(r, "임대인")).toBe("종료 확인")
  })

  test("모르는 종류가 와도 일반 이름으로 보여요", () => {
    const r = repairFromListItem(
      item({ waiting: [wait({ kind: "SOMETHING_NEW" })] }),
      "r1",
    )
    expect(myTurn(r, "임대인")).toBe("확인 요청")
  })

  test("목업(waiting 없음)은 예전처럼 상태로 셉니다", () => {
    const r = { ...repairFromListItem(item(), "r1"), waiting: undefined }
    expect(myTurn(r, "임대인")).toBe("하자 확인")
  })
})

describe("목록 waiting → 상대 확인 기다림", () => {
  const now = Date.parse("2026-11-27T00:00:00Z")

  test("기한이 있으면 남은 시간을, 없으면 시간 없이 보여요", () => {
    const r = repairFromListItem(
      item({
        waiting: [
          wait({ dueAt: "2026-11-29T06:00:00Z" }),
          wait({ kind: "PROGRESS_CHECK" }),
        ],
      }),
      "r1",
    )
    expect(theirWaits(r, "임차인", now)).toEqual([
      "하자 확인 · 집주인 확인 기다림 · 2일 남음",
      "진행 상황 답하기 · 집주인 확인 기다림",
    ])
    // 내가 답할 것은 내 차례로만 보여요
    expect(theirWaits(r, "임대인", now)).toEqual([])
  })

  test("확인 없음은 남은 시간 없이 이유와 함께 보여요", () => {
    const r = repairFromListItem(
      item({
        waiting: [
          wait({
            status: "UNCONFIRMED",
            dueAt: "2026-11-26T00:00:00Z",
            soloReason: "OPTED_OUT",
          }),
        ],
      }),
      "r1",
    )
    expect(theirWaits(r, "임차인", now)).toEqual([
      "하자 확인 · 집주인 확인 없음 · 문자를 그만 받기로 했어요",
    ])
  })

  test("한 시간이 안 남아도 1시간으로, 지났으면 시간 없이 보여요", () => {
    const w = {
      kind: "종료 확인",
      by: "임대인" as const,
      unconfirmed: false,
    }
    expect(waitText({ ...w, dueAt: "2026-11-27T00:30:00Z" }, now)).toBe(
      "종료 확인 · 집주인 확인 기다림 · 1시간 남음",
    )
    expect(waitText({ ...w, dueAt: "2026-11-26T23:00:00Z" }, now)).toBe(
      "종료 확인 · 집주인 확인 기다림",
    )
  })
})

describe("상세 closeRequest → 종료 요청 확인 없음", () => {
  const detail = (
    closeRequest: RepairCaseDetailBody["closeRequest"],
  ): RepairCaseDetailBody => ({
    id: "c1",
    roomRecordId: "r1",
    mySide: "TENANT",
    registeredBy: "TENANT",
    status: "CLOSE_WAIT",
    closeRequestedBy: "TENANT",
    closedBy: null,
    closeObjection: null,
    solo: false,
    createdAt: "2026-11-20T01:00:00Z",
    asOf: null,
    content: {
      no: 1,
      roomPartId: "p1",
      partName: "천장",
      title: "천장 누수",
      description: null,
    },
    defect: { ack: "ACK", nextRerequestAt: null },
    money: { status: "UNDECIDED" },
    photos: [],
    causeNotes: [],
    closeRequest,
    availableActions: [],
  })
  const room = { id: "r1" } as Room

  test("확인 없음이면 그 날짜와 이유를 요약에 보여요", () => {
    const { repair } = repairFromDetail(
      detail({
        status: "UNCONFIRMED",
        dueAt: "2026-11-26T03:00:00Z",
        soloReason: "UNCONFIRMED",
      }),
    )
    expect(repairSummary(repair, "임차인", room, [])).toEqual({
      title: "종료 요청됨 · 집주인 확인 없음(11월 26일부터)",
      body: "72시간 동안 답이 오지 않았어요. 집주인이 늦게라도 확인하면 끝나요.",
    })
  })

  test("기다리는 중이면 예전 문구 그대로예요", () => {
    const { repair } = repairFromDetail(
      detail({ status: "WAITING", dueAt: null, soloReason: null }),
    )
    expect(repair.closeUnconfirmed).toBeUndefined()
    expect(repairSummary(repair, "임차인", room, []).title).toBe(
      "끝난 것 확인을 기다려요",
    )
  })
})
