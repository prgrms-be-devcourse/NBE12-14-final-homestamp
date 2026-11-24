import { useCallback, useContext, useEffect, useState } from "react"
import type { HomeStampApi } from "./types"
import { ApiContext } from "./context"
import { errorMessage } from "./errors"

export function useApi() {
  const api = useContext(ApiContext)
  if (!api) throw new Error("ApiProvider 안에서만 쓸 수 있어요.")
  return api
}

// 조회 공통 훅: 불러오는 중·오류·다시 불러오기. deps가 바뀌면 다시 불러옵니다.
export function useApiQuery<T>(
  load: (api: HomeStampApi) => Promise<T>,
  deps: unknown[],
) {
  const api = useApi()
  const [state, setState] = useState<{
    data?: T
    error?: string
    loading: boolean
  }>({ loading: true })
  const [tick, setTick] = useState(0)
  // 다시 불러올 조건이 바뀌면 그린 직후가 아니라 그리는 중에 '불러오는 중'으로 바꿔요(effect 안 setState 대신).
  const inputs = [api, tick, ...deps]
  const [prev, setPrev] = useState(inputs)
  if (
    prev.length !== inputs.length ||
    prev.some((v, i) => !Object.is(v, inputs[i]))
  ) {
    setPrev(inputs)
    setState((s) => ({ ...s, loading: true, error: undefined }))
  }
  useEffect(() => {
    let alive = true
    load(api).then(
      (data) => {
        if (alive) setState({ data, loading: false })
      },
      (e) => {
        if (alive) setState({ error: errorMessage(e), loading: false })
      },
    )
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, tick, ...deps])
  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { ...state, reload }
}
