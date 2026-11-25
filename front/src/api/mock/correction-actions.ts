import type { CorrectionAction } from "@/api/actions"
import type { FactCorrection, Role } from "@/data/demo"

type C = FactCorrection<string | number>

// 서버가 연결되기 전 Mock: 고친 내용마다의 availableActions를 서버 대신 계산해요(수리 건·지불·점검 항목 공통).
// list는 같은 기록의 고친 내용 전체예요. 같은 칸을 뒤에 다시 고쳤으면 지난 것은 다시 요청하지 않아요.
export function correctionAvailableActions(
  c: C,
  role: Role,
  list: readonly C[],
  viewer: { guest?: boolean; readOnly?: boolean } = {},
): CorrectionAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const mine = c.by === role
  if (c.status === "확인 대기") return mine ? ["withdraw"] : ["respond"]
  const latest = !list
    .slice(list.indexOf(c) + 1)
    .some((x) => x.field === c.field)
  if (c.status === "거절" && mine && latest) return ["resubmit"]
  return []
}
