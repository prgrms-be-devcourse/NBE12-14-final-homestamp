import type { Region, RegionOption } from "@/api/types"

// 화면에 보일 주소: "서울특별시 마포구 연남동"(시·군·구가 없는 세종은 "세종특별자치시 어진동")
export const regionName = (r: Pick<Region, "sido" | "sigungu" | "dong">) =>
  [r.sido, r.sigungu, r.dong].filter(Boolean).join(" ")

// 동 이름 찾기에 적을 수 있는 최대 길이. 서버(RegionService)와 같이 동 이름 칸(varchar(30))에 맞춰요(06 4-3).
export const REGION_QUERY_MAX = 30

// 시·군·구 목록이 이름 없는 한 칸뿐이면(세종, 06 4-3) 그 칸을 저절로 골라 단계를 건너뛰어요
export const skippedSigungu = (options: RegionOption[]) =>
  options.length === 1 && options[0].name === null ? options[0] : undefined

// 단계 선택으로 고른 세 칸을 찾기 결과와 같은 모양으로 바꿔요. 이름 없는 시·군·구는 sigungu null이에요.
export function steppedRegion(
  sido: RegionOption,
  sigungu: RegionOption,
  dong: RegionOption,
): Region | null {
  if (!sido.name || !dong.name) return null
  return {
    code: dong.code,
    sido: sido.name,
    sigungu: sigungu.name,
    dong: dong.name,
  }
}
