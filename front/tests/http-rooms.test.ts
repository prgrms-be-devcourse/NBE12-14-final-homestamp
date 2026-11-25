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
      if (path === endpoints.room("b"))
        throw new ApiError({ status: 404, code: "NOT_FOUND" })
      if (path === endpoints.notifications) throw notConnected()
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

  test("같은 커서가 다시 오면 끝없이 받지 않고 멈춘다", async () => {
    const request = (async () => ({
      items: [],
      nextCursor: "same",
    })) as unknown as HttpRequest
    await expect(listAllRooms(request)).rejects.toThrow()
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
