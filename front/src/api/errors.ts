// 서버 오류 형식: RFC 9457 Problem Details(명세 4-0). code는 명세 4-10 목록, detail은 사용자 문구.
export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  code?: string
  detail?: string
  // RATE_LIMITED의 Retry-After(초)
  retryAfter?: number
  // 업로드 표·사진 검사 오류의 사진 순서(0부터, 06 PHO-1b·1c)
  photoIndex?: number
}
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly retryAfter?: number
  readonly photoIndex?: number
  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title || "요청을 처리하지 못했어요.")
    this.status = problem.status
    this.code = problem.code
    this.retryAfter = problem.retryAfter
    this.photoIndex = problem.photoIndex
  }
}
// VERSION_CONFLICT·INVALID_STATE는 화면이 다시 불러온 뒤 한 줄 안내(명세 5장)
export const shouldReload = (e: unknown) =>
  e instanceof ApiError &&
  (e.code === "VERSION_CONFLICT" || e.code === "INVALID_STATE")
// 어느 사진이 거절됐는지 알려 그 사진만 다시 고르게 해요(PHO-1c, 쓴 글은 창에 그대로 남아요)
export const errorMessage = (e: unknown) =>
  e instanceof ApiError && e.photoIndex !== undefined
    ? `${e.photoIndex + 1}번째 사진 · ${e.message} 그 사진만 빼고 다시 골라 주세요.`
    : e instanceof Error
      ? e.message
      : "요청을 처리하지 못했어요."
