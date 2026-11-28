import { describe, expect, test, vi } from "vitest"
import { ApiError } from "../src/api/errors"
import { endpoints } from "../src/api/endpoints"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRecords } from "../src/api/http-records"
import { post, roomSide } from "../src/api/records"
import {
  connectionOf,
  listAllRooms,
  roomFromDetail,
  roomFromSummary,
  seoulDate,
  type RoomDetailBody,
  type RoomSummaryBody,
} from "../src/api/http-rooms"
import { notConnected } from "../src/api/connected"
import {
  repairFromListItem,
  type RepairCaseListItemBody,
} from "../src/api/http-repair-view"
import { lastActivity } from "../src/lib/activity"
import { connectionText, isPreJoin, sideOf } from "../src/lib/workflows"
import type { PartyStatus } from "../src/data/demo"

// 호실 API(06 4-3) 서버 모양 → 화면 모델. 서버 값은 room.server에 그대로 남아요.
const summary = (patch: Partial<RoomSummaryBody> = {}): RoomSummaryBody => ({
  id: "r1",
  alias: "302호",
  region: {
    code: "3611010100",
    sido: "세종특별자치시",
    sigungu: null,
    dong: "반곡동",
  },
  status: "LIVING",
  mySide: "LANDLORD",
  counterpartStatus: "EMPTY",
  joinedAway: false,
  nextStep: "NONE",
  createdAt: "2026-11-25T01:02:03Z",
  ...patch,
})
const detail = (patch: Partial<RoomDetailBody> = {}): RoomDetailBody => {
  const s = summary()
  return {
    id: s.id,
    alias: s.alias,
    region: s.region,
    status: s.status,
    mySide: s.mySide,
    counterpartStatus: s.counterpartStatus,
    joinedAway: s.joinedAway,
    createdAt: s.createdAt,
    moveInDate: null,
    createdBySide: "LANDLORD",
    myStatus: "CONNECTED",
    asOf: null,
    availableActions: ["repair-cases"],
    ...patch,
  }
}

// 호실의 수리 건 목록(06 4-6). 호실 시험에서는 비어 있어요
const isRepairPage = (path: string) => /\/repair-cases(\?|$)/.test(path)
const emptyRepairs = {
  asOf: null,
  mySide: "LANDLORD",
  items: [],
  nextCursor: null,
}
// 호실 경과(06 4장 timeline). 호실 시험에서는 비어 있어요
const emptyTimeline = { mySide: "TENANT", asOf: null, items: [] }

describe("목록 한 줄 → 화면 모델", () => {
  test("주소·상태·내 쪽을 화면 값으로 바꾸고, 연결 전 호실은 내가 만든 호실이다", () => {
    const r = roomFromSummary(summary(), "me")
    expect(r).toMatchObject({
      id: "r1",
      name: "302호",
      address: "세종특별자치시 반곡동",
      since: "",
      status: "거주 중",
      connection: "연결 전",
      creator: "임대인",
      createdAt: "2026-11-25",
    })
    expect(sideOf(r, "me")).toBe("임대인")
    expect(r.server).toMatchObject({
      mySide: "LANDLORD",
      counterpartStatus: "EMPTY",
      nextStep: "NONE",
    })
  })

  test.each<[PartyStatus, string]>([
    ["EMPTY", "연결 전"],
    ["NUMBER_ONLY", "연결 전"],
    ["INVITED", "초대됨"],
    ["REJECTED", "거절됨"],
    ["CONNECTED", "연결됨"],
    ["SHARING_STOPPED", "연결됨"],
    ["WITHDRAWN", "연결됨"],
  ])("상대 자리 %s → 연결 상태 %s", (status, connection) => {
    expect(connectionOf(status)).toBe(connection)
  })

  test("공유를 그만뒀거나 탈퇴한 상대는 정상 연결과 구분해 보이고, 서버 값은 그대로 남는다", () => {
    const stopped = roomFromSummary(
      summary({ counterpartStatus: "SHARING_STOPPED" }),
      "me",
    )
    expect(stopped.server?.counterpartStatus).toBe("SHARING_STOPPED")
    expect(connectionText(stopped)).toBe("상대가 공유 그만둠")
    const left = roomFromSummary(
      summary({ counterpartStatus: "WITHDRAWN" }),
      "me",
    )
    expect(left.counterpartLeft).toBe(true)
    expect(connectionText(left)).toBe("상대 탈퇴")
    const normal = roomFromSummary(
      summary({ counterpartStatus: "CONNECTED" }),
      "me",
    )
    expect(connectionText(normal)).toBe("연결됨")
    // 연결된 호실은 목록만으로는 누가 만들었는지 몰라 상세를 받은 뒤 채워요
    expect(normal.creator).toBeUndefined()
  })

  test("합류 전 기록은 읽기만 하는 기록으로 본다", () => {
    expect(
      isPreJoin(roomFromSummary(summary({ joinedAway: true }), "me")),
    ).toBe(true)
  })
})

