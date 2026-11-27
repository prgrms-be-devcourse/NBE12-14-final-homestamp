import { expect, test } from "vitest"
import { createHttpRecords } from "../src/api/http-records"
import {
  inspectionFromDetail,
  moveInInspectionId,
  type InspectionDetailBody,
  type InspectionItemBody,
} from "../src/api/http-inspection-view"
import { httpAvailableActions } from "../src/api/http-available-actions"
import { isConnected } from "../src/api/connected"
import { ApiError } from "../src/api/errors"
import type { HttpRequest } from "../src/api/http-client"
import { endpoints } from "../src/api/endpoints"
import {
  inspectionIdIn,
  inspectionIdOf,
  post,
  type RecordChange,
} from "../src/api/records"
import {
  confirmAll,
  confirmableItems,
  inspectionStatus,
} from "../src/lib/inspection-rules"
import { emptyState, type Room } from "../src/data/demo"

// 입주 점검 API 연결(06 4-7, #357): 서버 점검 → 화면 모델, 점검 ID 찾기, 요청 주소·모양
const s = { accountId: "dev-tenant" }
const ROOM = "302"
const INSPECTION = "0193a000-0000-7000-8000-000000000001"

const item = (over: Partial<InspectionItemBody> = {}): InspectionItemBody => ({
  id: "item-1",
  status: "PENDING",
  addedBy: "LANDLORD",
  addedAfterLock: false,
  addedAfterEnd: false,
  createdAt: "2026-11-20T01:00:00Z",
  version: {
    no: 2,
    roomPartId: "part-1",
    partName: "욕실 타일",
    description: "금이 가 있어요",
  },
  photos: [],
  nextRerequestAt: null,
  availableActions: ["respond"],
  ...over,
})
const inspection = (
  over: Partial<InspectionDetailBody> = {},
): InspectionDetailBody => ({
  id: INSPECTION,
  roomRecordId: ROOM,
  kind: "MOVE_IN",
  status: "REQUESTED",
  mySide: "TENANT",
  authorSide: "LANDLORD",
  late: false,
  lockedAt: null,
  lockReason: null,
  createdAt: "2026-11-20T00:00:00Z",
  asOf: null,
  items: [item()],
  counterpartCanRequestAt: "2026-11-27T00:00:00Z",
  availableActions: ["items", "confirm-all"],
  ...over,
})
const timeline = (...items: unknown[]) => ({
  mySide: "TENANT",
  asOf: null,
  items,
})
const started = (kind: "MOVE_IN" | "MOVE_OUT", id = INSPECTION) => ({
  id: `a-${kind}`,
  at: "2026-11-20T00:00:00Z",
  type: "START_INSPECTION",
  side: "LANDLORD",
  target: { kind: "INSPECTION", id },
  reason: null,
  payload: { kind },
  photos: [],
})
const roomDetail = (availableActions: string[]) => ({
  id: ROOM,
  alias: "302호",
  region: {
    code: "1144012400",
    sido: "서울특별시",
    sigungu: "마포구",
    dong: "연남동",
  },
  moveInDate: "2026-03-01",
  status: "LIVING",
  mySide: "TENANT",
  createdBySide: "TENANT",
  myStatus: "CONNECTED",
  counterpartStatus: "CONNECTED",
  joinedAway: false,
  asOf: null,
  createdAt: "2026-11-01T00:00:00Z",
  availableActions,
})

