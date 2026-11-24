import { describe, expect, it } from "vitest"
import { listMockRegions, searchMockRegions } from "../src/api/mock/regions"
import { regionName } from "../src/lib/region"

describe("법정동 고르기(06 4-3, decisions 2026-11-13)", () => {
  it("동 이름으로 찾으면 시·구가 달라도 모두 보여요", () => {
    const found = searchMockRegions("중동").map(regionName)
    expect(found).toEqual([
      "서울특별시 마포구 중동",
      "부산광역시 해운대구 중동",
    ])
  })
  it("단계 선택: 없으면 시·도, 2자리면 시·군·구, 5자리면 읍·면·동", () => {
    expect(listMockRegions().map((r) => r.code)).toEqual(["11", "26", "36"])
    expect(listMockRegions("11").map((r) => r.name)).toEqual([
      "종로구",
      "마포구",
    ])
    expect(listMockRegions("11440")[0]).toEqual({
      code: "1144010100",
      name: "아현동",
    })
  })
  it("시·군·구가 없는 세종은 시·도 이름과 동만 보여요", () => {
    expect(listMockRegions("36")).toEqual([
      { code: "36110", name: "세종특별자치시" },
    ])
    expect(regionName(searchMockRegions("어진")[0])).toBe(
      "세종특별자치시 어진동",
    )
  })
})