describe("상세 → 화면 모델", () => {
  test("입주일·만든 측·행동을 채우고, 목록에만 있는 nextStep은 지킨다", () => {
    const listed = roomFromSummary(
      summary({ counterpartStatus: "CONNECTED", nextStep: "SETTLE" }),
      "me",
    )
    const r = roomFromDetail(
      detail({
        counterpartStatus: "CONNECTED",
        moveInDate: "2026-03-01",
        createdBySide: "TENANT",
        myStatus: "SHARING_STOPPED",
        asOf: "2026-11-01T00:00:00Z",
      }),
      "me",
      listed,
    )
    expect(r).toMatchObject({ since: "2026-03-01", creator: "임차인" })
    expect(r.server).toMatchObject({
      nextStep: "SETTLE",
      myStatus: "SHARING_STOPPED",
      asOf: "2026-11-01T00:00:00Z",
      availableActions: ["repair-cases"],
    })
    expect(connectionText(r)).toBe("공유 그만둠")
  })

  test("목록을 다시 받아도 앞서 받은 상세 값(입주일·만든 측)은 이어서 쓴다", () => {
    const withDetail = roomFromDetail(
      detail({ moveInDate: "2026-03-01" }),
      "me",
    )
    const again = roomFromSummary(summary(), "me", withDetail)
    expect(again.since).toBe("2026-03-01")
    expect(again.server?.availableActions).toEqual(["repair-cases"])
  })
})

