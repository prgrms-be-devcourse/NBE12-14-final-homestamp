import { ApiError, type ProblemDetail } from "./errors"
import { isConnected, notConnected } from "./connected"
import { endpoints } from "./endpoints"

// 백엔드 연결용 요청 함수(명세 4-0): JSON, Bearer 토큰, 상태를 바꾸는 요청엔 Idempotency-Key, 오류는 Problem Details.
export interface HttpClientOptions {
  baseUrl: string
  getToken?: () => string | undefined
  // 서버로 보내도 되는 요청인지(기본: api/connected.ts의 연결을 마친 API 목록)
  allow?: (method: "GET" | "POST", path: string) => boolean
  // 액세스 토큰이 만료돼(auth.access.ttl 15분) 401 LOGIN_REQUIRED가 오면 부르는 재발급(ACC-2).
  // 새 토큰을 받으면 원래 요청을 한 번만 다시 보내요. 재발급이 실패하면 그 오류를 그대로 던져요.
  refresh?: () => Promise<void>
  // 재발급한 토큰으로 다시 보낸 요청도 401 LOGIN_REQUIRED면(탈퇴한 회원의 남은 토큰 등) 부르는 세션 끝
  endSession?: (e: ApiError) => void
}
// 액세스 토큰이 없거나 맞지 않아 거절된 401(06 4-10 LOGIN_REQUIRED)
const isLoginRequired = (e: ApiError) =>
  e.status === 401 && e.code === "LOGIN_REQUIRED"
export type RequestOptions = {
  body?: unknown
  idempotent?: boolean
  signal?: AbortSignal
}
const networkError = () =>
  new ApiError({
    status: 0,
    code: "NETWORK",
    detail: "연결이 불안정해요. 잠시 뒤 다시 시도해 주세요.",
  })
async function toApiError(res: Response) {
  const problem = (await res.json().catch(() => ({}))) as Partial<ProblemDetail>
  const retry = Number(res.headers.get("Retry-After"))
  return new ApiError({
    status: res.status,
    ...problem,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  })
}
export function createHttpClient({
  baseUrl,
  getToken,
  allow = isConnected,
  refresh,
  endSession,
}: HttpClientOptions) {
  return async function request<T>(
    method: "GET" | "POST",
    path: string,
    opts: RequestOptions = {},
  ): Promise<T> {
    // 연결을 마치지 않은 API는 서버로 보내지 않아요(요청·응답 모양이 아직 서버와 다를 수 있어서)
    if (!allow(method, path)) throw notConnected()
    const headers: Record<string, string> = {
      Accept: "application/json, application/problem+json",
    }
    if (opts.body !== undefined) headers["Content-Type"] = "application/json"
    // 다시 보낼 때도 처음 요청과 같은 키를 써요(서버가 같은 요청으로 알아보게)
    if (method === "POST" && opts.idempotent !== false)
      headers["Idempotency-Key"] = crypto.randomUUID()
    const body = opts.body === undefined ? undefined : JSON.stringify(opts.body)
    const send = async (token: string | undefined) => {
      try {
        return await fetch(baseUrl + path, {
          method,
          headers: token
            ? { ...headers, Authorization: `Bearer ${token}` }
            : headers,
          body,
          credentials: "include",
          signal: opts.signal,
        })
      } catch {
        throw networkError()
      }
    }
    const token = getToken?.()
    let res = await send(token)
    if (!res.ok) {
      const error = await toApiError(res)
      // 토큰을 붙여 보냈는데 만료로 거절됐을 때만 재발급해요. 재발급 요청 자신은 다시 보내지 않아요.
      const expired =
        refresh && token && path !== endpoints.refresh && isLoginRequired(error)
      if (!expired) throw error
      // 이 요청이 기다리는 사이 다른 요청이 이미 재발급했으면(토큰이 바뀜) 다시 재발급하지 않아요
      if (getToken?.() === token) await refresh()
      res = await send(getToken?.())
      if (!res.ok) {
        const again = await toApiError(res)
        // 새 토큰으로도 로그인이 필요하다면 이어 갈 수 없는 세션이라 끝내요(재발급을 되풀이하지 않아요)
        if (isLoginRequired(again)) endSession?.(again)
        throw again
      }
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
  }
}
export type HttpRequest = ReturnType<typeof createHttpClient>
