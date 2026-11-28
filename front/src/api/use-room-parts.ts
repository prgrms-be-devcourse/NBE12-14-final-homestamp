import { useCallback, useContext, useEffect } from "react"
import type { NewRoomPart, RoomPart } from "@/lib/room-parts"
import { SessionContext } from "./context"
import { useApi, useApiQuery } from "./hooks"

// 같은 호실의 부위 목록을 보는 곳(부위 관리 카드, 하자 등록·점검의 부위 칸)끼리 바뀐 것을 알려요.
// 한 곳에서 더하거나 숨기거나 켜고 끄면 그 호실을 보는 모든 곳이 목록을 다시 받아요(#456)
const watchers = new Map<string, Set<() => void>>()
const notifyChanged = (roomId: string) =>
  watchers.get(roomId)?.forEach((reload) => reload())

// 호실 부위 목록(06 4-3)을 받아 두고, 부위를 바꾸면 다시 받아요. 수리 건·점검 화면과 부위 관리 카드가 같이 써요
export function useRoomParts(roomId: string) {
  const api = useApi()
  const session = useContext(SessionContext)
  const query = useApiQuery(
    (a) =>
      session
        ? a.parts.list(session, roomId)
        : Promise.reject(new Error("로그인한 뒤 부위를 고를 수 있어요.")),
    [roomId, session],
  )
  const { reload } = query
  useEffect(() => {
    const set = watchers.get(roomId) ?? new Set()
    set.add(reload)
    watchers.set(roomId, set)
    return () => {
      set.delete(reload)
      if (!set.size) watchers.delete(roomId)
    }
  }, [roomId, reload])
  // 바꾸는 요청을 보내고, 성공하든 실패하든 목록을 다시 받아요.
  // 실패(INVALID_STATE 등)는 상대가 먼저 바꿨을 수 있어 지금 상태로 다시 그려야 해요
  const run = useCallback(
    async (send: (s: NonNullable<typeof session>) => Promise<RoomPart>) => {
      if (!session) throw new Error("로그인한 뒤 부위를 바꿀 수 있어요.")
      try {
        return await send(session)
      } finally {
        notifyChanged(roomId)
      }
    },
    [session, roomId],
  )
  const add = useCallback(
    (body: NewRoomPart) => run((s) => api.parts.add(s, roomId, body)),
    [api, roomId, run],
  )
  const hide = useCallback(
    (partId: string) => run((s) => api.parts.hide(s, roomId, partId)),
    [api, roomId, run],
  )
  const show = useCallback(
    (partId: string) => run((s) => api.parts.show(s, roomId, partId)),
    [api, roomId, run],
  )
  const toggle = useCallback(
    (partId: string, enabled: boolean) =>
      run((s) => api.parts.toggle(s, roomId, partId, enabled)),
    [api, roomId, run],
  )
  return {
    parts: query.data?.parts,
    asOf: query.data?.asOf ?? null,
    loading: query.loading,
    error: query.error,
    reload,
    add,
    hide,
    show,
    toggle,
  }
}
