import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, test } from "vitest"
import { isConnected } from "../src/api/connected"
import { endpoints } from "../src/api/endpoints"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRepairCases } from "../src/api/http-repair-cases"
import {
  repairFromDetail,
  type RepairCaseDetailBody,
} from "../src/api/http-repair-view"
import type { Repair } from "../src/data/demo"
import { ProgressNotes } from "../src/features/repair/progress-notes"

// 진행 상황(06 4-6 progressNotes·ask-progress·progress-notes, REP-6, #367)
const note = (patch: Record<string, unknown> = {}) => ({
  id: "n1",
  side: "TENANT",
  text: "업체가 수요일에 와요",
  asksCheck: false,
  createdAt: "2026-11-20T03:00:00Z",
  photos: [],
  response: null,
  canRespond: false,
  ...patch,
})
const body = (progressNotes: unknown[]): RepairCaseDetailBody =>
  ({
    id: "c1",
    roomRecordId: "r1",
    mySide: "LANDLORD",
    registeredBy: "TENANT",
    status: "IN_PROGRESS",
    closeRequestedBy: null,
    closedBy: null,
    closeObjection: null,
    solo: false,
    createdAt: "2026-11-19T03:00:00Z",
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
    progressNotes,
    availableActions: ["progress-notes"],
  }) as unknown as RepairCaseDetailBody

describe("상세의 진행 상황 → 화면 모델", () => {
  test("남긴 측·글·사진 수를 옮기고, 답은 남긴 측의 상대가 한 것으로 보인다", () => {
    const { repair } = repairFromDetail(
      body([
        note({ photos: [{}, {}] }),
        note({
          id: "n2",
          asksCheck: true,
          response: {
            answer: "NEED_MORE",
            reason: "사진이 더 필요해요",
            at: "2026-11-21T03:00:00Z",
          },
          canRespond: true,
        }),
      ]),
    )
    expect(repair.progressNotes).toEqual([
      {
        id: "n1",
        by: "임차인",
        text: "업체가 수요일에 와요",
        asksCheck: false,
        at: "2026-11-20T03:00:00Z",
        photoCount: 2,
        canRespond: false,
      },
      {
        id: "n2",
        by: "임차인",
        text: "업체가 수요일에 와요",
        asksCheck: true,
        at: "2026-11-20T03:00:00Z",
        photoCount: 0,
        response: {
          by: "임대인",
          answer: "보류",
          reason: "사진이 더 필요해요",
          at: "2026-11-21T03:00:00Z",
        },
        canRespond: true,
      },
    ])
  })
})

describe("진행 상황 요청은 06 4-6 모양으로 보낸다", () => {
  const fake = () => {
    const sent: { method: string; path: string; body?: unknown }[] = []
    const request = (async (m: string, p: string, o?: { body?: unknown }) => {
      sent.push({ method: m, path: p, body: o?.body })
      return m === "GET" ? body([]) : undefined
    }) as unknown as HttpRequest
    return { sent, api: createHttpRepairCases(request) }
  }
  const s = { accountId: "m1" }

  test("묻기는 본문 없이, 남기기는 {text, uploadTickets, asksCheck}", async () => {
    const { sent, api } = fake()
    await api.askProgress(s, "c1")
    await api.addProgressNote(s, "c1", {
      text: " 곰팡이가 번졌어요 ",
      photos: [],
      asksCheck: true,
    })
    const posts = sent.filter((x) => x.method === "POST")
    expect(posts).toEqual([
      {
        method: "POST",
        path: endpoints.repairAction("c1", "ask-progress"),
        body: undefined,
      },
      {
        method: "POST",
        path: endpoints.repairAction("c1", "progress-notes"),
        body: { text: "곰팡이가 번졌어요", uploadTickets: [], asksCheck: true },
      },
    ])
  })

  test("답은 진행 상황에 보내고, 맞아요는 사유 없이", async () => {
    const { sent, api } = fake()
    await api.respondProgressNote(s, "c1", "n2", { answer: "ACK" })
    await api.respondProgressNote(s, "c1", "n2", {
      answer: "DISPUTE",
      reason: "원래 있던 자국이에요",
    })
    const posts = sent.filter((x) => x.method === "POST")
    expect(posts.map((x) => [x.path, x.body])).toEqual([
      ["/api/progress-notes/n2/respond", { answer: "ACK" }],
      [
        "/api/progress-notes/n2/respond",
        { answer: "DISPUTE", reason: "원래 있던 자국이에요" },
      ],
    ])
  })

  test("사진을 고르면 업로드 표 연결 전이라 보내지 않는다", async () => {
    const { sent, api } = fake()
    await expect(
      api.addProgressNote(s, "c1", {
        text: "",
        photos: ["data:"],
        asksCheck: false,
      }),
    ).rejects.toMatchObject({ code: "NOT_CONNECTED" })
    expect(sent).toEqual([])
  })

  test("세 API는 서버로 보내는 목록에 있다", () => {
    expect(isConnected("POST", "/api/repair-cases/c1/ask-progress")).toBe(true)
    expect(isConnected("POST", "/api/repair-cases/c1/progress-notes")).toBe(
      true,
    )
    expect(isConnected("POST", "/api/progress-notes/n1/respond")).toBe(true)
  })
})

describe("진행 상황 화면(08 5-2)", () => {
  const repair = (patch: Partial<Repair> = {}) =>
    ({
      ...repairFromDetail(body([])).repair,
      ...patch,
    }) as Repair
  const show = (
    r: Repair,
    opts: { canNote?: boolean; canAsk?: boolean } = {},
  ) =>
    render(
      <ProgressNotes
        repair={r}
        role="임대인"
        canNote={opts.canNote ?? false}
        canAsk={opts.canAsk ?? false}
        onCommand={async () => undefined}
      />,
    )

  test("확인을 요청한 진행 상황은 상대 확인 기다림이 보이고, 서버가 허락할 때만 답 버튼이 보인다", () => {
    show(
      repair({
        progressNotes: [
          {
            id: "n1",
            by: "임차인",
            text: "구멍이 커졌어요",
            asksCheck: true,
            at: "2026-11-20T03:00:00Z",
            photoCount: 1,
            canRespond: true,
          },
          {
            id: "n2",
            by: "임차인",
            text: "업체가 와요",
            asksCheck: false,
            at: "2026-11-21T03:00:00Z",
            photoCount: 0,
            canRespond: false,
          },
        ],
      }),
    )
    expect(screen.getByText(/확인 기다림/)).toBeTruthy()
    expect(screen.getByText("[사진 1]")).toBeTruthy()
    expect(screen.getAllByRole("button", { name: "맞아요" })).toHaveLength(1)
  })

  test("남기기·묻기 버튼은 availableActions에 있을 때만, 상황이 바뀌었어요는 하자를 둘 다 확인한 건에서만", () => {
    const { unmount } = show(repair({ ack: "확인 대기" }))
    expect(
      screen.queryByRole("button", { name: /진행 상황 남기기/ }),
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "진행 상황 묻기" })).toBeNull()
    unmount()
    show(repair({ ack: "확인됨" }), { canNote: true, canAsk: true })
    expect(screen.getByRole("button", { name: "진행 상황 묻기" })).toBeTruthy()
    fireEvent.click(screen.getByRole("button", { name: /진행 상황 남기기/ }))
    expect(screen.getByText(/상황이 바뀌었어요\./)).toBeTruthy()
  })
})
