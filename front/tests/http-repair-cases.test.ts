import { describe, expect, test } from "vitest"
import { isConnected } from "../src/api/connected"
import { endpoints } from "../src/api/endpoints"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRepairCases } from "../src/api/http-repair-cases"
import {
  repairFromDetail,
  type RepairCaseDetailBody,
} from "../src/api/http-repair-view"

// 수리 건 상세(06 4-6 GET /api/repair-cases/{id})의 서버 모양 → 화면 모델(#179)
const body = (
  patch: Partial<RepairCaseDetailBody> = {},
): RepairCaseDetailBody => ({
  id: "c1",
  roomRecordId: "r1",
  mySide: "LANDLORD",
  registeredBy: "TENANT",
  status: "NOT_STARTED",
  closeRequestedBy: null,
  closedBy: null,
  closeObjection: null,
  solo: false,
  createdAt: "2026-11-25T16:30:00Z",
  asOf: null,
  content: {
    no: 3,
    roomPartId: "p1",
    partName: "세면대",
    title: "수전에서 물이 새요",
    description: null,
  },
  defect: { ack: "PENDING", nextRerequestAt: null },
  money: { status: "UNDECIDED" },
  photos: [],
  causeNotes: [],
  progressNotes: [],
  availableActions: ["respond", "start"],
  ...patch,
})
const session = { accountId: "m1" }

describe("수리 건 상세 응답을 화면 모델로 바꿔요", () => {
  test("기본 칸: 제목·부위·설명·등록 측·날짜(한국 시간)·판 번호", () => {
    const { repair } = repairFromDetail(body())
    expect(repair).toMatchObject({
      id: "c1",
      roomId: "r1",
      title: "수전에서 물이 새요",
      zone: "세면대",
      description: "",
      author: "임차인",
      // UTC 11/25 16:30은 한국 11/26 01:30
      date: "2026.11.26",
      version: 3,
      photos: [],
    })
  })

  test("하자 인정 다섯 가지를 화면 글자로", () => {
    const ack = (a: RepairCaseDetailBody["defect"]["ack"]) =>
      repairFromDetail(body({ defect: { ack: a, nextRerequestAt: null } }))
        .repair.ack
    expect(ack("PENDING")).toBe("확인 대기")
    expect(ack("ACK")).toBe("확인됨")
    expect(ack("NEED_MORE")).toBe("보충 요청")
    expect(ack("DISPUTE")).toBe("이견")
    expect(ack("UNCONFIRMED")).toBe("상대 미확인")
  })

  test("시작 전은 하자 인정으로 나누고, 종료 확인 대기는 처리 중 + 요청한 측", () => {
    expect(repairFromDetail(body()).repair.status).toBe("확인 전")
    expect(
      repairFromDetail(body({ defect: { ack: "ACK", nextRerequestAt: null } }))
        .repair.status,
    ).toBe("확인 완료")
    const waiting = repairFromDetail(
      body({ status: "CLOSE_WAIT", closeRequestedBy: "LANDLORD" }),
    ).repair
    expect(waiting.status).toBe("처리 중")
    expect(waiting.closeBy).toBe("임대인")
    expect(
      repairFromDetail(body({ status: "IN_PROGRESS" })).repair.closeBy,
    ).toBeUndefined()
  })

  test("처리 완료는 한쪽이 끝냈을 때만 그 측을 soloClosed로", () => {
    expect(
      repairFromDetail(body({ status: "DONE", closedBy: "BOTH" })).repair,
    ).toMatchObject({ status: "처리 완료", soloClosed: undefined })
    expect(
      repairFromDetail(body({ status: "DONE", closedBy: "TENANT" })).repair
        .soloClosed,
    ).toBe("임차인")
  })

  test("원인 설명: 지금 설명과 고치기 전 설명(supersededBy), 상대 응답", () => {
    const { repair } = repairFromDetail(
      body({
        causeNotes: [
          {
            id: "n2",
            side: "TENANT",
            text: "배관이 낡았어요",
            basis: "EXPERT",
            expertName: "○○설비",
            previousId: "n1",
            createdAt: "2026-11-25T01:00:00Z",
            response: {
              answer: "DISPUTE",
              reason: "작년에 교체했어요",
              at: "2026-11-25T02:00:00Z",
            },
            photos: [],
            canRespond: true,
            previous: [
              {
                id: "n1",
                side: "TENANT",
                text: "잘 모르겠어요",
                basis: "GUESS",
                expertName: null,
                previousId: null,
                createdAt: "2026-11-24T01:00:00Z",
                response: null,
                photos: [],
                canRespond: false,
                previous: [],
              },
            ],
          },
        ],
      }),
    )
    expect(repair.causes).toEqual([
      expect.objectContaining({
        id: "n2",
        by: "임차인",
        basis: "업체·전문가 소견",
        company: "○○설비",
        supersededBy: undefined,
        supersedes: "n1",
        canRespond: true,
        responses: [
          {
            by: "임대인",
            agree: false,
            reason: "작년에 교체했어요",
            at: "2026-11-25T02:00:00Z",
          },
        ],
      }),
      expect.objectContaining({
        id: "n1",
        basis: "내 추측",
        supersededBy: "n2",
        canRespond: false,
        responses: [],
      }),
    ])
  })

  test("행동은 서버가 준 것 중 화면에 버튼이 있는 것만", () => {
    const { availableActions } = repairFromDetail(
      body({
        availableActions: [
          "respond",
          "ask-progress",
          "progress-notes",
          "cause-notes",
          "accept-all",
        ],
      }),
    )
    expect(availableActions).toEqual([
      "respond",
      "ask-progress",
      "progress-notes",
      "cause-notes",
    ])
  })
})

