import { describe, expect, test } from "vitest"
import { ApiError, errorMessage } from "../src/api/errors"
import { exifTakenAt } from "../src/lib/photo-taken-at"

// 촬영 시각 EXIF가 든 가장 작은 JPEG(빅 엔디언). DateTimeOriginal(0x9003)과 OffsetTimeOriginal(0x9011)
function jpegWithExif(when: string, offset?: string) {
  const tiff: number[] = []
  const u16 = (n: number) => tiff.push((n >> 8) & 255, n & 255)
  const u32 = (n: number) => {
    u16((n >>> 16) & 0xffff)
    u16(n & 0xffff)
  }
  const entry = (tag: number, type: number, count: number, value: number) => {
    u16(tag)
    u16(type)
    u32(count)
    u32(value)
  }
  const ascii = (s: string) => [...s, "\0"].map((c) => c.charCodeAt(0))
  const whenBytes = ascii(when)
  const offsetBytes = offset ? ascii(offset) : []
  const exifIfd = 8 + 2 + 12 + 4
  const exifEntries = offset ? 2 : 1
  const data = exifIfd + 2 + exifEntries * 12 + 4
  tiff.push(0x4d, 0x4d)
  u16(42)
  u32(8)
  // IFD0: Exif IFD 포인터
  u16(1)
  entry(0x8769, 4, 1, exifIfd)
  u32(0)
  // Exif IFD
  u16(exifEntries)
  entry(0x9003, 2, whenBytes.length, data)
  if (offset) entry(0x9011, 2, offsetBytes.length, data + whenBytes.length)
  u32(0)
  tiff.push(...whenBytes, ...offsetBytes)
  const app1 = [0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff]
  const size = app1.length + 2
  return new Uint8Array([
    0xff,
    0xd8,
    0xff,
    0xe1,
    (size >> 8) & 255,
    size & 255,
    ...app1,
    0xff,
    0xd9,
  ]).buffer
}

describe("촬영 시각 읽기(06 PHO-1f, #366)", () => {
  test("시간대가 있으면 그 시간대로 UTC 시각을 만든다", () => {
    expect(exifTakenAt(jpegWithExif("2026:11:20 09:30:00", "+09:00"))).toBe(
      "2026-11-20T00:30:00.000Z",
    )
  })

  test("시간대가 없으면 이 기기의 시간대로 본다", () => {
    expect(exifTakenAt(jpegWithExif("2026:11:20 09:30:00"))).toBe(
      new Date(2026, 10, 20, 9, 30, 0).toISOString(),
    )
  })

  test("JPEG가 아니거나 EXIF가 없으면 null", () => {
    expect(exifTakenAt(new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer)).toBe(
      null,
    )
    expect(
      exifTakenAt(new Uint8Array([0xff, 0xd8, 0xff, 0xda, 0, 2]).buffer),
    ).toBe(null)
  })
})

test("업로드 표·사진 검사 오류는 몇 번째 사진인지 함께 알린다(PHO-1c)", () => {
  expect(
    errorMessage(
      new ApiError({
        status: 400,
        code: "UPLOAD_TICKET_EXPIRED",
        detail: "사진 올린 지 오래됐어요.",
        photoIndex: 1,
      }),
    ),
  ).toBe(
    "2번째 사진 · 사진 올린 지 오래됐어요. 그 사진만 빼고 다시 골라 주세요.",
  )
  expect(errorMessage(new ApiError({ status: 400, detail: "다른 오류" }))).toBe(
    "다른 오류",
  )
})
