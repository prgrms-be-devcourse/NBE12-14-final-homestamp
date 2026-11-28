import type { ReactNode } from "react"
import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { Dialog, DialogContent } from "../src/components/ui/dialog"
import { createDemo, type Room } from "../src/data/demo"
import type { Account } from "../src/api/auth"
import { isConnected } from "../src/api/connected"
import { endpoints } from "../src/api/endpoints"
import { ApiError } from "../src/api/errors"
import type { HttpRequest } from "../src/api/http-client"
import {
  roomFromInvitation,
  SHARE_CONSENT_VERSION,
  withAccountPhone,
  type InvitationSummaryBody,
} from "../src/api/http-invitations"
import { createHttpRecords } from "../src/api/http-records"
import { roomFromDetail, type RoomDetailBody } from "../src/api/http-rooms"
import { post, type ApiRequest } from "../src/api/records"
import { ConnectForm } from "../src/features/room/connect-form"
import { InviteForm } from "../src/features/room/invite-form"
import { ReceivedInvitations } from "../src/features/room/received-invitations"
import { ConnectionPage } from "../src/pages/connection-page"
import { canReceiveInvitation, inviteeSide } from "../src/lib/workflows"

// 상대 초대·받은 초대·연결·거절을 서버 API(06 4-3, ROOM-4·5)에 맞춘 것
const inDialog = (ui: ReactNode) =>
  render(
    <Dialog open>
      <DialogContent>{ui}</DialogContent>
    </Dialog>,
  )
const submitButton = (name: RegExp) =>
  screen.getByRole("button", { name }) as HTMLButtonElement
const account: Account = {
  id: "me",
  name: "나",
  email: "me@example.com",
  phone: "01011112222",
} as Account
const invitation = (
  patch: Partial<InvitationSummaryBody> = {},
): InvitationSummaryBody => ({
  partyId: "party-1",
  side: "TENANT",
  region: {
    code: "3611010100",
    sido: "세종특별자치시",
    sigungu: null,
    dong: "반곡동",
  },
  inviterName: "정*윤",
  ...patch,
})
const detail = (patch: Partial<RoomDetailBody> = {}): RoomDetailBody => ({
  id: "r1",
  alias: "302호",
  region: invitation().region,
  moveInDate: null,
  status: "LIVING",
  mySide: "LANDLORD",
  createdBySide: "LANDLORD",
  myStatus: "CONNECTED",
  counterpartStatus: "NUMBER_ONLY",
  joinedAway: false,
  asOf: null,
  createdAt: "2026-11-25T01:02:03Z",
  availableActions: [],
  counterpartMaskedPhone: "010-****-5678",
  ...patch,
})
const serverRoom = (patch: Partial<RoomDetailBody> = {}) =>
  roomFromDetail(detail(patch), "me")

describe("연결한 API", () => {
  test("받은 초대·연결·거절은 서버로 보내고, 상대 초대는 #370 활성화 조건 전이라 보내지 않는다", () => {
    expect(isConnected("GET", endpoints.invitations)).toBe(true)
    expect(isConnected("POST", endpoints.connect("p1"))).toBe(true)
    expect(isConnected("POST", endpoints.rejectInvitation("p1"))).toBe(true)
    expect(isConnected("POST", endpoints.invite("r1"))).toBe(false)
    expect(isConnected("POST", endpoints.counterpartPhone("r1"))).toBe(false)
  })
})

describe("받은 초대 → 호실 자리표", () => {
  test("자리 ID로 연결·거절하고, 내 인증 번호를 넣어야 받은 초대로 보인다", () => {
    const room = roomFromInvitation(invitation())
    expect(room).toMatchObject({
      id: "party-1",
      name: "정*윤님의 호실",
      address: "세종특별자치시 반곡동",
      connection: "초대됨",
      creator: "임대인",
      invitation: { partyId: "party-1", inviterName: "정*윤" },
    })
    expect(inviteeSide(room)).toBe("임차인")
    expect(canReceiveInvitation(room, "임차인", account.phone)).toBe(false)
    const [mine] = withAccountPhone([room], account.phone)
    expect(canReceiveInvitation(mine, "임차인", account.phone)).toBe(true)
  })

  test("초대한 사람이 탈퇴했으면 이름 없이 보인다", () => {
    expect(roomFromInvitation(invitation({ inviterName: null })).name).toBe(
      "초대받은 호실",
    )
  })

  test("목록에는 주소와 가린 이름만 보이고 초대 날짜 줄은 없다", () => {
    const rooms = withAccountPhone(
      [roomFromInvitation(invitation())],
      account.phone,
    )
    render(
      <ReceivedInvitations
        rooms={rooms}
        accountPhone={account.phone}
        onOpen={() => {}}
      />,
    )
    expect(screen.getByText("정*윤님의 호실")).toBeTruthy()
    expect(screen.getByText("세종특별자치시 반곡동")).toBeTruthy()
    expect(screen.queryByText(/초대 날짜/)).toBeNull()
  })
})

