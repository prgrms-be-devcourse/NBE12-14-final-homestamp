import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { roomFromDetail, type RoomDetailBody } from "../src/api/http-rooms"
import type { Role, RoomEndRequest } from "../src/data/demo"
import { RoomEndCard } from "../src/features/room/room-end-card"
import {
  dayCount,
  moveOutEndsNow,
  roomEndView,
  timeLeft,
} from "../src/lib/room-end"
import { ApiError } from "../src/api/errors"
import { endpoints } from "../src/api/endpoints"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRecords } from "../src/api/http-records"
import { post } from "../src/api/records"

// 거주 종료 카드(06 S-06 표, ROOM-9, #445). 버튼은 서버 availableActions로만 그리고(06 4-0),
// 서버는 요청 문자·기다림 시작·미확인 뒤 적용·양쪽 안내 전에는 버튼을 주지 않아요(#421 D 리뷰).
const body = (patch: Partial<RoomDetailBody> = {}): RoomDetailBody => ({
  id: "r1",
  alias: "302호",
  region: {
    code: "3611010100",
    sido: "세종특별자치시",
    sigungu: null,
    dong: "반곡동",
  },
  moveInDate: null,
  status: "LIVING",
  mySide: "TENANT",
  createdBySide: "LANDLORD",
  myStatus: "CONNECTED",
  counterpartStatus: "CONNECTED",
  joinedAway: false,
  asOf: null,
  createdAt: "2026-11-01T00:00:00Z",
  availableActions: ["repair-cases"],
  endRequest: null,
  endedAt: null,
  endSoloReason: null,
  ...patch,
})
const request = (patch: Partial<RoomEndRequest> = {}): RoomEndRequest => ({
  status: "WAITING",
  openedAt: "2026-11-27T03:00:00Z",
  dueAt: null,
  rejectReason: null,
  ...patch,
})
const NOW = new Date("2026-11-28T03:00:00Z")

function card(
  b: RoomDetailBody,
  role: Role,
  handlers: Partial<{
    onMoveOut: () => void
    onConfirm: () => void
    onReject: (r: string) => void
  }> = {},
) {
  const room = roomFromDetail(b, "me")
  const has = (a: string) => room.server!.availableActions!.includes(a)
  return render(
    <RoomEndCard
      view={roomEndView(room, role)}
      role={role}
      canMoveOut={has("move-out")}
      canConfirm={has("move-out-confirm")}
      canReject={has("move-out-reject")}
      now={NOW}
      onMoveOut={handlers.onMoveOut ?? (() => {})}
      onConfirm={handlers.onConfirm ?? (() => {})}
      onReject={handlers.onReject ?? (() => {})}
    />,
  )
}

describe("호실 상세 → 화면 모델", () => {
  test("서버 이름 confirm·reject를 화면 이름으로 바꾸고 거주 종료 칸을 담는다", () => {
    const room = roomFromDetail(
      body({
        availableActions: ["repair-cases", "move-out", "confirm", "reject"],
        endRequest: request(),
      }),
      "me",
    )
    expect(room.server?.availableActions).toEqual([
      "repair-cases",
      "move-out",
      "move-out-confirm",
      "move-out-reject",
    ])
    expect(room.server?.endRequest?.status).toBe("WAITING")
    expect(room.endRequested).toBe(true)
  })

  test("답한 요청은 열린 요청이 아니다", () => {
    const room = roomFromDetail(
      body({ endRequest: request({ status: "ANSWERED" }) }),
      "me",
    )
    expect(room.endRequested).toBe(false)
  })
})

