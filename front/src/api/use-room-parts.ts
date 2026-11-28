import { useCallback, useContext } from "react"
import type { NewRoomPart, RoomPart } from "@/lib/room-parts"
import { SessionContext } from "./context"
import { useApi, useApiQuery } from "./hooks"

// 호실 부위 목록(06 4-3)을 받아 두고, 부위를 더하면 다시 받아요. 수리 건·점검 화면이 같이 써요
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
  const add = useCallback(
    async (body: NewRoomPart): Promise<RoomPart> => {
      if (!session) throw new Error("로그인한 뒤 부위를 더할 수 있어요.")
      const part = await api.parts.add(session, roomId, body)
      reload()
      return part
    },
    [api, session, roomId, reload],
  )
  return {
    parts: query.data?.parts,
    loading: query.loading,
    error: query.error,
    reload,
    add,
  }
}
