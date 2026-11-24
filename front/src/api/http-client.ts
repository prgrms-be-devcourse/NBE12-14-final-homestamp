import { ApiError, type ProblemDetail } from "./errors"
import { isConnected, notConnected } from "./connected"

// 백엔드 연결용 요청 함수(명세 4-0): JSON, Bearer 토큰, 상태를 바꾸는 요청엔 Idempotency-Key, 오류는 Problem Details.
export interface HttpClientOptions {
  baseUrl: string
  getToken?: () => string | undefined
  // 서버로 보내도 되는 요청인지(기본: api/connected.ts의 연결을 마친 API 목록)
  allow?: (method: "GET" | "POST", path: string) => boolean
}
export type RequestOptions = {
  body?: unknown
  idempotent?: boolean
  signal?: AbortSignal
}
export function createHttpClient({
  baseUrl,
  getToken,
  allow = isConnected,
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
    const token = getToken?.()
    if (token) headers.Authorization = `Bearer ${token}`
    if (method === "POST" && opts.idempotent !== false)
      headers["Idempotency-Key"] = crypto.randomUUID()
    let res: Response
    try {
      res = await fetch(baseUrl + path, {
        method,
        headers,
        body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
        credentials: "include",
        signal: opts.signal,
      })
    } catch {
      throw new ApiError({
        status: 0,
        code: "NETWORK",
        detail: "연결이 불안정해요. 잠시 뒤 다시 시도해 주세요.",
      })
    }
    if (!res.ok) {
      const problem = (await res
        .json()
        .catch(() => ({}))) as Partial<ProblemDetail>
      const retry = Number(res.headers.get("Retry-After"))
      throw new ApiError({
        status: res.status,
        ...problem,
        retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
      })
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
  }
}
export type HttpRequest = ReturnType<typeof createHttpClient>
