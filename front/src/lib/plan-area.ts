// 집 평면도(components/common/house-plan)의 구역. 실제 평면이 아니라 기본 구역(V7 PART_ZONES)을 늘 같은 자리에 둬요.
export type PlanArea = "방" | "주방" | "욕실" | "현관" | "기타"

// 수리 건의 zone("구역 · 부위")을 그림의 구역으로. 침실·거실은 방에, 설비·가전과 모르는 구역은 기타에 둬요.
export function planArea(zone: string): PlanArea {
  const head = zone.split(" · ")[0]?.trim() ?? ""
  if (/^(방|침실|거실|작은방|안방)/.test(head)) return "방"
  if (/^주방/.test(head)) return "주방"
  if (/^(욕실|화장실)/.test(head)) return "욕실"
  if (/^현관/.test(head)) return "현관"
  return "기타"
}