describe("HTTP 기록 API의 호실 받기", () => {
  test("nextCursor가 없을 때까지 이어 받고, 남의 호실(404)은 빼며, 알림이 연결 전이어도 호실은 받는다", async () => {
    const paths: string[] = []
    const request = (async (_m: string, path: string) => {
      paths.push(path)
      if (path === endpoints.roomPage(100))
        return { items: [summary({ id: "a" })], nextCursor: "c1" }
      if (path === endpoints.roomPage(100, "c1"))
        return { items: [summary({ id: "b" })], nextCursor: null }
      if (path === endpoints.room("a")) return detail({ id: "a" })
      if (isRepairPage(path)) return emptyRepairs
      if (path === endpoints.room("b"))
        throw new ApiError({ status: 404, code: "NOT_FOUND" })
      if (path === endpoints.notifications) throw notConnected()
      if (path === endpoints.invitations) return []
      throw new Error(`unexpected ${path}`)
    }) as HttpRequest
    const records = createHttpRecords(request)
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().rooms.map((r) => r.id)).toEqual(["a"])
    expect(records.getSnapshot().notices).toEqual([])
    expect(paths).toContain(endpoints.roomPage(100, "c1"))
  })

  test("호실에 들어가면 그 호실 상세만 다시 받는다", async () => {
    let actions = ["repair-cases"]
    const request = (async (_m: string, path: string) => {
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (path === endpoints.room("r1"))
        return detail({ availableActions: actions })
      if (isRepairPage(path)) return emptyRepairs
      return []
    }) as HttpRequest
    const records = createHttpRecords(request)
    await records.load({ accountId: "me" })
    actions = []
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().rooms[0].server?.availableActions).toEqual([])
  })

  // 목록 한 쪽과 상세를 정해 두는 가짜 서버. detailOf(id)가 던지면 그 오류가 나요
  const server = (
    detailOf: (id: string) => Promise<RoomDetailBody> | RoomDetailBody,
  ) =>
    (async (method: string, path: string) => {
      if (method === "POST") return { id: "r1" }
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (isRepairPage(path)) return emptyRepairs
      if (path.endsWith("/timeline")) return emptyTimeline
      if (path.startsWith(`${endpoints.rooms}/`))
        return detailOf(decodeURIComponent(path.split("/").pop()!))
      return []
    }) as HttpRequest

  test("상세가 404가 아닌 오류면 목록 값은 두고 오류를 알린다", async () => {
    const records = createHttpRecords(
      server(() => {
        throw new ApiError({ status: 500, code: "INTERNAL_ERROR" })
      }),
    )
    await expect(records.load({ accountId: "me" })).rejects.toMatchObject({
      status: 500,
    })
    expect(records.getSnapshot().rooms.map((r) => r.name)).toEqual(["302호"])
    await expect(
      records.loadRoom({ accountId: "me" }, "r1"),
    ).rejects.toMatchObject({ status: 500 })
  })

  test("호실에 들어갈 때 404면 목록에서 뺀다", async () => {
    let gone = false
    const records = createHttpRecords(
      server(() => {
        if (gone) throw new ApiError({ status: 404, code: "NOT_FOUND" })
        return detail()
      }),
    )
    await records.load({ accountId: "me" })
    gone = true
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().rooms).toEqual([])
  })

  test("load가 기다리는 사이 받은 더 새 상세는 load가 덮어쓰지 않는다", async () => {
    let release: (b: RoomDetailBody) => void = () => {}
    let call = 0
    const records = createHttpRecords(
      server(() => {
        call += 1
        if (call === 2) return new Promise<RoomDetailBody>((r) => (release = r))
        return detail({ availableActions: call === 1 ? [] : ["new"] })
      }),
    )
    await records.load({ accountId: "me" })
    const slow = records.load({ accountId: "me" })
    await vi.waitFor(() => expect(call).toBe(2))
    await records.loadRoom({ accountId: "me" }, "r1")
    release(detail({ availableActions: ["old"] }))
    await slow
    expect(records.getSnapshot().rooms[0].server?.availableActions).toEqual([
      "new",
    ])
  })

  test("보낸 뒤 다시 받기가 실패해도 보내기는 성공으로 돌려준다(다시 보내 두 번 만들지 않게)", async () => {
    const records = createHttpRecords(
      server(() => {
        throw new ApiError({ status: 500 })
      }),
    )
    await expect(
      records.send(
        { accountId: "me" },
        {
          request: post(endpoints.rooms, { side: "TENANT" }),
          role: "임차인",
          text: "새 호실 기록 만들기",
          update: () => {},
          options: { roomId: "x" },
        },
      ),
    ).resolves.toEqual({ id: "r1" })
  })

  test("만든 뒤 목록 다시 받기가 실패해도, 새 호실에 들어가면 상세로 받아 화면이 열린다(#241 리뷰)", async () => {
    let listFails = true
    const request = (async (method: string, path: string) => {
      if (method === "POST") return { id: "new" }
      if (path.startsWith(`${endpoints.rooms}?`)) {
        if (listFails) throw new ApiError({ status: 500 })
        return { items: [summary({ id: "new" })], nextCursor: null }
      }
      if (path === endpoints.room("new"))
        return detail({ id: "new", moveInDate: "2026-03-01" })
      if (isRepairPage(path)) return emptyRepairs
      return []
    }) as HttpRequest
    const records = createHttpRecords(request)
    const made = await records.send(
      { accountId: "me" },
      {
        request: post(endpoints.rooms, { side: "LANDLORD" }),
        role: "임대인",
        text: "새 호실 기록 만들기",
        update: () => {},
        options: { roomId: "x" },
      },
    )
    // 만들기는 성공으로 돌려줘 다시 보내지 않아요(호실이 두 개 생기지 않게)
    expect(made).toEqual({ id: "new" })
    expect(records.getSnapshot().rooms).toEqual([])
    // room-form이 새 ID로 이동하면 App이 loadRoom을 불러요
    await records.loadRoom({ accountId: "me" }, "new")
    expect(records.getSnapshot().rooms).toMatchObject([
      { id: "new", name: "302호", since: "2026-03-01", creator: "임대인" },
    ])
    // 다음 목록 받기에서 같은 호실이 하나로 이어져요
    listFails = false
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().rooms.map((r) => r.id)).toEqual(["new"])
  })

  test("목록에 없는 호실이 404면 넣지 않는다(찾을 수 없는 기록 그대로)", async () => {
    const records = createHttpRecords(
      server(() => {
        throw new ApiError({ status: 404, code: "NOT_FOUND" })
      }),
    )
    await records.loadRoom({ accountId: "me" }, "someone-else")
    expect(records.getSnapshot().rooms).toEqual([])
  })

  test("같은 커서가 다시 오면 끝없이 받지 않고 멈춘다", async () => {
    const request = (async () => ({
      items: [],
      nextCursor: "same",
    })) as unknown as HttpRequest
    await expect(listAllRooms(request)).rejects.toThrow()
  })
})

