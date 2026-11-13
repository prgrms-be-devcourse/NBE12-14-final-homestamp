import type { FactCorrection } from "@/data/demo"

type C = FactCorrection<string | number>
// 한 번의 [고치기]로 남긴 칸별 고친 내용을 한 요청으로 묶어요(같은 사람·같은 시각·같은 이유).
// 확인·거절은 칸마다 따로 처리하는 기존 규칙을 그대로 두고, 화면에서만 '고치기 요청 1건 · 변경 항목 3개'처럼 보여 줘요.
export interface CorrectionGroup {
  key: string
  by: C["by"]
  at: string
  reason: string
  items: C[]
}
export function groupCorrections(list: C[]): CorrectionGroup[] {
  const groups: CorrectionGroup[] = []
  for (const c of list) {
    const key = `${c.by}|${c.at}|${c.reason}`
    const g = groups.find((x) => x.key === key)
    if (g) g.items.push(c)
    else groups.push({ key, by: c.by, at: c.at, reason: c.reason, items: [c] })
  }
  return groups
}
// 요청 하나의 상태: 칸이 하나라도 기다리면 '상대 확인 전', 모두 끝났으면 칸별 결과를 모아 한 줄로
export function groupStatus(g: CorrectionGroup) {
  const s = g.items.map((c) => c.status)
  if (s.includes("확인 대기"))
    return s.every((x) => x === "확인 대기")
      ? "상대 확인 전"
      : "일부 응답 · 상대 확인 전"
  if (s.every((x) => x === "반영")) return "반영됨"
  if (s.every((x) => x === "거절")) return "거절 · 지금 기록 유지"
  if (s.every((x) => x === "철회")) return "거둠 · 지금 기록 유지"
  if (s.every((x) => x === "대체됨")) return "새로 고쳐서 바뀜"
  const n = (v: C["status"]) => s.filter((x) => x === v).length
  return [
    n("반영") && `반영 ${n("반영")}`,
    n("거절") && `거절 ${n("거절")}`,
    n("철회") && `거둠 ${n("철회")}`,
    n("대체됨") && `대체 ${n("대체됨")}`,
  ]
    .filter(Boolean)
    .join(" · ")
}

// 기록당 확인 요청은 하나(상태와 규칙 G, REP-7h): 확인을 기다리는 칸들을 요청 하나로 묶어 보여 줘요.
// 칸마다 기다리는 고친 내용은 하나뿐이에요(같은 칸을 다시 고치면 이전 것은 '대체됨', 저장 규칙 3).
export function pendingRequest(list: C[]): CorrectionGroup | undefined {
  const items = list.filter((c) => c.status === "확인 대기")
  if (!items.length) return undefined
  const reasons = Array.from(new Set(items.map((c) => c.reason)))
  const last = items[items.length - 1]
  return {
    key: "pending",
    by: last.by,
    at: last.at,
    reason: reasons.length === 1 ? reasons[0] : "",
    items,
  }
}
// 칸별로 지금 확인을 기다리는 값. 다시 고치기 창을 열 때 미리 채워요.
export const pendingValue = (list: C[] | undefined, field: string) =>
  list?.filter((c) => c.status === "확인 대기" && c.field === field).at(-1)?.to
