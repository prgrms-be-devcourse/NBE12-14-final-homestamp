import { test, expect } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { MoneyInput } from "../src/components/money-input"
import { formatMoneyInput, formatWon, parseMoney } from "../src/lib/money"

test("원화 입력은 즉시 쉼표를 표시하고 파서는 숫자로 복원한다", () => {
  render(<MoneyInput aria-label="보증금" name="deposit" />)
  const input = screen.getByLabelText("보증금") as HTMLInputElement
  fireEvent.change(input, { target: { value: "10000000", selectionStart: 8 } })
  expect(input.value).toBe("10,000,000")
  expect(input.inputMode).toBe("numeric")
  expect(parseMoney(input.value)).toBe(10000000)
  expect(formatMoneyInput("32500")).toBe("32,500")
  expect(formatWon(-200000)).toBe("-200,000원")
})
test("중간 삽입과 쉼표 근처 삭제 후 커서가 끝으로 이동하지 않는다", () => {
  render(<MoneyInput aria-label="금액" defaultValue={1234567} />)
  const input = screen.getByLabelText("금액") as HTMLInputElement
  fireEvent.change(input, {
    target: { value: "1,2934,567", selectionStart: 4 },
  })
  expect(input.value).toBe("12,934,567")
  expect(input.selectionStart).toBe(4)
  input.setSelectionRange(3, 3)
  fireEvent.keyDown(input, { key: "Backspace" })
  expect(input.value).toBe("1,934,567")
  expect(input.selectionStart).toBe(1)
  fireEvent.change(input, { target: { value: "" } })
  expect(input.value).toBe("")
  expect(parseMoney(input.value)).toBeNaN()
})

test("쉼표 뒤 Delete와 선택 범위 교체·붙여넣기 및 한도 검증", () => {
  render(<MoneyInput aria-label="금액" defaultValue={1234567} />)
  const input = screen.getByLabelText("금액") as HTMLInputElement
  input.setSelectionRange(1, 1)
  fireEvent.keyDown(input, { key: "Delete" })
  expect(input.value).toBe("134,567")
  expect(input.selectionStart).toBe(1)
  input.setSelectionRange(0, 3)
  fireEvent.change(input, { target: { value: "200,567", selectionStart: 3 } })
  expect(input.value).toBe("200,567")
  expect(input.selectionStart).toBe(3)
  fireEvent.change(input, {
    target: { value: "10,000,000", selectionStart: 10 },
  })
  expect(parseMoney(input.value)).toBe(10000000)
  expect(parseMoney("100,000,000,001")).toBeNaN()
  expect(parseMoney("12.5")).toBeNaN()
  expect(parseMoney("-10")).toBeNaN()
})