describe("호실의 수리 건 목록(06 4-6, #290)", () => {
  const item = (
    patch: Partial<RepairCaseListItemBody> = {},
  ): RepairCaseListItemBody => ({
    id: "c1",
    registeredBy: "TENANT",
    createdAt: "2026-11-20T01:00:00Z",
    content: { no: 2, partName: "천장", title: "천장 누수" },
    status: "CLOSE_WAIT",
    lastActionAt: "2026-11-24T03:00:00Z",
    closeRequestedBy: "LANDLORD",
    closedBy: null,
    defect: { ack: "ACK" },
    money: { status: "UNDECIDED" },
    waiting: [],
    ...patch,
  })

  test("목록 한 줄을 상세와 같은 규칙으로 화면 모델로 바꾼다", () => {
    expect(repairFromListItem(item(), "r1")).toMatchObject({
      id: "c1",
      roomId: "r1",
      title: "천장 누수",
      zone: "천장",
      status: "처리 중",
      ack: "확인됨",
      author: "임차인",
      date: "2026.11.20",
      version: 2,
      closeBy: "임대인",
      lastActionAt: "2026-11-24T03:00:00Z",
    })
    expect(
      repairFromListItem(
        item({ status: "DONE", closedBy: "TENANT", closeRequestedBy: null }),
        "r1",
      ),
    ).toMatchObject({ status: "처리 완료", soloClosed: "임차인" })
  })

  test("N일째는 서버의 lastActionAt부터 센다", () => {
    const r = repairFromListItem(item(), "r1")
    expect(lastActivity(r, [])).toBe(Date.parse("2026-11-24T03:00:00Z"))
  })

  test("호실마다 수리 건을 끝까지 받고, 호실에 들어가면 그 호실 것만 바꾼다", async () => {
    let title = "천장 누수"
    const paths: string[] = []
    const request = (async (_m: string, path: string) => {
      paths.push(path)
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (path === endpoints.room("r1")) return detail()
      if (path === endpoints.repairCasePage("r1", 100))
        return {
          asOf: null,
          mySide: "LANDLORD",
          items: [item({ content: { no: 1, partName: "천장", title } })],
          nextCursor: "c1",
        }
      if (path === endpoints.repairCasePage("r1", 100, "c1"))
        return {
          asOf: null,
          mySide: "LANDLORD",
          items: [item({ id: "c0" })],
          nextCursor: null,
        }
      return []
    }) as HttpRequest
    const records = createHttpRecords(request)
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().repairs.map((r) => r.id)).toEqual(["c1", "c0"])
    title = "천장 물샘"
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().repairs.map((r) => r.title)).toEqual([
      "천장 물샘",
      "천장 누수",
    ])
  })

  // 호실 r1(상세 + 수리 건 한 쪽)을 정해 두는 가짜 서버. listOf가 던지면 수리 건 목록만 실패해요
  const repairServer = (
    listOf: () => Promise<unknown> | unknown,
    detailOf: () => Promise<RoomDetailBody> | RoomDetailBody = () => detail(),
  ) =>
    (async (method: string, path: string) => {
      if (method === "POST") return {}
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (isRepairPage(path)) return listOf()
      if (path === endpoints.room("r1")) return detailOf()
      return []
    }) as HttpRequest
  const page = (
    items: RepairCaseListItemBody[],
    asOf: string | null = null,
  ) => ({
    asOf,
    mySide: "LANDLORD",
    items,
    nextCursor: null,
  })

  test("늦게 끝난 loadRoom은 더 늦게 시작한 loadRoom이 받은 값을 덮지 않는다(#293 리뷰)", async () => {
    const releases: ((v: unknown) => void)[] = []
    const records = createHttpRecords(
      repairServer(() => new Promise((r) => releases.push(r))),
    )
    const first = records.loadRoom({ accountId: "me" }, "r1")
    const second = records.loadRoom({ accountId: "me" }, "r1")
    await vi.waitFor(() => expect(releases).toHaveLength(2))
    releases[1](page([item({ status: "IN_PROGRESS", closeRequestedBy: null })]))
    await second
    releases[0](page([item({ status: "NOT_STARTED", closeRequestedBy: null })]))
    await first
    expect(records.getSnapshot().repairs.map((r) => r.status)).toEqual([
      "처리 중",
    ])
  })

  test("수리 건 목록만 실패하면 상세는 반영하고, 앞서 받은 수리 건을 두며 알리지 않는다(#293 리뷰)", async () => {
    let listFails = false
    let actions = ["repair-cases"]
    const records = createHttpRecords(
      repairServer(
        () => {
          if (listFails) throw new ApiError({ status: 500 })
          return page([item()])
        },
        () => detail({ availableActions: actions }),
      ),
    )
    await records.load({ accountId: "me" })
    listFails = true
    actions = []
    await expect(
      records.loadRoom({ accountId: "me" }, "r1"),
    ).resolves.toBeUndefined()
    await expect(records.load({ accountId: "me" })).resolves.toBeUndefined()
    const snap = records.getSnapshot()
    expect(snap.rooms[0].server?.availableActions).toEqual([])
    expect(snap.repairs.map((r) => r.id)).toEqual(["c1"])
  })

  test("수리 건 목록만 404면 호실을 빼지 않는다", async () => {
    const records = createHttpRecords(
      repairServer(() => {
        throw new ApiError({ status: 404, code: "NOT_FOUND" })
      }),
    )
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().rooms.map((r) => r.id)).toEqual(["r1"])
  })

  test("목록 기준 시각(asOf)은 호실 상세가 아니라 수리 건 목록 응답 값을 호실별로 둔다", async () => {
    let asOf: string | null = "2026-11-20T03:00:00Z"
    const records = createHttpRecords(
      repairServer(
        () => page([item()], asOf),
        () => detail({ asOf: "2026-11-01T00:00:00Z" }),
      ),
    )
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().repairListAsOf).toEqual({
      r1: "2026-11-20T03:00:00Z",
    })
    // 탈퇴한 상대 등으로 서버가 비워 보내면 "기준 없음"
    asOf = null
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().repairListAsOf).toEqual({ r1: null })
  })

  test("다시 받아 합쳐도 수리 건은 등록 시각 새것부터(같으면 ID) 순서를 지킨다", async () => {
    const records = createHttpRecords(
      repairServer(() =>
        page([
          item({ id: "c3", createdAt: "2026-11-22T00:00:00Z" }),
          item({ id: "c2", createdAt: "2026-11-20T00:00:00Z" }),
          item({ id: "c1", createdAt: "2026-11-20T00:00:00Z" }),
        ]),
      ),
    )
    await records.load({ accountId: "me" })
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().repairs.map((r) => r.id)).toEqual([
      "c3",
      "c2",
      "c1",
    ])
  })

  test("아는 호실의 행동을 보낸 뒤에는 그 호실만 다시 받는다", async () => {
    const paths: string[] = []
    const base = repairServer(() => page([item()]))
    const records = createHttpRecords((async (m: string, p: string) => {
      paths.push(`${m} ${p}`)
      return base(m, p)
    }) as HttpRequest)
    await records.load({ accountId: "me" })
    paths.length = 0
    await records.send(
      { accountId: "me" },
      {
        request: post("/api/rooms/r1/move-in-date", {}),
        role: "임대인",
        text: "입주일",
        update: () => {},
        options: { roomId: "r1" },
      },
    )
    expect(paths.some((p) => p.startsWith(`GET ${endpoints.rooms}?`))).toBe(
      false,
    )
    expect(paths).toContain(`GET ${endpoints.room("r1")}`)
  })
})