// 호실 하나 + 경과 + 점검 상세를 주는 가짜 서버. POST는 적어 두고, fail이면 그 오류로 거절해요
function fakeServer(opts: {
  started?: boolean
  roomActions?: string[]
  detail?: InspectionDetailBody
  fail?: ApiError
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
      return undefined
    }
    if (path.startsWith(`${endpoints.rooms}?`))
      return {
        items: [{ ...roomDetail([]), nextStep: "NONE" }],
        nextCursor: null,
      }
    if (path === endpoints.room(ROOM))
      return roomDetail(opts.roomActions ?? ["repair-cases"])
    if (path.startsWith(endpoints.repairCases(ROOM)))
      return { asOf: null, mySide: "TENANT", items: [], nextCursor: null }
    if (path === endpoints.timeline(ROOM))
      return opts.started ? timeline(started("MOVE_IN")) : timeline()
    if (path === endpoints.inspection(INSPECTION))
      return opts.detail ?? inspection()
    if (path === endpoints.notifications) return []
    throw new Error(`모르는 주소: ${path}`)
  }) as HttpRequest
  return { calls, request }
}
const change = (request: RecordChange["request"]): RecordChange => ({
  request,
  role: "임차인",
  text: "점검 · 시험",
  update: () => {},
  options: { roomId: ROOM },
})
const roomOf = (records: ReturnType<typeof createHttpRecords>) =>
  records.getSnapshot().rooms.find((r) => r.id === ROOM) as Room

test("서버 점검을 화면 모델로 바꾼다: 상태·작성 측·항목 판 번호·응답·버튼", () => {
  const { session, items } = inspectionFromDetail(
    inspection({
      items: [
        item(),
        item({
          id: "item-2",
          status: "NEED_MORE",
          addedBy: "TENANT",
          nextRerequestAt: "2026-11-30T00:00:00Z",
          availableActions: ["supplement", "corrections"],
        }),
      ],
    }),
  )

  expect(session).toMatchObject({
    author: "임대인",
    status: "확인 요청",
    noDefects: false,
    server: { id: INSPECTION, availableActions: ["items", "confirm-all"] },
  })
  expect(items[0]).toMatchObject({
    id: "item-1",
    roomId: ROOM,
    kind: "in",
    // 서버 항목에는 제목이 없어 부위 이름을 써요
    title: "욕실 타일",
    description: "금이 가 있어요",
    ack: "확인 대기",
    author: "임대인",
    publication: "requested",
    version: 2,
    server: { availableActions: ["respond"], addedAfterLock: false },
  })
  // 화면에 버튼이 없는 행동 이름은 빼요
  expect(items[1].server?.availableActions).toEqual(["supplement"])
  expect(items[1].ack).toBe("보충 요청")
  // 다음 보충 가능 시각 7일 전 = 마지막 보충 시각(lib/reask.ts가 세는 기준)
  expect(items[1].reaskedAt).toBe("2026-11-23T00:00:00.000Z")
})

test("작성 중에는 기준 측 항목만 초안이고, 상대가 더한 항목은 기준 측에게 보여 바로 답한다(#286)", () => {
  // 상대(세입자)가 보는 작성 중 점검: 자기가 더한 항목만 와요
  const counterpart = inspectionFromDetail(
    inspection({
      status: "DRAFT",
      items: [item({ id: "mine", addedBy: "TENANT", availableActions: [] })],
      availableActions: ["items"],
    }),
  )
  // 기준 측(집주인)이 보는 같은 점검: 자기 항목은 초안, 상대 항목은 보이는 기록
  const author = inspectionFromDetail(
    inspection({
      status: "DRAFT",
      mySide: "LANDLORD",
      items: [
        item({ id: "base", addedBy: "LANDLORD", availableActions: [] }),
        item({
          id: "theirs",
          addedBy: "TENANT",
          availableActions: ["respond"],
        }),
      ],
      availableActions: ["items", "request-confirm"],
    }),
  )

  expect(counterpart.session.status).toBe("작성 중")
  expect(counterpart.items.map((i) => [i.id, i.publication])).toEqual([
    ["mine", "requested"],
  ])
  expect(author.items.map((i) => [i.id, i.publication])).toEqual([
    ["base", "draft"],
    ["theirs", "requested"],
  ])
  expect(confirmableItems(author.items, "임대인").map((i) => i.id)).toEqual([
    "theirs",
  ])
})

