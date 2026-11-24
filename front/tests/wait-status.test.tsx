import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { WaitStatus } from "../src/components/common/wait-status"
import { waitLabel } from "../src/lib/words"

describe("확인 없음 표시(09 5장 공통 부품)", () => {
  it("기다림 단계마다 같은 말을 써요", () => {
    expect(waitLabel("재통지")).toBe("다시 알렸어요")
    expect(waitLabel("미확인")).toBe("확인 없음")
    expect(waitLabel("미확인", { who: "세입자", long: true })).toBe(
      "세입자 확인 없음 · 72시간 동안 답이 오지 않았어요",
    )
    expect(waitLabel("재통지", { remindPrefix: "확인 요청" })).toBe(
      "확인 요청 · 다시 알렸어요",
    )
  })
  it("기다리는 것이 없으면 아무것도 그리지 않아요", () => {
    const { container } = render(<WaitStatus />)
    expect(container.textContent).toBe("")
    render(<WaitStatus stage="미확인" />)
    expect(screen.getByText("확인 없음")).toBeTruthy()
  })
})
