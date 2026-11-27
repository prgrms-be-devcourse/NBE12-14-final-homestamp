import { describe, expect, test } from "vitest"
import { isConnected } from "../src/api/connected"
import { endpoints } from "../src/api/endpoints"
import { ApiError } from "../src/api/errors"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRepairCases } from "../src/api/http-repair-cases"
import {
  repairFromDetail,
  type ServerPhotoCard,
  type RepairCaseDetailBody,
} from "../src/api/http-repair-view"
import { ERASED_PHOTO, UNAVAILABLE_PHOTO } from "../src/lib/photos"

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

  test("사진을 고른 보충·원인 설명은 상세에서 본 호실로 사진을 올리고 그 업로드 표를 보내요(#366)", async () => {
    const { request, sent } = fakeRequest()
    const uploads: { roomId: string; photos: readonly string[] }[] = []
    const api = createHttpRepairCases(request, {
      upload: async (roomId, photos) => {
        uploads.push({ roomId, photos })
        return photos.map((_, i) => `t${i}`)
      },
    })
    await api.supplement(session, "c1", {
      text: "",
      photos: ["data:a", "data:b"],
    })
    await api.addCauseNote(session, "c1", {
      basis: "내 추측",
      text: "글",
      photos: ["data:c"],
    })
    expect(uploads).toEqual([
      { roomId: "r1", photos: ["data:a", "data:b"] },
      { roomId: "r1", photos: ["data:c"] },
    ])
    const posts = sent.filter((x) => x.method === "POST")
    expect(posts[0].body).toEqual({
      description: "",
      uploadTickets: ["t0", "t1"],
    })
    expect(posts[1].body).toMatchObject({ uploadTickets: ["t0"] })
  })

  test("사진을 올리지 못하면 행동을 보내지 않아요", async () => {
    const { request, sent } = fakeRequest()
    const api = createHttpRepairCases(request, {
      upload: async () => {
        throw new ApiError({ status: 429, code: "UPLOAD_LIMIT" })
      },
    })
    await expect(
      api.supplement(session, "c1", { text: "", photos: ["data:a"] }),
    ).rejects.toMatchObject({ code: "UPLOAD_LIMIT" })
    expect(sent.filter((x) => x.method === "POST")).toEqual([])
  })
})

describe("수리 건 사진(06 4-5 PhotoCard + view-urls, #366)", () => {
  const card = (patch: Partial<ServerPhotoCard>): ServerPhotoCard => ({
    id: "p1",
    stage: "BEFORE",
    uploadedBy: "TENANT",
    sortOrder: 0,
    createdAt: "2026-11-25T16:30:00Z",
    erased: null,
    ...patch,
  })

  test("수리 전 칸에는 등록·보충 사진을 순서대로, 수리 후 칸에는 AFTER를, 지운 사진·주소 없는 사진은 정해진 표시로", () => {
    const { repair } = repairFromDetail(
      body({
        photos: [
          card({ id: "e", stage: "EVIDENCE", sortOrder: 2 }),
          card({ id: "b", sortOrder: 0 }),
          card({
            id: "x",
            sortOrder: 1,
            erased: {
              side: "TENANT",
              at: "2026-11-26T00:00:00Z",
              reason: "PRIVACY",
            },
          }),
          card({ id: "a", stage: "AFTER" }),
          card({ id: "n", stage: "AFTER", sortOrder: 1 }),
        ],
      }),
      new Map([
        ["b", "https://s3/b"],
        ["e", "https://s3/e"],
        ["a", "https://s3/a"],
      ]),
    )
    expect(repair.photos).toEqual([
      "https://s3/b",
      ERASED_PHOTO,
      "https://s3/e",
    ])
    expect(repair.afterPhotos).toEqual(["https://s3/a", UNAVAILABLE_PHOTO])
  })

  test("같은 사진이 여러 곳에 보여도 주소는 한 번만 요청하고, 로그인이 풀린 401은 상세 오류로 알려요(#371 리뷰)", async () => {
    const shared = card({ id: "s", stage: "BASIS" })
    const cause = {
      id: "n2",
      side: "TENANT",
      text: "새 설명",
      basis: "GUESS",
      expertName: null,
      previousId: "n1",
      createdAt: "2026-11-26T00:00:00Z",
      response: null,
      photos: [shared],
      canRespond: false,
      previous: [
        {
          id: "n1",
          side: "TENANT",
          text: "옛 설명",
          basis: "GUESS",
          expertName: null,
          previousId: null,
          createdAt: "2026-11-25T00:00:00Z",
          response: null,
          photos: [shared],
          canRespond: false,
          previous: [],
        },
      ],
    } as unknown as RepairCaseDetailBody["causeNotes"][number]
    const sent: unknown[] = []
    let status = 200
    const request = (async (
      method: string,
      _p: string,
      o?: { body?: unknown },
    ) => {
      if (method === "GET")
        return body({ photos: [card({ id: "s" })], causeNotes: [cause] })
      sent.push(o?.body)
      if (status === 401)
        throw new ApiError({ status: 401, code: "LOGIN_REQUIRED" })
      return { items: [] }
    }) as unknown as HttpRequest
    const api = createHttpRepairCases(request)
    await api.get(session, "c1")
    expect(sent).toEqual([{ photoIds: ["s"], variant: "THUMB" }])
    status = 401
    await expect(api.get(session, "c1")).rejects.toMatchObject({ status: 401 })
  })

  test("상세를 받으면 지우지 않은 사진의 썸네일 주소를 받고, 주소 받기가 실패해도 상세는 보여요", async () => {
    const sent: { path: string; body?: unknown }[] = []
    let fail = false
    const request = (async (
      method: string,
      path: string,
      o?: { body?: unknown },
    ) => {
      sent.push({ path, body: o?.body })
      if (method === "GET")
        return body({
          photos: [
            card({ id: "b" }),
            card({
              id: "x",
              sortOrder: 1,
              erased: {
                side: null,
                at: "2026-11-26T00:00:00Z",
                reason: "THIRD_PARTY",
              },
            }),
          ],
        })
      if (fail) throw new ApiError({ status: 500, code: "INTERNAL_ERROR" })
      return {
        items: [
          { photoId: "b", url: "https://s3/b", expiresAt: null, erased: null },
        ],
      }
    }) as unknown as HttpRequest
    const api = createHttpRepairCases(request)
    expect((await api.get(session, "c1")).repair.photos).toEqual([
      "https://s3/b",
      ERASED_PHOTO,
    ])
    expect(sent[1]).toEqual({
      path: endpoints.photoViewUrls,
      body: { photoIds: ["b"], variant: "THUMB" },
    })
    fail = true
    expect((await api.get(session, "c1")).repair.photos).toEqual([
      UNAVAILABLE_PHOTO,
      ERASED_PHOTO,
    ])
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
