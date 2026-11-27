import { useMemo, type ReactNode } from "react"
import type { HomeStampApi } from "./types"
import { ApiContext, SessionContext } from "./context"

export function ApiProvider({
  api,
  accountId,
  children,
}: {
  api: HomeStampApi
  // 로그인한 계정. 없으면(링크로 보는 사람) 계정이 필요한 조회는 하지 않아요
  accountId?: string
  children: ReactNode
}) {
  const session = useMemo(() => (accountId ? { accountId } : null), [accountId])
  return (
    <ApiContext.Provider value={api}>
      <SessionContext.Provider value={session}>
        {children}
      </SessionContext.Provider>
    </ApiContext.Provider>
  )
}
