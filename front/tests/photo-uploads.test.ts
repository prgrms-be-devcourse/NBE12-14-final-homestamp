import { describe, expect, test } from "vitest"
import { isConnected } from "../src/api/connected"
import { ApiError } from "../src/api/errors"
import type { HttpRequest } from "../src/api/http-client"
import {
  createHttpPhotoUploads,
  isFakeStorage,
  postToStorage,
  sha256Base64,
  type PresignedPostBody,
} from "../src/api/photo-uploads"

// 사진 올리기(06 4-5 upload-tickets, PHO-1, #365)
const prepared = (n: number) => ({
  original: new Blob([`o${n}`.repeat(10)]),
  thumb: new Blob([`t${n}`]),
  originalSha256: `osha${n}`,
  thumbSha256: `tsha${n}`,
})
const post = (url: string): PresignedPostBody => ({
  url,
  fields: { key: "pending/a" },
  expiresAt: "2026-11-27T05:05:00Z",
})

describe("사진 올리기", () => {
  test("사진마다 크기·지문을 보내 표를 받고, 썸네일 먼저 올린 뒤 요청 순서대로 표를 돌려준다", async () => {
    const sent: { method: string; path: string; body?: unknown }[] = []
    const request = (async (
      method: string,
      path: string,
      o?: { body?: unknown },
    ) => {
      sent.push({ method, path, body: o?.body })
      // 서버가 순서를 바꿔 보내도 index로 맞춰요
      return {
        slots: [1, 0].map((index) => ({
          index,
          ticket: `ticket-${index}`,
          ticketExpiresAt: "2026-11-27T17:00:00Z",
          original: post(`https://s3/original-${index}`),
          thumb: post(`https://s3/thumb-${index}`),
        })),
      }
    }) as HttpRequest
    const uploaded: string[] = []
    const api = createHttpPhotoUploads(request, {
      prepare: async (p) => prepared(Number(p.slice(-1))),
      send: async (p) => {
        uploaded.push(p.url)
      },
    })
    expect(await api.upload("r1", ["data:0", "data:1"])).toEqual([
      "ticket-0",
      "ticket-1",
    ])
    expect(sent).toEqual([
      {
        method: "POST",
        path: "/api/rooms/r1/upload-tickets",
        body: {
          files: [0, 1].map((n) => ({
            originalBytes: 20,
            thumbBytes: 2,
            originalSha256: `osha${n}`,
            thumbSha256: `tsha${n}`,
            deviceTakenAt: null,
          })),
        },
      },
    ])
    for (const i of [0, 1])
      expect(uploaded.indexOf(`https://s3/thumb-${i}`)).toBeLessThan(
        uploaded.indexOf(`https://s3/original-${i}`),
      )
  })

  test("사진이 없으면 서버를 부르지 않고, 10장을 넘으면 보내지 않는다", async () => {
    let called = 0
    const api = createHttpPhotoUploads((async () => {
      called++
      return { slots: [] }
    }) as HttpRequest)
    expect(await api.upload("r1", [])).toEqual([])
    await expect(
      api.upload(
        "r1",
        Array.from({ length: 11 }, () => "data:"),
      ),
    ).rejects.toMatchObject({ code: "VALIDATION_FAILED" })
    expect(called).toBe(0)
  })

  test("서버 거절(UPLOAD_LIMIT 등)은 그대로 알린다", async () => {
    const api = createHttpPhotoUploads(
      (async () => {
        throw new ApiError({ status: 429, code: "UPLOAD_LIMIT" })
      }) as HttpRequest,
      { prepare: async () => prepared(0) },
    )
    await expect(api.upload("r1", ["data:0"])).rejects.toMatchObject({
      code: "UPLOAD_LIMIT",
    })
  })

  test("로컬 가짜 업로드 폼(.invalid)이면 올리지 않고, 실제 주소가 거절하면 올리기 실패로 알린다", async () => {
    expect(isFakeStorage("https://fake-photo-storage.invalid/")).toBe(true)
    expect(
      isFakeStorage("https://bucket.s3.ap-northeast-2.amazonaws.com/"),
    ).toBe(false)
    const calls: string[] = []
    const realFetch = globalThis.fetch
    globalThis.fetch = (async (url: string) => {
      calls.push(url)
      return new Response(null, { status: 403 })
    }) as typeof fetch
    try {
      await postToStorage(
        post("https://fake-photo-storage.invalid/"),
        new Blob(["x"]),
      )
      expect(calls).toEqual([])
      await expect(
        postToStorage(post("https://s3.example/"), new Blob(["x"])),
      ).rejects.toMatchObject({ code: "PHOTO_UPLOAD_FAILED" })
    } finally {
      globalThis.fetch = realFetch
    }
  })

  test("지문은 S3 체크섬 모양(SHA-256 base64 44자)이다", async () => {
    // "abc"의 SHA-256
    expect(await sha256Base64(new Blob(["abc"]))).toBe(
      "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
    )
  })

  test("업로드 표 받기는 서버로 보내는 API 목록에 있다", () => {
    expect(isConnected("POST", "/api/rooms/r1/upload-tickets")).toBe(true)
  })
})