describe("HTTP 기록 API의 받은 초대", () => {
  const server = (invitations: () => unknown) =>
    (async (method: string, path: string) => {
      if (path.startsWith("/api/rooms?")) return { items: [], nextCursor: null }
      if (path === endpoints.notifications) return []
      if (path === endpoints.invitations) return invitations()
      if (method === "POST" && path === endpoints.connect("party-1"))
        return {
          roomRecordId: "room-9",
          partyId: "party-1",
          status: "CONNECTED",
        }
      throw new Error(`unexpected ${path}`)
    }) as HttpRequest

  test("호실과 함께 받은 초대를 받아 둔다", async () => {
    const records = createHttpRecords(server(() => [invitation()]))
    await records.load({ accountId: "me" })
    expect(records.getSnapshot().invitations?.map((r) => r.id)).toEqual([
      "party-1",
    ])
  })

  test("받은 초대만 실패하면 앞서 받은 초대를 두고 오류를 알린다", async () => {
    let fail = false
    const records = createHttpRecords(
      server(() => {
        if (fail) throw new ApiError({ status: 500, code: "INTERNAL_ERROR" })
        return [invitation()]
      }),
    )
    await records.load({ accountId: "me" })
    fail = true
    await expect(records.load({ accountId: "me" })).rejects.toMatchObject({
      status: 500,
    })
    expect(records.getSnapshot().invitations?.map((r) => r.id)).toEqual([
      "party-1",
    ])
  })

  test("연결하면 연결 응답의 호실 기록 ID를 돌려준다", async () => {
    const records = createHttpRecords(server(() => []))
    const made = await records.send(
      { accountId: "me" },
      {
        request: post(endpoints.connect("party-1"), {
          consentVersion: SHARE_CONSENT_VERSION,
        }),
        role: "임차인",
        text: "연결",
        update: () => {},
        options: { roomId: "party-1", connecting: true },
      },
    )
    expect(made).toEqual({ id: "room-9" })
  })

  test("더는 받을 수 없는 초대로 거절되면 받은 초대를 다시 받아 목록에서 뺀다", async () => {
    let listed = [invitation()]
    const base = server(() => listed)
    const request = (async (method: string, path: string, opts?: unknown) => {
      if (method === "POST" && path === endpoints.rejectInvitation("party-1"))
        throw new ApiError({ status: 409, code: "INVALID_STATE" })
      return base(method as "GET", path, opts as never)
    }) as HttpRequest
    const records = createHttpRecords(request)
    await records.load({ accountId: "me" })
    listed = []
    await expect(
      records.send(
        { accountId: "me" },
        {
          request: post(endpoints.rejectInvitation("party-1")),
          role: "임차인",
          text: "거절",
          update: () => {},
          options: { roomId: "party-1", connecting: true },
        },
      ),
    ).rejects.toMatchObject({ code: "INVALID_STATE" })
    expect(records.getSnapshot().invitations).toEqual([])
  })

  test("끝난 초대로 거절되면 받은 초대만 다시 받고 호실 목록·상세는 받지 않는다", async () => {
    const calls: string[] = []
    const base = server(() => [])
    const request = (async (method: string, path: string, opts?: unknown) => {
      calls.push(`${method} ${path}`)
      if (method === "POST" && path === endpoints.rejectInvitation("party-1"))
        throw new ApiError({ status: 409, code: "INVALID_STATE" })
      return base(method as "GET", path, opts as never)
    }) as HttpRequest
    const records = createHttpRecords(request)
    await expect(
      records.send({ accountId: "me" }, rejectCommand()),
    ).rejects.toMatchObject({ code: "INVALID_STATE" })
    expect(calls).toEqual([
      `POST ${endpoints.rejectInvitation("party-1")}`,
      `GET ${endpoints.invitations}`,
    ])
  })

  // 먼저 시작한 전체 받기가 늦게 끝나도, 연결·거절 뒤 새로 받은 목록에서 빠진 초대가 다시 나타나지 않는다(#431 리뷰)
  describe("늦게 끝난 받기", () => {
    // 받은 초대 GET을 부른 순서대로 붙잡아 두고, 테스트가 원하는 순서로 풀어요
    const held = () => {
      const pending: Array<(v: unknown) => void> = []
      let answer: "reject" | "invalid" = "reject"
      const request = (async (method: string, path: string) => {
        if (path.startsWith("/api/rooms?"))
          return { items: [], nextCursor: null }
        if (path === endpoints.notifications) return []
        if (path === endpoints.invitations)
          return new Promise((resolve) => pending.push(resolve))
        if (
          method === "POST" &&
          path === endpoints.rejectInvitation("party-1")
        ) {
          if (answer === "invalid")
            throw new ApiError({ status: 409, code: "INVALID_STATE" })
          return { partyId: "party-1", status: "REJECTED" }
        }
        throw new Error(`unexpected ${path}`)
      }) as HttpRequest
      return {
        request,
        pending,
        answerInvalid: () => {
          answer = "invalid"
        },
      }
    }
    const settle = () => new Promise((r) => setTimeout(r, 0))

    test("거절 뒤 다시 받은 목록을 먼저 시작한 전체 받기가 덮지 않는다", async () => {
      const h = held()
      const records = createHttpRecords(h.request)
      const me = { accountId: "me" }
      const first = records.load(me)
      await settle()
      const sent = records.send(me, rejectCommand())
      await settle()
      expect(h.pending).toHaveLength(2)
      // 거절 뒤 받기(나중에 시작)가 먼저 끝남: 초대가 빠진 목록
      h.pending[1]([])
      await sent
      expect(records.getSnapshot().invitations).toEqual([])
      // 먼저 시작한 받기가 늦게 끝남: 거절 전 목록
      h.pending[0]([invitation()])
      await first
      expect(records.getSnapshot().invitations).toEqual([])
    })

    test("끝난 초대로 다시 받은 목록도 먼저 시작한 전체 받기가 덮지 않는다", async () => {
      const h = held()
      h.answerInvalid()
      const records = createHttpRecords(h.request)
      const me = { accountId: "me" }
      const first = records.load(me)
      await settle()
      const sent = records.send(me, rejectCommand())
      await settle()
      expect(h.pending).toHaveLength(2)
      h.pending[1]([])
      await expect(sent).rejects.toMatchObject({ code: "INVALID_STATE" })
      h.pending[0]([invitation()])
      await first
      expect(records.getSnapshot().invitations).toEqual([])
    })

    test("늦게 시작한 받기가 먼저 끝나지 않았으면 먼저 시작한 받기의 목록을 반영한다", async () => {
      const h = held()
      const records = createHttpRecords(h.request)
      const me = { accountId: "me" }
      const first = records.load(me)
      const second = records.load(me)
      await settle()
      h.pending[0]([invitation()])
      await first
      expect(records.getSnapshot().invitations?.map((r) => r.id)).toEqual([
        "party-1",
      ])
      h.pending[1]([])
      await second
      expect(records.getSnapshot().invitations).toEqual([])
    })
  })
})

