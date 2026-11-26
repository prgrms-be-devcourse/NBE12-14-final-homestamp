import type { ReactNode } from "react"
import type { HomeStampApi } from "./types"
import { ApiContext } from "./context"

export function ApiProvider({
  api,
  children,
}: {
  api: HomeStampApi
  children: ReactNode
}) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>
}
