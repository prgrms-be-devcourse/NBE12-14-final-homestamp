import { test, expect } from "vitest"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { PhotoGallery } from "../src/components/photo-gallery"

const photos = [
  "data:image/png;base64,first",
  "data:image/png;base64,second",
  "data:image/png;base64,third",
]

test("모든 사진을 표시하고 선택한 사진부터 확대·앞뒤 이동·번호를 제공한다", () => {
  render(<PhotoGallery title="입주 벽" photos={photos} />)
  expect(
    screen.getAllByRole("button", { name: /사진 \d 확대 보기/ }),
  ).toHaveLength(3)
  fireEvent.click(
    screen.getByRole("button", { name: "입주 벽 · 사진 2 확대 보기" }),
  )
  expect(
    screen.getByRole("dialog", { name: "입주 벽 · 사진 확대 보기" }),
  ).toBeTruthy()
  expect(
    screen.getByRole("status", { name: "현재 사진 번호" }).textContent,
  ).toBe("2 / 3")
  expect(
    screen
      .getByRole("img", { name: "입주 벽 · 확대 사진 2" })
      .getAttribute("src"),
  ).toBe(photos[1])
  fireEvent.click(screen.getByRole("button", { name: "다음 사진" }))
  expect(screen.getByRole("status").textContent).toBe("3 / 3")
  expect(
    screen.getByRole("button", { name: "다음 사진" }).hasAttribute("disabled"),
  ).toBe(true)
  fireEvent.click(screen.getByRole("button", { name: "이전 사진" }))
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "ArrowLeft" })
  expect(screen.getByRole("status").textContent).toBe("1 / 3")
  expect(
    screen.getByRole("button", { name: "이전 사진" }).hasAttribute("disabled"),
  ).toBe(true)
})
test("닫기 후 다시 열면 선택한 사진에서 시작하고 원래 사진 배열을 바꾸지 않는다", async () => {
  const original = [...photos]
  render(<PhotoGallery title="퇴거 벽" photos={photos} />)
  const trigger = screen.getByRole("button", {
    name: "퇴거 벽 · 사진 3 확대 보기",
  })
  trigger.focus()
  fireEvent.click(trigger)
  fireEvent.click(screen.getByRole("button", { name: "사진 확대 보기 닫기" }))
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
  await waitFor(() => expect(document.activeElement).toBe(trigger))
  fireEvent.click(
    screen.getByRole("button", { name: "퇴거 벽 · 사진 1 확대 보기" }),
  )
  expect(screen.getByRole("status").textContent).toBe("1 / 3")
  expect(photos).toEqual(original)
})
test("한 장은 양쪽 이동을 막고 Escape로 닫힌다", async () => {
  render(<PhotoGallery title="수리 후" photos={[photos[0]]} />)
  fireEvent.click(
    screen.getByRole("button", { name: "수리 후 · 사진 1 확대 보기" }),
  )
  expect(screen.getByRole("status").textContent).toBe("1 / 1")
  expect(
    screen.getByRole("button", { name: "다음 사진" }).hasAttribute("disabled"),
  ).toBe(true)
  expect(
    screen.getByRole("button", { name: "이전 사진" }).hasAttribute("disabled"),
  ).toBe(true)
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" })
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
})
test("작은 목록 썸네일에서도 전체 사진을 넘길 수 있다", () => {
  render(<PhotoGallery title="수리 기록" photos={photos} layout="compact" />)
  expect(screen.getAllByRole("button", { name: /확대 보기/ })).toHaveLength(1)
  fireEvent.click(
    screen.getByRole("button", { name: "수리 기록 · 사진 1 확대 보기" }),
  )
  fireEvent.click(screen.getByRole("button", { name: "다음 사진" }))
  expect(screen.getByRole("status").textContent).toBe("2 / 3")
})

test("많은 사진은 3장만 미리 보고 +N과 전체 보기에서 원문·전체 사진을 확인한다", () => {
  const many = Array.from(
    { length: 7 },
    (_, i) => `data:image/png;base64,photo${i}`,
  )
  const description = "긴 설명 전체입니다. ".repeat(30)
  render(
    <PhotoGallery title="다수 사진" photos={many} description={description} />,
  )
  expect(
    screen.getAllByRole("button", { name: /사진 \d 확대 보기/ }),
  ).toHaveLength(3)
  expect(
    screen.getByRole("button", { name: "전체 사진 보기 (7장)" }),
  ).toBeTruthy()
  fireEvent.click(screen.getByRole("button", { name: "+4장" }))
  expect(screen.getByRole("status").textContent).toBe("4 / 7")
  expect(screen.getByText(description.trim())).toBeTruthy()
  expect(
    screen.getAllByRole("button", { name: /전체 사진 \d 선택/ }),
  ).toHaveLength(7)
  fireEvent.click(screen.getByRole("button", { name: "전체 사진 7 선택" }))
  expect(screen.getByRole("status").textContent).toBe("7 / 7")
})