test("하자 기록 없음: 확인 요청 뒤 기준 측 항목이 없을 때만(누가 봐도 같다)", () => {
  // 상대가 더한 항목만 있음: 확인하는 쪽에도 기준 측에도 하자 기록 없음
  const onlyCounterpart = inspectionFromDetail(
    inspection({ items: [item({ addedBy: "TENANT" })] }),
  )
  // 기준 측이 자기 항목이 있는 점검을 봄: 하자 기록 없음이 아니에요(#357 리뷰)
  const authorView = inspectionFromDetail(
    inspection({ mySide: "LANDLORD", items: [item({ addedBy: "LANDLORD" })] }),
  )
  const draft = inspectionFromDetail(inspection({ status: "DRAFT", items: [] }))

  expect(onlyCounterpart.session.noDefects).toBe(true)
  expect(authorView.session.noDefects).toBe(false)
  expect(draft.session.noDefects).toBe(false)
})

test("남은 항목 모두 확인 대상은 서버의 미응답(확인 기다림·상대 미확인) 중 응답 버튼이 있는 항목이다", () => {
  const { items } = inspectionFromDetail(
    inspection({
      items: [
        item({ id: "pending" }),
        item({ id: "unconfirmed", status: "UNCONFIRMED" }),
        item({ id: "answered", status: "NEED_MORE" }),
        item({ id: "no-button", availableActions: [] }),
      ],
    }),
  )
  const ids = confirmableItems(items, "임차인").map((i) => i.id)

  expect(ids).toEqual(["pending", "unconfirmed"])
  // 확인 창이 미리 돌려 보는 규칙도 같은 대상을 받아요
  const db = { ...emptyState(), inspections: structuredClone(items) }
  expect(() =>
    confirmAll(db, "임차인", ids, { pending: 2, unconfirmed: 2 }),
  ).not.toThrow()
})

test("잠금은 서버 상태 그대로다: 모든 항목에 답이 붙어도 화면이 잠그지 않는다(#326)", () => {
  const answered = inspectionFromDetail(
    inspection({ items: [item({ status: "ACK" })] }),
  )
  const locked = inspectionFromDetail(
    inspection({
      status: "LOCKED",
      lockedAt: "2026-11-25T00:00:00Z",
      lockReason: "ALL_RESOLVED",
    }),
  )
  const room = (session: typeof answered.session) =>
    ({ moveIn: session }) as Room

  expect(inspectionStatus(room(answered.session), answered.items, "in")).toBe(
    "확인 요청",
  )
  expect(inspectionStatus(room(locked.session), locked.items, "in")).toBe(
    "잠김",
  )
  expect(locked.session.server).toMatchObject({
    lockedAt: "2026-11-25T00:00:00Z",
    lockReason: "ALL_RESOLVED",
  })
})

test("경과의 입주 점검 시작 줄에서 점검 ID를 찾는다(퇴거 점검은 건너뜀)", () => {
  expect(
    moveInInspectionId(
      timeline(started("MOVE_OUT", "out-id"), started("MOVE_IN")),
    ),
  ).toBe(INSPECTION)
  expect(moveInInspectionId(timeline(started("MOVE_OUT", "out-id")))).toBe(null)
})

test("호실을 받을 때 입주 점검도 받고, 버튼은 서버가 준 것만 꺼낸다", async () => {
  const { request } = fakeServer({ started: true })
  const records = createHttpRecords(request)

  await records.load(s)

  const room = roomOf(records)
  expect(room.moveIn?.server?.id).toBe(INSPECTION)
  expect(records.getSnapshot().inspections.map((i) => i.id)).toEqual(["item-1"])
  const db = records.getSnapshot()
  expect(httpAvailableActions.inspection(db, room, "in", "임차인")).toEqual([
    "items",
    "confirm-all",
  ])
  expect(
    httpAvailableActions.inspectionItem(db.inspections[0], "임차인"),
  ).toEqual(["respond"])
  // 링크로 보는 중 등은 버튼이 없어요
  expect(
    httpAvailableActions.inspection(db, room, "in", "임차인", {
      readOnly: true,
    }),
  ).toEqual([])
  // 퇴거 점검은 아직 연결 전이에요
  expect(httpAvailableActions.inspection(db, room, "out", "임차인")).toEqual([])
})

