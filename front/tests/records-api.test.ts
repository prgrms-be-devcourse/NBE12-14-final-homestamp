import { expect, test } from "vitest"
import { createMockStore } from "../src/api/mock/store"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockRecords } from "../src/api/mock/records"
import { createHttpRecords } from "../src/api/http-records"
import type { HttpRequest } from "../src/api/http-client"
import { endpoints } from "../src/api/endpoints"
import {
  noRequest,
  notInSpec,
  post,
  type RecordChange,
} from "../src/api/records"

// 기록 API 경계: 화면은 기록을 이 API로만 읽고 바꿔요. 목업은 저장소를, HTTP는 06 4장 주소를 써요.
const s = { accountId: "dev-tenant" }
const change = (request: RecordChange["request"]): RecordChange => ({
  request,
  role: "임차인",
  text: "지불 기록 · 시험",
  update: (d) => {
    d.repairs[0].title = "바뀜"
  },
  options: { roomId: "302" },
})
// 보낸 요청을 적어 두는 가짜 HTTP 요청 함수
function fakeHttp(made?: { id: string }) {
  const calls: { method: string; path: string; body?: unknown }[] = []
  const request = (async (
    method: string,
    path: string,
    opts?: { body?: unknown },
  ) => {
    calls.push({ method, path, body: opts?.body })
    if (method === "POST") return made
    return path === endpoints.rooms ? [{ id: "302" }] : []
  }) as HttpRequest
  return { calls, request }
}

test("목업: 요청을 보내면 저장소가 바로 바뀌고 경과에 남는다", async () => {
  const store = createMockStore(createSampleSeed)
  const records = createMockRecords(store)
  const p = records.send(s, change(post(endpoints.payments("leak"))))
  // 화면이 기다리지 않아도 바로 바뀌어요
  expect(records.getSnapshot().repairs[0].title).toBe("바뀜")
  await p
  expect(records.getSnapshot().events[0].text).toBe("지불 기록 · 시험")
})

test("목업: 규칙 위반은 거절로 돌려주고 기록은 그대로다", async () => {
  const store = createMockStore(createSampleSeed)
  const records = createMockRecords(store)
  const before = store.getState()
  await expect(
    records.send(s, {
      ...change(post(endpoints.payments("leak"))),
      update: () => {
        throw new Error("규칙 위반")
      },
    }),
  ).rejects.toThrow("규칙 위반")
  expect(store.getState()).toBe(before)
})

test("HTTP: 06 주소로 POST를 보내고, 만든 ID를 돌려주며, 기록을 다시 받는다", async () => {
  const { calls, request } = fakeHttp({ id: "new-room" })
  const records = createHttpRecords(request)
  const made = await records.send(
    s,
    change(post(endpoints.rooms, { side: "임차인" })),
  )
  expect(made.id).toBe("new-room")
  expect(calls[0]).toEqual({
    method: "POST",
    path: "/api/rooms",
    body: { side: "임차인" },
  })
  // 보낸 뒤 내 호실·알림을 다시 받아요
  expect(calls.slice(1).map((c) => c.path)).toEqual(
    expect.arrayContaining([endpoints.rooms, endpoints.notifications]),
  )
  expect(records.getSnapshot().rooms).toEqual([{ id: "302" }])
})

test("HTTP: 여러 사진처럼 요청 목록이면 차례로 보낸다", async () => {
  const { calls, request } = fakeHttp()
  const records = createHttpRecords(request)
  await records.send(
    s,
    change([
      post(endpoints.photoAction("p1", "mark-wrong")),
      post(endpoints.photoAction("p2", "mark-wrong")),
    ]),
  )
  expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual([
    "/api/photos/p1/mark-wrong",
    "/api/photos/p2/mark-wrong",
  ])
})

test("HTTP: 06에 주소가 없는 행동은 보내지 않고 안내하며, 서버가 스스로 남기는 기록은 보낼 것이 없다", async () => {
  const { calls, request } = fakeHttp()
  const records = createHttpRecords(request)
  await expect(
    records.send(s, change(notInSpec("점검 혼자 마치기"))),
  ).rejects.toMatchObject({ code: "NOT_CONNECTED" })
  await expect(
    records.send(s, change(noRequest("링크 열람"))),
  ).resolves.toEqual({})
  expect(calls).toHaveLength(0)
})