const rejectCommand = () => ({
  request: post(endpoints.rejectInvitation("party-1")),
  role: "임차인" as const,
  text: "거절",
  update: () => {},
  options: { roomId: "party-1", connecting: true },
})

describe("서버 호실의 상대 초대", () => {
  const show = (
    room: Room,
    patch: { target?: { kind: "inspection" | "repair"; id: string } } = {},
  ) => {
    const commit = vi.fn()
    inDialog(
      <InviteForm
        room={room}
        role="임대인"
        sessionPhone={account.phone}
        target={patch.target}
        commit={commit}
        toast={() => {}}
        onCancel={() => {}}
      />,
    )
    return commit
  }
  const sent = (commit: ReturnType<typeof vi.fn>) =>
    commit.mock.calls[0][0] as ApiRequest

  test("번호만 자리는 저장한 번호(가린 모양)로, 번호 없이 보낸다", () => {
    const commit = show(serverRoom())
    expect(
      screen.getByText(/저장한 번호\(010-\*\*\*\*-5678\)로 초대해요/),
    ).toBeTruthy()
    expect(screen.queryByLabelText(/휴대폰 번호/)).toBeNull()
    fireEvent.click(submitButton(/동의하고 보여 주기/))
    expect(sent(commit)).toEqual({
      path: endpoints.invite("r1"),
      body: { consentVersion: SHARE_CONSENT_VERSION },
    })
  })

  test("다른 번호로 초대하면 새 번호를 보낸다(서버가 저장한 번호를 덮어씀)", () => {
    const commit = show(serverRoom())
    fireEvent.click(screen.getByRole("checkbox"))
    fireEvent.change(screen.getByLabelText("새 휴대폰 번호"), {
      target: { value: "010-5555-6666" },
    })
    fireEvent.click(submitButton(/동의하고 보여 주기/))
    expect(sent(commit).body).toEqual({
      phone: "01055556666",
      consentVersion: SHARE_CONSENT_VERSION,
    })
  })

  test("비어 있음 자리는 번호를 꼭 넣고, 내 번호는 막는다", () => {
    const commit = show(
      serverRoom({ counterpartStatus: "EMPTY", counterpartMaskedPhone: null }),
    )
    fireEvent.change(screen.getByLabelText(/세입자 휴대폰 번호/), {
      target: { value: account.phone },
    })
    fireEvent.click(submitButton(/동의하고 보여 주기/))
    expect(screen.getByRole("alert").textContent).toBe(
      "내 계정 번호는 상대로 넣을 수 없어요.",
    )
    expect(commit).not.toHaveBeenCalled()
  })

  test("점검과 함께 초대하면 서버 점검 ID를 기록으로 보내고, 수리 건은 호실 초대로만 보낸다", () => {
    const room: Room = {
      ...serverRoom(),
      moveIn: {
        status: "확인 요청",
        author: "임대인",
        server: { id: "insp-1" },
      } as Room["moveIn"],
    }
    const commit = show(room, { target: { kind: "inspection", id: "item-1" } })
    fireEvent.click(submitButton(/동의하고 보여 주기/))
    expect(sent(commit).body).toEqual({
      consentVersion: SHARE_CONSENT_VERSION,
      record: { kind: "INSPECTION", id: "insp-1" },
    })
  })

  test("이미 초대했거나 거절된 자리는 다시 초대를 열지 않는다(별도 작업)", () => {
    const commit = show(serverRoom({ counterpartStatus: "INVITED" }))
    expect(
      screen.getByText(/번호 고치기·다시 초대는 아직 준비 중/),
    ).toBeTruthy()
    expect(submitButton(/동의하고 보여 주기/).disabled).toBe(true)
    expect(commit).not.toHaveBeenCalled()
  })
})

