// 서버 오류 형식: RFC 9457 Problem Details(명세 4-0). code는 명세 4-10 목록, detail은 사용자 문구.
export interface ProblemDetail { type?: string; title?: string; status: number; code?: string; detail?: string }
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title || '요청을 처리하지 못했어요.')
    this.status = problem.status; this.code = problem.code
  }
}
// VERSION_CONFLICT·INVALID_STATE는 화면이 다시 불러온 뒤 한 줄 안내(명세 5장)
export const shouldReload = (e: unknown) => e instanceof ApiError && (e.code === 'VERSION_CONFLICT' || e.code === 'INVALID_STATE')
export const errorMessage = (e: unknown) => e instanceof Error ? e.message : '요청을 처리하지 못했어요.'
