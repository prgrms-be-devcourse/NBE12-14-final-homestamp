import type { Event, Repair } from "@/data/demo"
import { recordTimeMs } from "@/lib/repair-rules"

const DAY = 864e5
// 수리 건의 마지막 당사자 행동 시각(경과 기록 기준). 오래 멈춘 건(REP-6a, 14일) 판단에 써요.
export function lastActivity(r: Repair, events: Event[]) {
  // 서버가 준 값이 있으면 그대로 써요(보이는 범위까지 서버가 거른 값, 06 4-6). 목업은 경과 기록으로 셉니다
  if (r.lastActionAt) {
    const at = Date.parse(r.lastActionAt)
    if (!Number.isNaN(at)) return at
  }
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
// 홈 "오래 멈춘 건"(06 4장 GET /api/me/home "처리 중·보류", REP-6a·6e): 14일(repair.stale-after) 넘게 당사자 행동이 없는
// 처리 중인 건과 하자가 보충 요청됨(보류)인 건. 종료 확인 대기(closeBy 있음)는 한쪽이 끝냈다고 하고 상대 답을
// 기다리는 중이라 멈춘 건이 아니에요(수리 목록 "N일째"와 같은 기준). 멈춘 까닭(처리 중 / 보충 요청됨)을 돌려주고, 아니면 undefined
export function staleReason(
  r: Repair,
  events: Event[],
): "처리 중" | "보충 요청됨" | undefined {
  const reason =
    r.status === "처리 완료"
      ? undefined
      : r.ack === "보충 요청"
        ? "보충 요청됨"
        : r.status === "처리 중" && !r.closeBy
          ? "처리 중"
          : undefined
  return reason && (daysSince(lastActivity(r, events)) ?? 0) > 14
    ? reason
    : undefined
}