test("만든 시각(UTC)은 한국 날짜로 보인다", () => {
  expect(seoulDate("2026-11-25T16:00:00Z")).toBe("2026-11-26")
  expect(
    roomFromSummary(summary({ createdAt: "2026-11-25T16:00:00Z" }), "me")
      .createdAt,
  ).toBe("2026-11-26")
})

test("호실 쪽은 서버 값(TENANT·LANDLORD)으로 보낸다", () => {
  expect(roomSide("임차인")).toBe("TENANT")
  expect(roomSide("임대인")).toBe("LANDLORD")
})

describe("호실 경과(06 4장 timeline, #359)", () => {
  const notYet = {
    id: "a1",
    at: "2026-11-27T05:00:00Z",
    type: "NOT_YET",
    side: "TENANT",
    target: { kind: "REPAIR_CASE", id: "c1" },
    reason: "아직 물이 새요",
    payload: {},
    photos: [],
  }
  const server = (timeline: () => unknown) =>
    (async (_m: string, path: string) => {
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (isRepairPage(path)) return emptyRepairs
      if (path.endsWith("/timeline")) return timeline()
      if (path.startsWith(`${endpoints.rooms}/`)) return detail()
      return []
    }) as HttpRequest

  test("호실을 받을 때 경과도 받아, [아직 안 됐어요] 사유가 그 수리 건 줄에 보인다", async () => {
    const records = createHttpRecords(
      server(() => ({ ...emptyTimeline, items: [notYet] })),
    )
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().events).toEqual([
      {
        id: "a1",
        roomId: "r1",
        text: "아직 안 됐어요 · 아직 물이 새요",
        subject: "수리 건",
        by: "임차인",
        at: "2026-11-27T05:00:00Z",
        target: { kind: "repair", id: "c1" },
        ref: { kind: "REPAIR_CASE", id: "c1" },
      },
    ])
  })

  test("경과만 실패하면 호실은 반영하고 앞서 받은 경과를 둔다", async () => {
    let fail = false
    const records = createHttpRecords(
      server(() => {
        if (fail) throw new ApiError({ status: 500, code: "INTERNAL_ERROR" })
        return { ...emptyTimeline, items: [notYet] }
      }),
    )
    await records.load({ accountId: "me" })
    fail = true
    await records.loadRoom({ accountId: "me" }, "r1")
    expect(records.getSnapshot().events.map((e) => e.id)).toEqual(["a1"])
    expect(records.getSnapshot().rooms.map((r) => r.id)).toEqual(["r1"])
  })

  test("경과에 모양이 다른 줄이 있으면 경과만 앞서 받은 것을 두고, 호실 반영은 막지 않는다(#368 리뷰)", async () => {
    let broken = false
    let actions = ["repair-cases"]
    const records = createHttpRecords((async (_m: string, path: string) => {
      if (path.startsWith(`${endpoints.rooms}?`))
        return { items: [summary()], nextCursor: null }
      if (isRepairPage(path)) return emptyRepairs
      if (path.endsWith("/timeline"))
        return { ...emptyTimeline, items: broken ? [notYet, null] : [notYet] }
      if (path.startsWith(`${endpoints.rooms}/`))
        return detail({ availableActions: actions })
      return []
    }) as HttpRequest)
    await records.load({ accountId: "me" })
    broken = true
    actions = ["new"]
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().events.map((e) => e.id)).toEqual(["a1"])
    expect(records.getSnapshot().rooms[0].server?.availableActions).toEqual([
      "new",
    ])
  })

  test("다른 계정으로 받으면 앞 계정의 경과·호실을 비우고, 경과가 실패해도 앞 계정 경과를 보이지 않는다(#368 리뷰)", async () => {
    let fail = false
    const records = createHttpRecords(
      server(() => {
        if (fail) throw new ApiError({ status: 500, code: "INTERNAL_ERROR" })
        return { ...emptyTimeline, items: [notYet] }
      }),
    )
    await records.load({ accountId: "landlord" })
    expect(records.getSnapshot().events).toHaveLength(1)
    fail = true
    await records.load({ accountId: "tenant" })
    expect(records.getSnapshot().events).toEqual([])
    expect(records.getSnapshot().rooms.map((r) => r.id)).toEqual(["r1"])
  })

  test("계정이 바뀌는 사이 끝난 앞 계정의 받기는 반영하지 않는다", async () => {
    let release: () => void = () => {}
    let first = true
    const records = createHttpRecords(
      server(() => {
        if (!first) return { ...emptyTimeline, items: [] }
        first = false
        return new Promise(
          (r) => (release = () => r({ ...emptyTimeline, items: [notYet] })),
        )
      }),
    )
    const slow = records.load({ accountId: "landlord" })
    await vi.waitFor(() => expect(first).toBe(false))
    await records.load({ accountId: "tenant" })
    release()
    await slow
    expect(records.getSnapshot().events).toEqual([])
  })

  test("공유를 그만둔 뒤면 경과 기준 시각(asOf)을 호실별로 둔다", async () => {
    const records = createHttpRecords(
      server(() => ({
        ...emptyTimeline,
        asOf: "2026-11-20T03:00:00Z",
        items: [notYet],
      })),
    )
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().timelineAsOf).toEqual({
      r1: "2026-11-20T03:00:00Z",
    })
  })
})
