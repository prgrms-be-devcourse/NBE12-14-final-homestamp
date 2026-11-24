import { expect, test } from "vitest"
import { createMockStore } from "../src/api/mock/store"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockRepairCases } from "../src/api/mock/repair-cases"
import { ApiError } from "../src/api/errors"
import { AFTER_PHOTO_REQUIRED } from "../src/lib/repair-rules"

// 수리 건 API 경계(06 4장 /api/repair-cases). 화면은 이 인터페이스만 부르고, 목업이 서버 역할을 해요.
const tenant = { accountId: "dev-tenant" }
const landlord = { accountId: "dev-landlord" }
function setup() {
  const store = createMockStore(createSampleSeed)
  const api = createMockRepairCases(store)
  const repair = store.getState().repairs.find((r) => r.status === "처리 중")!
  return { store, api, id: repair.id }
}

test("상세를 읽으면 수리 건과 지금 누를 수 있는 행동을 함께 준다", async () => {
  const { api, id } = setup()
  const v = await api.get(tenant, id)
  expect(v.repair.id).toBe(id)
  expect(Array.isArray(v.availableActions)).toBe(true)
})

test("호실 회원이 아니면 남의 기록도 404로 거절한다(06 4-3)", async () => {
  const { api, id } = setup()
  await expect(api.get({ accountId: "nobody" }, id)).rejects.toMatchObject({
    status: 404,
    code: "NOT_FOUND",
  })
})

test("지금 할 수 없는 행동은 INVALID_STATE로 거절한다", async () => {
  const { api, id } = setup()
  // 처리 중인 건에 '수리 시작'은 행동 목록에 없어요
  const err = await api.start(tenant, id).catch((e) => e)
  expect(err).toBeInstanceOf(ApiError)
  expect(err.code).toBe("INVALID_STATE")
})

test("규칙 위반은 문구를 담은 API 오류로 돌려주고 기록은 바뀌지 않는다", async () => {
  const { store, api, id } = setup()
  store.setState((d) => ({
    ...d,
    repairs: d.repairs.map((r) =>
      r.id === id ? { ...r, afterPhotos: [], closeBy: undefined } : r,
    ),
  }))
  const before = store.getState().events.length
  const v = await api.get(tenant, id)
  if (!v.availableActions.includes("request-close")) return
  const err = await api.requestClose(tenant, id).catch((e) => e)
  expect(err).toBeInstanceOf(ApiError)
  expect(err.message).toBe(AFTER_PHOTO_REQUIRED)
  expect(store.getState().events.length).toBe(before)
})

test("행동을 보내면 기록이 바뀌고 경과에 남으며, 새 행동 목록을 돌려준다", async () => {
  const { store, api, id } = setup()
  store.setState((d) => ({
    ...d,
    repairs: d.repairs.map((r) =>
      r.id === id
        ? { ...r, afterPhotos: ["a.jpg"], closeBy: undefined, author: "임차인" }
        : r,
    ),
  }))
  const v = await api.requestClose(tenant, id)
  expect(v.repair.closeBy).toBe("임차인")
  expect(store.getState().events[0].text).toMatch("수리 종료 확인 요청")
  expect(store.getState().events[0].target).toEqual({ kind: "repair", id })
  // 상대 쪽에는 종료 확인·아직 안 됐어요가 열려요
  const other = await api.get(landlord, id)
  expect(other.availableActions).toEqual(
    expect.arrayContaining(["confirm-close", "not-yet"]),
  )
  const done = await api.confirmClose(landlord, id)
  expect(done.repair.status).toBe("처리 완료")
})

const room302 = "302"
const photo = "data:image/png;base64,AA"

