import { REGION_QUERY_MAX } from "@/lib/region"
import { ApiError } from "../errors"
import type { Region, RegionOption } from "../types"

// 서버 없이 개발할 때 쓰는 법정동 코드 몇 줄. 값은 백엔드 V2__seed_region.sql(법정동코드 전체자료)에서 그대로 옮겼어요.
// 세종특별자치시처럼 시·군·구가 없는 곳은 sigungu가 null이에요.
export const MOCK_REGIONS: Region[] = [
  { code: "1111010100", sido: "서울특별시", sigungu: "종로구", dong: "청운동" },
  { code: "1111010400", sido: "서울특별시", sigungu: "종로구", dong: "효자동" },
  { code: "1144010100", sido: "서울특별시", sigungu: "마포구", dong: "아현동" },
  { code: "1144010200", sido: "서울특별시", sigungu: "마포구", dong: "공덕동" },
  { code: "1144012000", sido: "서울특별시", sigungu: "마포구", dong: "서교동" },
  { code: "1144012200", sido: "서울특별시", sigungu: "마포구", dong: "합정동" },
  { code: "1144012300", sido: "서울특별시", sigungu: "마포구", dong: "망원동" },
  { code: "1144012400", sido: "서울특별시", sigungu: "마포구", dong: "연남동" },
  { code: "1144012600", sido: "서울특별시", sigungu: "마포구", dong: "중동" },
  { code: "1144012700", sido: "서울특별시", sigungu: "마포구", dong: "상암동" },
  { code: "2635010500", sido: "부산광역시", sigungu: "해운대구", dong: "우동" },
  { code: "2635010600", sido: "부산광역시", sigungu: "해운대구", dong: "중동" },
  { code: "2635010700", sido: "부산광역시", sigungu: "해운대구", dong: "좌동" },
  { code: "3611010100", sido: "세종특별자치시", sigungu: null, dong: "반곡동" },
  { code: "3611011000", sido: "세종특별자치시", sigungu: null, dong: "어진동" },
  { code: "3611011100", sido: "세종특별자치시", sigungu: null, dong: "종촌동" },
  { code: "3611011400", sido: "세종특별자치시", sigungu: null, dong: "도담동" },
]

const SEARCH_LIMIT = 20
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/

// GET /api/regions/search?q=: 서버와 같은 규칙. 앞뒤 공백을 뺀 1~30자(제어 문자 없이), 동 이름 앞부분 일치, 코드 순 최대 20개
export function searchMockRegions(q: string): Region[] {
  const k = q.trim()
  if (!k || k.length > REGION_QUERY_MAX || CONTROL.test(k))
    throw new ApiError({
      status: 400,
      code: "VALIDATION_FAILED",
      detail: "동 이름을 1~30자로 적어 주세요.",
    })
  return MOCK_REGIONS.filter((r) => r.dong.startsWith(k))
    .sort((a, b) => a.code.localeCompare(b.code))
    .slice(0, SEARCH_LIMIT)
}

// 서버(RegionService)와 같이 ASCII 숫자 2자리(시·도)·5자리(시·군·구)만 받아요
const SIDO_CODE = /^[0-9]{2}$/
const SIGUNGU_CODE = /^[0-9]{5}$/

// GET /api/regions?parent=: 없으면 시·도, 2자리면 시·군·구, 5자리면 읍·면·동(코드 순). 없는 코드면 빈 목록.
// parent가 있는데 숫자 2·5자리가 아니면(빈 글자 포함) 서버처럼 VALIDATION_FAILED예요.
// 세종은 parent=36에 이름이 null인 시·군·구 36110 하나를 줘요(06 4-3, decisions 2026-11-24)
export function listMockRegions(parent?: string): RegionOption[] {
  const level =
    parent === undefined
      ? "sido"
      : SIDO_CODE.test(parent)
        ? "sigungu"
        : SIGUNGU_CODE.test(parent)
          ? "dong"
          : null
  if (!level)
    throw new ApiError({
      status: 400,
      code: "VALIDATION_FAILED",
      detail: "입력한 내용을 확인해 주세요",
    })
  const seen = new Map<string, string | null>()
  for (const r of MOCK_REGIONS) {
    if (level === "sido") seen.set(r.code.slice(0, 2), r.sido)
    else if (!r.code.startsWith(parent!)) continue
    else if (level === "sigungu") seen.set(r.code.slice(0, 5), r.sigungu)
    else seen.set(r.code, r.dong)
  }
  return [...seen]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([code, name]) => ({ code, name }))
}
