import { describe, expect, test, vi } from "vitest"
import { strFromU8, unzipSync } from "fflate"
import {
  createHttpDownloads,
  type PhotoUrlBody,
  type RecordPhotoBody,
  type RoomDownloadBody,
} from "../src/api/downloads"
import { endpoints } from "../src/api/endpoints"
import { isConnected } from "../src/api/connected"
import type { HttpRequest } from "../src/api/http-client"
import {
  buildRoomZip,
  exportFileName,
  PHOTO_RETRIES,
  sha256Hex,
} from "../src/lib/room-export"

// 내려받기(S-19, DL-1): 서버가 준 목록으로 화면이 ZIP을 만들어요
const NOW = Date.parse("2026-11-27T01:00:00Z")
const bytesOf = (id: string) => new TextEncoder().encode(`photo-${id}`)
const photo = async (
  id: string,
  patch: Partial<RecordPhotoBody> = {},
): Promise<RecordPhotoBody> => ({
  photoId: id,
  file: `사진/${id}.jpg`,
  sha256: await sha256Hex(bytesOf(id)),
  uploadedBy: "TENANT",
  target: { kind: "REPAIR_CASE", id: "c1" },
  createdAt: "2026-11-26T00:00:00Z",
  erased: null,
  ...patch,
})
const download = async (
  batches: string[][],
  extra: RecordPhotoBody[] = [],
): Promise<RoomDownloadBody> => ({
  record: {
    format: "homestamp-record/1",
    exportedAt: "2026-11-27T01:00:00Z",
    notice: "서비스에 남긴 기록의 사본",
    mySide: "TENANT",
    roomStatus: "LIVING",
    actions: [{ id: "a1", type: "REGISTER_REPAIR" }],
    photos: [
      ...(await Promise.all(batches.flat().map((id) => photo(id)))),
      ...extra,
    ],
  },
  photoBatches: batches,
  photoCount: batches.flat().length,
})
const url = (id: string, patch: Partial<PhotoUrlBody> = {}): PhotoUrlBody => ({
  photoId: id,
  url: `https://s3.example/${id}?v=1`,
  expiresAt: "2026-11-27T01:05:00Z",
  erased: null,
  ...patch,
})
const unzip = (zip: Uint8Array) => unzipSync(zip)

