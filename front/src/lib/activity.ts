import type { Event, Repair } from "@/data/demo"
import { recordTimeMs } from "@/lib/repair-rules"

const DAY = 864e5
// 수리 건의 마지막 당사자 행동 시각(경과 기록 기준). 오래 멈춘 건(REP-6a, 14일) 판단에 써요.
export function lastActivity(r: Repair, events: Event[]) {
  const times = events
    .filter(
      (e) =>
        e.target?.kind === "repair" &&
        e.target.id === r.id &&
        e.by !== "서버 자동",
    )
    .map((e) => recordTimeMs(e.at))
    .filter((t): t is number => t !== undefined)
  return times.length ? Math.max(...times) : recordTimeMs(r.date)
}
export const daysSince = (ms?: number) =>
  ms === undefined
    ? undefined
    : Math.max(1, Math.floor((Date.now() - ms) / DAY) + 1)
export const moneyLabel = (r: Repair) => r.proposal?.status || "미정"