describe("서버 초대 연결", () => {
  test("공유 동의 판만 보내고, 같은 집 합류는 고르지 않으며, 연결한 호실 기록 ID로 간다", async () => {
    const shared = withAccountPhone(
      [roomFromInvitation(invitation())],
      account.phone,
    )[0]
    // 같은 쪽으로 내가 만든 호실이 있어도 서버 초대에는 같은 집 고르기가 없어요(ROOM-6은 별도 작업)
    const mine: Room = {
      ...structuredClone(createDemo().rooms[0]),
      id: "own",
      creator: "임차인",
      status: "거주 중",
      members: { 임차인: "me" },
      preJoinOf: undefined,
    }
    const act = vi.fn().mockResolvedValue({ id: "room-9" })
    const onConnected = vi.fn()
    inDialog(
      <ConnectForm
        sharedRoom={shared}
        role="임차인"
        account={account}
        guest
        db={createDemo()}
        myRooms={[mine]}
        act={act}
        onConnected={onConnected}
        onRejectToOwn={() => {}}
        onReject={() => {}}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByText(/반곡동 정\*윤님의 호실/)).toBeTruthy()
    expect(screen.queryByText("같은 집에 대해 내가 만든 기록")).toBeNull()
    fireEvent.click(submitButton(/동의하고 연결하기/))
    const [request] = act.mock.calls[0] as [ApiRequest]
    expect(request).toEqual({
      path: endpoints.connect("party-1"),
      body: { consentVersion: SHARE_CONSENT_VERSION },
    })
    await vi.waitFor(() => expect(onConnected).toHaveBeenCalled())
    expect(onConnected.mock.calls[0][0].id).toBe("room-9")
  })
})

describe("연결 화면의 가린 번호", () => {
  const page = (room: Room) =>
    render(
      <ConnectionPage
        room={room}
        role="임대인"
        readOnly={false}
        sessionPhone={account.phone}
        roomHas={() => false}
        open={() => {}}
      />,
    )

  test("번호만 자리는 저장한 번호를 가린 모양으로 보인다", () => {
    page(serverRoom())
    expect(screen.getByText(/저장한 번호 · 010-\*\*\*\*-5678/)).toBeTruthy()
  })

  test("초대됨 자리는 초대 번호를 가린 모양으로 보인다", () => {
    page(serverRoom({ counterpartStatus: "INVITED" }))
    expect(screen.getByText(/초대 번호 010-\*\*\*\*-5678/)).toBeTruthy()
  })

  test("서버가 invite를 주지 않으면 초대 버튼이 없다", () => {
    page(serverRoom())
    expect(screen.queryByRole("button", { name: /상대 초대하기/ })).toBeNull()
  })
})
