import type { ReactNode } from "react"
import { describe, expect, test } from "vitest"
import { renderHook, waitFor } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { ApiError } from "../src/api/errors"
import { isNotFound } from "../src/api/http-rooms"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockStore } from "../src/api/mock/store"
import { useRepairCase } from "../src/features/repair/use-repair-case"

// 수리 건 상세 조회가 실패하면 원인을 그대로 돌려줘 화면이 404(남의 건·없는 건)를 가를 수 있어야 해요(#270 리뷰)
describe("useRepairCase 조회 실패", () => {
  const withGet = (get: () => Promise<never>) => {
    const api = createMockApi(createMockStore())
    const wrapper = ({ children }: { children: ReactNode }) => (
      <ApiProvider api={{ ...api, repairCases: { ...api.repairCases, get } }}>
        {children}
      </ApiProvider>
    )
    return renderHook(() => useRepairCase("r1", { accountId: "a1" }), {
      wrapper,
    })
  }

  test("404면 상태 코드가 남아 찾을 수 없는 기록으로 가를 수 있어요", async () => {
    const { result } = withGet(() =>
      Promise.reject(new ApiError({ status: 404, code: "NOT_FOUND" })),
    )
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(isNotFound(result.current.error)).toBe(true)
  })

  test("다른 오류는 404로 보지 않아요", async () => {
    const { result } = withGet(() =>
      Promise.reject(new ApiError({ status: 500, detail: "잠시 뒤에" })),
    )
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(isNotFound(result.current.error)).toBe(false)
  })
})
