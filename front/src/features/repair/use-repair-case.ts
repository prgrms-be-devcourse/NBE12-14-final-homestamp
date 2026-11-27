import { useCallback } from "react"
import { useApi, useApiQuery } from "@/api/hooks"
import { errorMessage, shouldReload } from "@/api/errors"
import type { RepairCasesApi, RepairCaseView } from "@/api/repair-cases"
import type { Session } from "@/api/types"

export type RepairCommand = (
  repairs: RepairCasesApi,
  session: Session,
) => Promise<unknown>

// 수리 건 하나를 API로 읽고(availableActions 포함), 행동을 API로 보내요.
// 행동이 끝나면 다시 불러오고, VERSION_CONFLICT·INVALID_STATE면 다시 불러온 뒤 한 줄 안내(06 5-0).
// refreshKey: 아직 API로 옮기지 않은 다른 도메인(지불·고치기 등)이 기록을 바꾸면 다시 불러오려는 값이에요(목업 동안만).
export function useRepairCase(
  id: string,
  session: Session | null,
  refreshKey?: unknown,
  // 이 화면 밖(공용 입력 창)에서 수리 건을 바꾼 뒤 다시 불러오려고 올리는 번호
  tick = 0,
  // 행동이 끝나면 이 호실도 다시 받아요. 경과(GET /api/rooms/{id}/timeline)는 호실을 받을 때 함께 와요(#359)
  roomId?: string,
) {
  const api = useApi()
  const query = useApiQuery<RepairCaseView | null>(
    (a) => (session ? a.repairCases.get(session, id) : Promise.resolve(null)),
    [id, session?.accountId, refreshKey, tick],
  )
  const { reload } = query
  const run = useCallback(
    async (call: RepairCommand): Promise<string | undefined> => {
      if (!session) return "로그인한 뒤에 할 수 있어요."
      try {
        await call(api.repairCases, session)
        reload()
        // 보내기는 이미 성공했어요. 호실 다시 받기가 실패해도 실패로 돌려주지 않아요(다음 받기 때 다시 받아요)
        if (roomId) api.records.loadRoom(session, roomId).catch(() => {})
        return undefined
      } catch (e) {
        if (shouldReload(e)) reload()
        return errorMessage(e)
      }
    },
    [api, session, reload, roomId],
  )
  return {
    view: query.data ?? undefined,
    loading: query.loading,
    // 실패 원인 그대로 돌려줘요. 화면이 404(남의 건·없는 건)를 일반 오류와 가르려면 상태 코드가 있어야 해요(#270 리뷰)
    error: query.failure,
    run,
  }
}
