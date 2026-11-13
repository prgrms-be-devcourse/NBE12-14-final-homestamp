import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { API_BASE_URL, API_MODE } from '@/config'
import type { HomeStampApi } from './types'
import { createHttpApi } from './http-api'
import { createHttpClient } from './http-client'
import { errorMessage } from './errors'
import { tokenStore } from './auth'

export function createApi(mock: () => HomeStampApi): HomeStampApi {
  return API_MODE === 'http' ? createHttpApi(createHttpClient({ baseUrl: API_BASE_URL, getToken: tokenStore.get })) : mock()
}
const ApiContext = createContext<HomeStampApi | null>(null)
export function ApiProvider({ api, children }: { api: HomeStampApi; children: ReactNode }) { return <ApiContext.Provider value={api}>{children}</ApiContext.Provider> }
export function useApi() { const api = useContext(ApiContext); if (!api) throw new Error('ApiProvider 안에서만 쓸 수 있어요.'); return api }

// 조회 공통 훅: 불러오는 중·오류·다시 불러오기. deps가 바뀌면 다시 불러옵니다.
export function useApiQuery<T>(load: (api: HomeStampApi) => Promise<T>, deps: unknown[]) {
  const api = useApi()
  const [state, setState] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true })
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let alive = true
    setState(s => ({ ...s, loading: true, error: undefined }))
    load(api).then(data => { if (alive) setState({ data, loading: false }) }, e => { if (alive) setState({ error: errorMessage(e), loading: false }) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, tick, ...deps])
  const reload = useCallback(() => setTick(t => t + 1), [])
  return { ...state, reload }
}
