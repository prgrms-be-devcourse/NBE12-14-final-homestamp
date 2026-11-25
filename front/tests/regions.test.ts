import { describe, expect, it } from "vitest"
import { listMockRegions, searchMockRegions } from "../src/api/mock/regions"
import {
  REGION_QUERY_MAX,
  regionName,
  skippedSigungu,
  steppedRegion,
} from "../src/lib/region"

describe("법정동 고르기(06 4-3, decisions 2026-11-13·2026-11-24)", () => {
  it("동 이름으로 찾으면 시·구가 달라도 모두 보여요(코드 순)", () => {
    const found = searchMockRegions("중동").map(regionName)
    expect(found).toEqual([
      "서울특별시 마포구 중동",
      "부산광역시 해운대구 중동",
    ])
  })
  it("찾기는 서버처럼 동 이름 앞부분만 맞춰요", () => {
    expect(searchMockRegions("연남").map((r) => r.dong)).toEqual(["연남동"])
    // "남동"은 연남동의 가운데라 찾지 않아요
    expect(searchMockRegions("남동")).toEqual([])
    expect(searchMockRegions("  연남  ")).toHaveLength(1)
  })
  it("찾는 말이 비었거나 30자를 넘거나 제어 문자가 있으면 VALIDATION_FAILED", () => {
    for (const q of [
      "",
      "   ",
      "가".repeat(REGION_QUERY_MAX + 1),
      "연\u0000남",
    ])
      expect(() => searchMockRegions(q)).toThrow(
        expect.objectContaining({ code: "VALIDATION_FAILED" }),
      )
    expect(searchMockRegions("가".repeat(REGION_QUERY_MAX))).toEqual([])
  })
  it("단계 선택: 없으면 시·도, 2자리면 시·군·구, 5자리면 읍·면·동(코드 순)", () => {
    expect(listMockRegions().map((r) => r.code)).toEqual(["11", "26", "36"])
    expect(listMockRegions("11")).toEqual([
      { code: "11110", name: "종로구" },
      { code: "11440", name: "마포구" },
    ])
    expect(listMockRegions("11440")[0]).toEqual({
      code: "1144010100",
      name: "아현동",
    })
    expect(listMockRegions("99")).toEqual([])
  })
  it("세종은 이름 없는 시·군·구 한 칸을 주고, 화면은 그 칸을 저절로 골라요", () => {
    const sigungus = listMockRegions("36")
    expect(sigungus).toEqual([{ code: "36110", name: null }])
    const skip = skippedSigungu(sigungus)
    expect(skip).toEqual({ code: "36110", name: null })
    expect(skippedSigungu(listMockRegions("11"))).toBeUndefined()

    const dong = listMockRegions("36110")[0]
    expect(dong).toEqual({ code: "3611010100", name: "반곡동" })
    const picked = steppedRegion(
      { code: "36", name: "세종특별자치시" },
      skip!,
      dong,
    )
    expect(picked).toEqual(searchMockRegions("반곡")[0])
    expect(regionName(picked!)).toBe("세종특별자치시 반곡동")
  })
  it("단계 선택으로 고른 서울 주소는 찾기 결과와 같아요", () => {
    expect(
      steppedRegion(
        { code: "11", name: "서울특별시" },
        { code: "11110", name: "종로구" },
        { code: "1111010100", name: "청운동" },
      ),
    ).toEqual(searchMockRegions("청운")[0])
  })
})