test("점검이 없으면 호실 상세의 inspections가 시작 버튼이다(입주 점검 시작만, 06 4-3)", async () => {
  const shown = createHttpRecords(
    fakeServer({ roomActions: ["repair-cases", "inspections"] }).request,
  )
  const hidden = createHttpRecords(
    fakeServer({ roomActions: ["repair-cases"] }).request,
  )
  await shown.load(s)
  await hidden.load(s)

  expect(
    httpAvailableActions.inspection(
      shown.getSnapshot(),
      roomOf(shown),
      "in",
      "임차인",
    ),
  ).toEqual(["start"])
  expect(
    httpAvailableActions.inspection(
      hidden.getSnapshot(),
      roomOf(hidden),
      "in",
      "임차인",
    ),
  ).toEqual([])
})

test("서버에서 받은 점검은 서버 점검 ID로 주소를 만든다", async () => {
  const { calls, request } = fakeServer({ started: true })
  const records = createHttpRecords(request)
  await records.load(s)

  const id = inspectionIdIn(roomOf(records), "in")
  expect(id).toBe(INSPECTION)
  await records.send(
    s,
    change(
      post(endpoints.inspectionAction(id, "confirm-all"), {
        items: [{ id: "item-1", seenVersionNo: 2 }],
      }),
    ),
  )

  expect(calls.filter((c) => c.method === "POST")).toEqual([
    {
      method: "POST",
      path: `/api/inspections/${INSPECTION}/confirm-all`,
      body: { items: [{ id: "item-1", seenVersionNo: 2 }] },
    },
  ])
})

test("점검을 아직 받지 못했으면 자리표 주소로 보내지 않는다", async () => {
  const { calls, request } = fakeServer({})
  const records = createHttpRecords(request)
  await records.load(s)

  await expect(
    records.send(
      s,
      change(
        post(
          endpoints.inspectionAction(
            inspectionIdOf(ROOM, "in"),
            "request-confirm",
          ),
        ),
      ),
    ),
  ).rejects.toMatchObject({ code: "INSPECTION_NOT_LOADED" })
  // 퇴거 점검은 아직 받지 않아 "연결 전"으로 안내해요
  await expect(
    records.send(
      s,
      change(
        post(
          endpoints.inspectionAction(
            inspectionIdOf(ROOM, "out"),
            "request-confirm",
          ),
        ),
      ),
    ),
  ).rejects.toMatchObject({ code: "NOT_CONNECTED" })
  expect(calls.some((c) => c.method === "POST")).toBe(false)
})

test("상대가 먼저 시작했으면(INSPECTION_EXISTS) 그 점검을 다시 받아 열고 안내한다", async () => {
  const exists = new ApiError({
    status: 409,
    code: "INSPECTION_EXISTS",
    detail: "이미 시작된 점검(정산)이 있어요. 그쪽으로 열게요",
  })
  const opts = { fail: exists, started: false }
  const records = createHttpRecords(fakeServer(opts).request)
  await records.load(s)
  expect(roomOf(records).moveIn).toBeUndefined()

  // 화면을 연 사이 상대가 먼저 시작했어요
  opts.started = true
  await expect(
    records.send(
      s,
      change(post(endpoints.inspections(ROOM), { kind: "MOVE_IN" })),
    ),
  ).rejects.toBe(exists)
  expect(roomOf(records).moveIn?.server?.id).toBe(INSPECTION)
})

test("연결한 점검 API만 서버로 보낸다(항목 더하기·부위 사진·퇴거 마침은 연결 전)", () => {
  expect(isConnected("GET", endpoints.timeline(ROOM))).toBe(true)
  expect(isConnected("GET", endpoints.inspection(INSPECTION))).toBe(true)
  expect(isConnected("POST", endpoints.inspections(ROOM))).toBe(true)
  for (const a of ["request-confirm", "confirm-all"] as const)
    expect(isConnected("POST", endpoints.inspectionAction(INSPECTION, a))).toBe(
      true,
    )
  for (const a of ["respond", "supplement"] as const)
    expect(isConnected("POST", endpoints.inspectionItemAction("i", a))).toBe(
      true,
    )
  for (const a of ["items", "part-photos", "finish"] as const)
    expect(isConnected("POST", endpoints.inspectionAction(INSPECTION, a))).toBe(
      false,
    )
})
