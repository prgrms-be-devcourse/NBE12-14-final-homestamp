import { expect, test } from "vitest"
import { createHttpRecords } from "../src/api/http-records"
import {
  settlementFromDetail,
  type SettlementDetailBody,
} from "../src/api/http-settlement-view"
import { httpAvailableActions } from "../src/api/http-available-actions"
import { isConnected } from "../src/api/connected"
import { ApiError } from "../src/api/errors"
import type { HttpRequest } from "../src/api/http-client"
import { endpoints } from "../src/api/endpoints"
import {
  post,
  settlementIdIn,
  settlementIdOf,
  type RecordChange,
} from "../src/api/records"
import type { Room } from "../src/data/demo"

// 정산 API 연결(06 4-8, #442): 호실의 정산 받기, 열기, 끝내기 주소
const s = { accountId: "dev-tenant" }
const ROOM = "302"
const SETTLEMENT = "0193a000-0000-7000-8000-0000000000aa"

const settlement = (
  over: Partial<SettlementDetailBody> = {},
): SettlementDetailBody => ({
  id: SETTLEMENT,
  roomRecordId: ROOM,
  mySide: "TENANT",
  depositStatus: "PENDING",
  snapshotNo: 3,
  createdAt: "2026-11-27T00:00:00Z",
  unansweredForMe: 2,
  availableActions: [],
  ...over,
})
const roomDetail = {
  id: ROOM,
  alias: "302호",
  region: {
    code: "1144012400",
    sido: "서울특별시",
    sigungu: "마포구",
    dong: "연남동",
  },
  moveInDate: "2026-03-01",
  status: "ENDED",
  mySide: "TENANT",
  createdBySide: "TENANT",
  myStatus: "CONNECTED",
  counterpartStatus: "CONNECTED",
  joinedAway: false,
  asOf: null,
  createdAt: "2026-11-01T00:00:00Z",
  availableActions: [],
}
const notFound = () =>
  new ApiError({ status: 404, code: "NOT_FOUND", detail: "찾을 수 없어요" })

// 호실 하나와 그 정산을 주는 가짜 서버. opened가 거짓이면 호실의 정산은 404예요
function fakeServer(opts: {
  opened?: boolean
  detail?: SettlementDetailBody
  fail?: ApiError
  settlementFail?: Error
}) {
  const calls: { method: string; path: string; body?: unknown }[] = []
  const request = (async (
    method: string,
    path: string,
    o?: { body?: unknown },
  ) => {
    calls.push({ method, path, body: o?.body })
    if (method === "POST") {
      if (opts.fail) throw opts.fail
      if (path === endpoints.openSettlement(ROOM)) {
        opts.opened = true
        return { id: SETTLEMENT }
      }
      return undefined
    }
    if (path.startsWith(`${endpoints.rooms}?`))
      return { items: [{ ...roomDetail, nextStep: "NONE" }], nextCursor: null }
    if (path === endpoints.room(ROOM)) return roomDetail
    if (path.startsWith(endpoints.repairCases(ROOM)))
      return { asOf: null, mySide: "TENANT", items: [], nextCursor: null }
    if (path === endpoints.timeline(ROOM))
      return { mySide: "TENANT", asOf: null, items: [] }
    if (path === endpoints.openSettlement(ROOM)) {
      if (opts.settlementFail) throw opts.settlementFail
      if (!opts.opened) throw notFound()
      return opts.detail ?? settlement()
    }
    if (path === endpoints.notifications) return []
    throw new Error(`모르는 주소: ${path}`)
  }) as HttpRequest
  return { calls, request }
}
const change = (request: RecordChange["request"]): RecordChange => ({
  request,
  role: "임차인",
  text: "정산 · 시험",
  update: () => {},
  options: { roomId: ROOM },
})
const roomOf = (records: ReturnType<typeof createHttpRecords>) =>
  records.getSnapshot().rooms.find((r) => r.id === ROOM) as Room

test("서버 정산을 화면 모델로 바꾼다: 버튼은 연결한 끝내기 행동만 남긴다", () => {
  expect(
    settlementFromDetail(
      settlement({
        availableActions: ["finish-confirm", "finish-not-yet", "items", "x"],
      }),
    ),
  ).toEqual({
    id: SETTLEMENT,
    depositStatus: "PENDING",
    snapshotNo: 3,
    createdAt: "2026-11-27T00:00:00Z",
    unansweredForMe: 2,
    availableActions: ["finish-confirm", "finish-not-yet"],
  })
})