describe("거주 종료 카드", () => {
  test("설정이 꺼져 버튼이 없으면 거주 중 호실에는 카드가 없다", () => {
    const { container } = card(body(), "임차인")
    expect(container.innerHTML).toBe("")
  })

  test("세입자: 집주인이 남긴 날짜를 보이고, 기한이 없으면 남은 시간을 보이지 않는다(NOTI-4b)", () => {
    card(
      body({
        availableActions: ["move-out", "confirm", "reject"],
        endRequest: request(),
      }),
      "임차인",
    )
    expect(
      screen.getByText("집주인이 11월 27일에 이사했다고 남겼어요"),
    ).toBeTruthy()
    expect(screen.queryByText(/남음/)).toBeNull()
    expect(screen.getByRole("button", { name: "맞아요" })).toBeTruthy()
    expect(
      screen.getByRole("button", { name: "아직 살고 있어요" }),
    ).toBeTruthy()
  })

  test("세입자: 기한이 있으면 남은 시간을 함께 보인다", () => {
    card(
      body({
        availableActions: ["confirm", "reject"],
        endRequest: request({ dueAt: "2026-11-30T03:00:00Z" }),
      }),
      "임차인",
    )
    expect(screen.getByText(/2일 0시간 남음/)).toBeTruthy()
  })

  test("공유를 그만둔 세입자도 요청을 보고 답할 수 있다(03 B 예외 둘)", () => {
    const onConfirm = vi.fn()
    card(
      body({
        myStatus: "SHARING_STOPPED",
        asOf: "2026-11-20T00:00:00Z",
        availableActions: ["move-out", "confirm", "reject"],
        endRequest: request(),
      }),
      "임차인",
      { onConfirm },
    )
    fireEvent.click(screen.getByRole("button", { name: "맞아요" }))
    expect(onConfirm).toHaveBeenCalled()
  })

  test("아직 살고 있어요는 사유가 있어야 보낸다", async () => {
    const onReject = vi.fn()
    card(
      body({ availableActions: ["reject"], endRequest: request() }),
      "임차인",
      { onReject },
    )
    fireEvent.click(screen.getByRole("button", { name: "아직 살고 있어요" }))
    fireEvent.change(screen.getByLabelText("사유 · 필수"), {
      target: { value: "다음 달 15일에 이사해요" },
    })
    fireEvent.click(screen.getByRole("button", { name: "보내기" }))
    await vi.waitFor(() =>
      expect(onReject).toHaveBeenCalledWith("다음 달 15일에 이사해요"),
    )
  })

  test("집주인: 요청을 보냈으면 N일째 기다림만 보이고 버튼은 없다", () => {
    card(body({ mySide: "LANDLORD", endRequest: request() }), "임대인")
    expect(screen.getByText("세입자의 답을 기다려요 · 2일째")).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  test("세입자가 아직 살고 있다고 답하면 사유가 양쪽에 보인다", () => {
    for (const [side, role] of [
      ["LANDLORD", "임대인"],
      ["TENANT", "임차인"],
    ] as const) {
      const { unmount } = card(
        body({
          mySide: side,
          endRequest: request({
            status: "ANSWERED",
            rejectReason: "다음 달 15일에 이사해요",
          }),
        }),
        role,
      )
      expect(
        screen.getByText(
          "세입자가 아직 살고 있다고 답했어요 · 다음 달 15일에 이사해요",
        ),
      ).toBeTruthy()
      unmount()
    }
  })

  test("거주 종료됨: 날짜와 혼자 남긴 이유를 보이고 버튼은 없다", () => {
    card(
      body({
        status: "ENDED",
        mySide: "LANDLORD",
        endedAt: "2026-11-28T01:00:00Z",
        endSoloReason: "NO_COUNTERPART",
        availableActions: [],
      }),
      "임대인",
    )
    expect(
      screen.getByText("11월 28일 거주 종료 · 세입자 없이 남긴 거주 종료"),
    ).toBeTruthy()
    expect(screen.queryByRole("button")).toBeNull()
  })

  test("버튼 문구는 세입자 이사했어요, 집주인 이사함", () => {
    const onMoveOut = vi.fn()
    card(body({ availableActions: ["move-out"] }), "임차인", { onMoveOut })
    fireEvent.click(screen.getByRole("button", { name: "이사했어요" }))
    expect(onMoveOut).toHaveBeenCalled()
  })
})

describe("시간 문구", () => {
  test("연 날을 1일째로 센다(한국 날짜)", () => {
    expect(dayCount("2026-11-28T00:30:00Z", NOW)).toBe(1)
    expect(dayCount("2026-11-26T03:00:00Z", NOW)).toBe(3)
  })
  test("기한이 지났으면 남은 시간이 없다", () => {
    expect(timeLeft("2026-11-28T02:00:00Z", NOW)).toBeNull()
    expect(timeLeft("2026-11-28T05:00:00Z", NOW)).toBe("2시간 남음")
  })
})

describe("연결 목록(connected.ts)", () => {
  test("거주 종료 세 주소만 서버로 보낸다", async () => {
    const { isConnected } = await import("../src/api/connected")
    expect(isConnected("POST", "/api/rooms/r1/move-out")).toBe(true)
    expect(isConnected("POST", "/api/rooms/r1/move-out/confirm")).toBe(true)
    expect(isConnected("POST", "/api/rooms/r1/move-out/reject")).toBe(true)
    expect(isConnected("POST", "/api/rooms/r1/move-out/alone")).toBe(false)
  })
})

describe("바로 거주 종료 판단(확인 창)", () => {
  test("세입자는 늘 바로, 집주인은 세입자가 답할 수 있는 자리가 아니면 바로", () => {
    const of = (counterpartStatus: RoomDetailBody["counterpartStatus"]) =>
      roomFromDetail(body({ mySide: "LANDLORD", counterpartStatus }), "me")
    expect(moveOutEndsNow(of("CONNECTED"), "임차인")).toBe(true)
    expect(moveOutEndsNow(of("CONNECTED"), "임대인")).toBe(false)
    expect(moveOutEndsNow(of("SHARING_STOPPED"), "임대인")).toBe(false)
    // 서버가 이 자리들에서 move-out을 주면 그것은 바로 거주 종료예요(#428·초대 거절 합의 뒤에도 그대로)
    for (const s of [
      "EMPTY",
      "NUMBER_ONLY",
      "INVITED",
      "REJECTED",
      "WITHDRAWN",
    ] as const)
      expect(moveOutEndsNow(of(s), "임대인")).toBe(true)
  })
})

describe("정산 완료 뒤", () => {
  test("거주 종료 날짜가 그대로 보인다(03 B)", () => {
    const room = roomFromDetail(
      body({
        status: "SETTLED",
        endedAt: "2026-11-28T01:00:00Z",
        availableActions: [],
      }),
      "me",
    )
    expect(roomEndView(room, "임차인")).toEqual({
      kind: "ended",
      endedAt: "2026-11-28T01:00:00Z",
      soloReason: null,
    })
  })
})

describe("INVALID_STATE면 호실을 다시 받는다", () => {
  test("그사이 상대가 먼저 답해 거절되면 지금 버튼으로 다시 그린다", async () => {
    let actions = ["move-out", "confirm", "reject"]
    const request = (async (method: string, path: string) => {
      if (method === "POST")
        throw new ApiError({ status: 409, code: "INVALID_STATE" })
      if (path.startsWith(`${endpoints.rooms}?`))
        return {
          items: [
            {
              id: "r1",
              alias: "302호",
              region: body().region,
              status: "LIVING",
              mySide: "TENANT",
              counterpartStatus: "CONNECTED",
              joinedAway: false,
              nextStep: "NONE",
              createdAt: "2026-11-01T00:00:00Z",
            },
          ],
          nextCursor: null,
        }
      if (path === endpoints.room("r1"))
        return body({ availableActions: actions })
      if (path.includes("/repair-cases"))
        return { asOf: null, mySide: "TENANT", items: [], nextCursor: null }
      if (path.endsWith("/timeline")) return { items: [], nextCursor: null }
      return []
    }) as HttpRequest
    const records = createHttpRecords(request)
    await records.load({ accountId: "me" })
    await records.loadRoom({ accountId: "me" }, "r1")
    actions = ["repair-cases"]
    await expect(
      records.send(
        { accountId: "me" },
        {
          request: post(endpoints.moveOutAnswer("r1", "confirm")),
          role: "임차인",
          text: "거주 종료 요청에 맞아요 · 거주 종료",
          update: () => {},
          options: { roomId: "r1" },
        },
      ),
    ).rejects.toMatchObject({ code: "INVALID_STATE" })
    expect(records.getSnapshot().rooms[0].server?.availableActions).toEqual([
      "repair-cases",
    ])
  })
})