// 보낸 요청을 모으고, 상세 조회에는 위 body를 돌려주는 가짜 요청
function fakeRequest() {
  const sent: { method: string; path: string; body?: unknown }[] = []
  const request = (async (
    method: string,
    path: string,
    opts?: { body?: unknown },
  ) => {
    sent.push({ method, path, body: opts?.body })
    if (method === "GET") return body()
    return undefined
  }) as unknown as HttpRequest
  return { request, sent }
}

describe("수리 건 행동은 06 4-6 본문 모양으로 보내고 상세를 다시 받아요", () => {
  test("보충은 {description, uploadTickets}", async () => {
    const { request, sent } = fakeRequest()
    await createHttpRepairCases(request).supplement(session, "c1", {
      text: "사진을 더 찍었어요",
      photos: [],
    })
    expect(sent).toEqual([
      {
        method: "POST",
        path: endpoints.repairAction("c1", "supplement"),
        body: { description: "사진을 더 찍었어요", uploadTickets: [] },
      },
      { method: "GET", path: endpoints.repairCase("c1"), body: undefined },
    ])
  })

  test("종료 확인에 하자 맞아요를 함께 남기면 본 판 번호도 보내요", async () => {
    const { request, sent } = fakeRequest()
    await createHttpRepairCases(request).confirmClose(session, "c1", {
      alsoAckDefect: true,
      seenVersionNo: 3,
    })
    expect(sent[0].body).toEqual({ alsoAckDefect: true, seenVersionNo: 3 })
  })

  test("원인 설명은 근거 종류를 코드로, 업체 이름은 업체·전문가 소견일 때만, 고치면 previousId", async () => {
    const { request, sent } = fakeRequest()
    const api = createHttpRepairCases(request)
    await api.addCauseNote(session, "c1", {
      basis: "업체·전문가 소견",
      text: "배관이 낡았어요",
      company: "○○설비",
      photos: [],
      previousId: "n1",
    })
    await api.addCauseNote(session, "c1", {
      basis: "직접 봤어요",
      text: "물이 떨어져요",
      company: "남은 값",
      photos: [],
    })
    expect(sent[0].body).toEqual({
      text: "배관이 낡았어요",
      basis: "EXPERT",
      expertName: "○○설비",
      previousId: "n1",
      uploadTickets: [],
    })
    expect(sent[2].body).toEqual({
      text: "물이 떨어져요",
      basis: "SEEN",
      expertName: null,
      previousId: null,
      uploadTickets: [],
    })
  })

  test("사진을 고른 보충·원인 설명은 업로드 표 연결 전이라 서버로 보내지 않아요", async () => {
    const { request, sent } = fakeRequest()
    const api = createHttpRepairCases(request)
    await expect(
      api.supplement(session, "c1", { text: "", photos: ["data:image/x"] }),
    ).rejects.toMatchObject({ code: "NOT_CONNECTED" })
    await expect(
      api.addCauseNote(session, "c1", {
        basis: "내 추측",
        text: "글",
        photos: ["data:image/x"],
      }),
    ).rejects.toMatchObject({ code: "NOT_CONNECTED" })
    expect(sent).toEqual([])
  })
})

describe("수리 건 API 중 연결한 것만 서버로 보내요", () => {
  test("상세와 그 버튼, 원인 설명 응답", () => {
    expect(isConnected("GET", "/api/repair-cases/c1")).toBe(true)
    for (const a of [
      "respond",
      "supplement",
      "start",
      "request-close",
      "confirm-close",
      "not-yet",
      "close-alone",
      "cause-notes",
    ])
      expect(isConnected("POST", `/api/repair-cases/c1/${a}`)).toBe(true)
    expect(isConnected("POST", "/api/cause-notes/n1/respond")).toBe(true)
  })

  test("하자 등록·사진 추가·다시 열기는 아직", () => {
    expect(isConnected("POST", "/api/rooms/r1/repair-cases")).toBe(false)
    expect(isConnected("POST", "/api/repair-cases/c1/photos")).toBe(false)
    expect(isConnected("POST", "/api/repair-cases/c1/reopen")).toBe(false)
    expect(isConnected("GET", "/api/repair-cases/c1/extra")).toBe(false)
  })
})

test("혼자 끝낸 처리 완료에 남은 이견(closeObjection)을 화면 모델로 옮긴다(REP-5f, #359)", () => {
  const { repair } = repairFromDetail(
    body({
      status: "DONE",
      closedBy: "LANDLORD",
      closeObjection: {
        side: "TENANT",
        reason: "아직 물이 새요",
        at: "2026-11-27T05:00:00Z",
      },
    }),
  )
  expect(repair.closeObjection).toEqual({
    by: "임차인",
    reason: "아직 물이 새요",
    at: "2026-11-27T05:00:00Z",
  })
  expect(repairFromDetail(body()).repair.closeObjection).toBeUndefined()
})