test("호실을 받을 때 정산도 받고, 아직 열지 않았으면(404) null이다", async () => {
  const opts = { opened: false }
  const records = createHttpRecords(fakeServer(opts).request)
  await records.load(s)
  expect(roomOf(records).settlement).toBeNull()

  opts.opened = true
  await records.loadRoom(s, ROOM)
  expect(roomOf(records).settlement?.id).toBe(SETTLEMENT)
  expect(settlementIdIn(roomOf(records))).toBe(SETTLEMENT)
})

test("정산 받기만 실패하면 앞서 받은 정산을 둔다(호실은 그대로 받는다)", async () => {
  const opts: { opened: boolean; settlementFail?: Error } = { opened: true }
  const records = createHttpRecords(fakeServer(opts).request)
  await records.load(s)
  opts.settlementFail = new Error("연결 끊김")
  await records.loadRoom(s, ROOM)
  expect(roomOf(records).settlement?.id).toBe(SETTLEMENT)
})

test("정산 열기를 보낸 뒤 호실의 정산을 다시 받는다", async () => {
  const opts = { opened: false }
  const server = fakeServer(opts)
  const records = createHttpRecords(server.request)
  await records.load(s)
  const made = await records.send(
    s,
    change(post(endpoints.openSettlement(ROOM))),
  )
  expect(made.id).toBe(SETTLEMENT)
  expect(server.calls.find((c) => c.method === "POST")).toMatchObject({
    path: `/api/rooms/${ROOM}/settlement`,
    body: undefined,
  })
  expect(roomOf(records).settlement?.id).toBe(SETTLEMENT)
})

test("상대가 먼저 열었으면(SETTLEMENT_EXISTS) 그 정산을 다시 받아 열고 안내한다", async () => {
  const exists = new ApiError({
    status: 409,
    code: "SETTLEMENT_EXISTS",
    detail: "이미 시작된 점검(정산)이 있어요. 그쪽으로 열게요",
  })
  const opts = { opened: false, fail: exists }
  const records = createHttpRecords(fakeServer(opts).request)
  await records.load(s)
  expect(roomOf(records).settlement).toBeNull()

  opts.opened = true
  await expect(
    records.send(s, change(post(endpoints.openSettlement(ROOM)))),
  ).rejects.toBe(exists)
  expect(roomOf(records).settlement?.id).toBe(SETTLEMENT)
})

test("정산을 아직 받지 못했으면 자리표 주소로 끝내기를 보내지 않는다", async () => {
  const server = fakeServer({ opened: false })
  const records = createHttpRecords(server.request)
  await records.load(s)
  await expect(
    records.send(
      s,
      change(
        post(endpoints.settlementAction(settlementIdOf(ROOM), "finish"), {}),
      ),
    ),
  ).rejects.toMatchObject({ code: "SETTLEMENT_NOT_LOADED" })
  expect(server.calls.some((c) => c.method === "POST")).toBe(false)
})

test("버튼은 서버가 준 것만 꺼내고, 읽기만 하는 화면이면 비운다", async () => {
  const records = createHttpRecords(
    fakeServer({
      opened: true,
      detail: settlement({ availableActions: ["finish"] }),
    }).request,
  )
  await records.load(s)
  const room = roomOf(records)
  expect(httpAvailableActions.settlement(room, "임차인")).toEqual(["finish"])
  expect(
    httpAvailableActions.settlement(room, "임차인", { readOnly: true }),
  ).toEqual([])
})

test("연결한 정산 API만 서버로 보낸다(보증금·항목·송금·초대는 연결 전)", () => {
  expect(isConnected("POST", endpoints.openSettlement(ROOM))).toBe(true)
  expect(isConnected("GET", endpoints.openSettlement(ROOM))).toBe(true)
  expect(isConnected("GET", endpoints.settlement(SETTLEMENT))).toBe(true)
  for (const a of [
    "finish",
    "finish/confirm",
    "finish/not-yet",
    "finish/alone",
  ] as const)
    expect(isConnected("POST", endpoints.settlementAction(SETTLEMENT, a))).toBe(
      true,
    )
  for (const a of [
    "deposit-proposals",
    "items",
    "transfers",
    "invite",
  ] as const)
    expect(isConnected("POST", endpoints.settlementAction(SETTLEMENT, a))).toBe(
      false,
    )
  expect(isConnected("POST", endpoints.transferRespond("t"))).toBe(false)
})
