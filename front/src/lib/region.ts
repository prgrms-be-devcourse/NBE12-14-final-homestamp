import type { Region } from "@/api/types"

// 화면에 보일 주소: "서울특별시 마포구 연남동"(시·군·구가 없는 세종은 "세종특별자치시 어진동")
export const regionName = (r: Pick<Region, "sido" | "sigungu" | "dong">) =>
  [r.sido, r.sigungu, r.dong].filter(Boolean).join(" ")