test("하자 등록: 사진이 있어야 하고, 거주가 끝난 호실에는 남길 수 없다(REP-1, 불변식 9)", async () => {
  const { store, api } = setup()
  await expect(
    api.create(tenant, room302, {
      title: "창문 틈",
      zone: "방 · 창",
      description: "",
      photos: [],
    }),
  ).rejects.toMatchObject({ code: "PHOTO_REQUIRED" })
  const v = await api.create(tenant, room302, {
    title: "창문 틈",
    zone: "방 · 창",
    description: "바람이 들어와요",
    photos: [photo],
  })
  expect([v.repair.title, v.repair.author, v.repair.ack]).toEqual([
    "창문 틈",
    "임차인",
    "확인 대기",
  ])
  expect(store.getState().events[0].text).toMatch("창문 틈 · 하자 등록")
  // 상대 쪽에는 하자 응답이 열려요
  expect((await api.get(landlord, v.repair.id)).availableActions).toContain(
    "respond",
  )
  store.setState((d) => ({
    ...d,
    rooms: d.rooms.map((r) =>
      r.id === room302 ? { ...r, status: "거주 종료" } : r,
    ),
  }))
  await expect(
    api.create(tenant, room302, {
      title: "또",
      zone: "방 · 창",
      description: "",
      photos: [photo],
    }),
  ).rejects.toMatchObject({ code: "ROOM_ENDED" })
})

test("재발 등록은 이전 수리 건을 잇고 경과에 재발로 남는다", async () => {
  const { store, api } = setup()
  const done = store.getState().repairs.find((r) => r.status === "처리 완료")!
  const v = await api.create(tenant, room302, {
    title: "다시 샘",
    zone: done.zone,
    description: "",
    photos: [photo],
    recurrenceOf: done.id,
  })
  expect(v.repair.recurrenceOf).toBe(done.id)
  expect(store.getState().events[0].text).toMatch("이전 수리 건 이후 재발")
})

test("사진 추가: 수리 후 기록은 처리 중에 남고, 처음 기록한 쪽은 사진을 더할 수 있다", async () => {
  const { api, id } = setup()
  const after = await api.addPhotos(tenant, id, {
    photos: [photo],
    afterRecord: { title: "수리 후", description: "수전 교체" },
  })
  expect(after.repair.afterPhotos).toContain(photo)
  expect(after.repair.afterRecords!.at(-1)!.description).toBe("수전 교체")
  await expect(
    api.addPhotos(tenant, id, {
      photos: [],
      afterRecord: { title: "", description: "" },
    }),
  ).rejects.toMatchObject({ status: 400 })
  const more = await api.addPhotos(tenant, id, {
    photos: ["data:image/png;base64,BB"],
  })
  expect(more.repair.photos).toContain("data:image/png;base64,BB")
  // 처음 기록하지 않은 쪽은 수리 전 사진을 더할 수 없어요
  await expect(
    api.addPhotos(landlord, id, { photos: [photo] }),
  ).rejects.toMatchObject({
    status: 400,
  })
})

test("원인 설명: 근거 종류가 필요하고, 고치면 새 설명이 되며, 상대만 동의·이견을 단다(REP-8)", async () => {
  const { api, id } = setup()
  await expect(
    api.addCauseNote(tenant, id, { basis: " ", text: "윗집 배관", photos: [] }),
  ).rejects.toMatchObject({ status: 400 })
  const first = await api.addCauseNote(tenant, id, {
    basis: "내 추측",
    text: "윗집 배관",
    photos: [],
  })
  const c1 = first.repair.causes!.at(-1)!
  const second = await api.addCauseNote(tenant, id, {
    basis: "업체·전문가 소견",
    company: "○○설비",
    text: "윗집 배관 누수",
    photos: [],
  })
  const c2 = second.repair.causes!.at(-1)!
  expect(second.repair.causes!.find((c) => c.id === c1.id)!.supersededBy).toBe(
    c2.id,
  )
  await expect(
    api.respondCauseNote(tenant, id, c2.id, { answer: "ACK" }),
  ).rejects.toMatchObject({ status: 400 })
  await expect(
    api.respondCauseNote(landlord, id, c2.id, { answer: "DISPUTE" }),
  ).rejects.toMatchObject({ status: 400 })
  const agreed = await api.respondCauseNote(landlord, id, c2.id, {
    answer: "ACK",
  })
  expect(
    agreed.repair.causes!.find((c) => c.id === c2.id)!.responses,
  ).toHaveLength(1)
})
