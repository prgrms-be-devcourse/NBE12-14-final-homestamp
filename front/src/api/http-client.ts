import { ApiError, type ProblemDetail } from "./errors"

// 백엔드 연결용 요청 함수(명세 4-0): JSON, Bearer 토큰, 상태를 바꾸는 요청엔 Idempotency-Key, 오류는 Problem Details.
export interface HttpClientOptions {
  baseUrl: string
  getToken?: () => string | undefined
}
export type RequestOptions = {
  body?: unknown
  idempotent?: boolean
  signal?: AbortSignal
}
export function createHttpClient({ baseUrl, getToken }: HttpClientOptions) {
  return async function request<T>(
    method: "GET" | "POST",
    path: string,
    opts: RequestOptions = {},
  ): Promise<T> {
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
      throw new ApiError({ status: res.status, ...problem })
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
  }
}
export type HttpRequest = ReturnType<typeof createHttpClient>