describe("호실 기록 ZIP 만들기(DL-1)", () => {
  test("기록.json은 서버가 준 record 그대로, 사진은 record의 file 경로에 원본으로 넣는다", async () => {
    const d = await download([["p1", "p2"]])
    const originalUrls = vi.fn(async (ids: readonly string[]) =>
      ids.map((id) => url(id)),
    )
    const progress: number[] = []
    const r = await buildRoomZip(d, {
      api: { get: vi.fn(), originalUrls },
      fetchPhoto: async (u) => bytesOf(new URL(u).pathname.slice(1)),
      now: () => NOW,
      onProgress: (p) => progress.push(p.done),
    })
    const files = unzip(r.zip)
    expect(JSON.parse(strFromU8(files["기록.json"]))).toEqual(d.record)
    expect(strFromU8(files["사진/p1.jpg"])).toBe("photo-p1")
    expect(Object.keys(files).sort()).toEqual([
      "기록.json",
      "사진/p1.jpg",
      "사진/p2.jpg",
    ])
    expect(r).toMatchObject({ erased: [], missing: [], mismatched: [] })
    expect(progress.at(-1)).toBe(2)
  })

  test("주소가 끝났거나 받기에 실패한 사진은 그 묶음의 그 사진만 주소를 다시 받는다(DL-1b)", async () => {
    const d = await download([["p1", "p2"], ["p3"]])
    const asked: string[][] = []
    let p2Fails = 1
    const r = await buildRoomZip(d, {
      api: {
        get: vi.fn(),
        originalUrls: async (ids) => {
          asked.push([...ids])
          // 첫 번째 주소는 이미 끝남(p1)
          return ids.map((id) =>
            id === "p1" && asked.length === 1
              ? url(id, { expiresAt: "2026-11-27T00:59:00Z" })
              : url(id),
          )
        },
      },
      fetchPhoto: async (u) => {
        const id = new URL(u).pathname.slice(1)
        if (id === "p2" && p2Fails-- > 0) throw new Error("403")
        return bytesOf(id)
      },
      now: () => NOW,
    })
    expect(asked).toEqual([["p1", "p2"], ["p1", "p2"], ["p3"]])
    expect(Object.keys(unzip(r.zip))).toHaveLength(4)
    expect(r.missing).toEqual([])
  })

  test("다시 받아도 받지 못한 사진은 빠진 사진으로 알리고, 나머지는 저장한다", async () => {
    const d = await download([["p1", "p2"]])
    const originalUrls = vi.fn(async (ids: readonly string[]) =>
      ids.map((id) => url(id)),
    )
    const r = await buildRoomZip(d, {
      api: { get: vi.fn(), originalUrls },
      fetchPhoto: async (u) => {
        const id = new URL(u).pathname.slice(1)
        if (id === "p2") throw new Error("network")
        return bytesOf(id)
      },
      now: () => NOW,
    })
    expect(r.missing).toEqual(["p2"])
    expect(originalUrls).toHaveBeenCalledTimes(1 + PHOTO_RETRIES)
    expect(Object.keys(unzip(r.zip)).sort()).toEqual([
      "기록.json",
      "사진/p1.jpg",
    ])
  })

  test("받는 도중 지워진 사진(erased)은 파일 없이 건너뛴다", async () => {
    const d = await download([["p1", "p2"]])
    const fetchPhoto = vi.fn(async (u: string) =>
      bytesOf(new URL(u).pathname.slice(1)),
    )
    const r = await buildRoomZip(d, {
      api: {
        get: vi.fn(),
        originalUrls: async (ids) =>
          ids.map((id) =>
            id === "p2"
              ? url(id, {
                  url: null,
                  expiresAt: null,
                  erased: { side: "TENANT", at: "2026-11-27T00:30:00Z" },
                })
              : url(id),
          ),
      },
      fetchPhoto,
      now: () => NOW,
    })
    expect(r.erased).toEqual(["p2"])
    expect(fetchPhoto).toHaveBeenCalledTimes(1)
    expect(unzip(r.zip)["사진/p2.jpg"]).toBeUndefined()
  })

  test("받은 원본의 SHA-256이 기록.json의 지문값과 다르면 알린다(DL-1a)", async () => {
    const d = await download([["p1"]])
    const r = await buildRoomZip(d, {
      api: {
        get: vi.fn(),
        originalUrls: async (ids) => ids.map((id) => url(id)),
      },
      fetchPhoto: async () => new TextEncoder().encode("바뀐 바이트"),
      now: () => NOW,
    })
    expect(r.mismatched).toEqual(["p1"])
  })

  test("사진이 없는 호실은 기록.json만 담고 사진 주소를 받지 않는다", async () => {
    const d = await download([])
    const originalUrls = vi.fn()
    const r = await buildRoomZip(d, {
      api: { get: vi.fn(), originalUrls },
      now: () => NOW,
    })
    expect(Object.keys(unzip(r.zip))).toEqual(["기록.json"])
    expect(originalUrls).not.toHaveBeenCalled()
  })
})

test("지문값은 shasum -a 256과 같은 64자 소문자 hex다", async () => {
  expect(await sha256Hex(new TextEncoder().encode("abc"))).toBe(
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  )
})

test("파일 이름은 호실 이름과 한국 날짜, 파일 이름에 못 쓰는 글자는 뺀다", () => {
  expect(exportFileName("302호/연남", new Date("2026-11-26T16:00:00Z"))).toBe(
    "홈스탬프-302호연남-20261127.zip",
  )
})

test("HTTP: 목록을 받고, 원본 주소는 ORIGINAL로 묶음을 보낸다(두 API 연결)", async () => {
  const calls: { method: string; path: string; body?: unknown }[] = []
  const request = (async (
    method: string,
    path: string,
    opts?: { body?: unknown },
  ) => {
    calls.push({ method, path, body: opts?.body })
    if (path === endpoints.photoViewUrls) return { items: [url("p1")] }
    return { record: {}, photoBatches: [], photoCount: 0 }
  }) as HttpRequest
  const api = createHttpDownloads(request)
  await api.get("r1")
  expect(await api.originalUrls(["p1"])).toEqual([url("p1")])
  expect(calls).toEqual([
    { method: "GET", path: "/api/rooms/r1/download", body: undefined },
    {
      method: "POST",
      path: "/api/photos/view-urls",
      body: { photoIds: ["p1"], variant: "ORIGINAL" },
    },
  ])
  expect(isConnected("GET", "/api/rooms/r1/download")).toBe(true)
  expect(isConnected("POST", "/api/photos/view-urls")).toBe(true)
})
