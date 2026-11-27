import { expect, test } from "vitest"
import { fireEvent, render, screen, within } from "@testing-library/react"
import App from "../src/App"

// App에서 입력 창을 열고 닫는 흐름(창 부품은 features/*/…-form). 개발용 확인 계정·호실로 시작해요.
async function login() {
  render(<App />)
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "aaaa1111" },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))
  await screen.findAllByText(/이 호실에서 세입자 쪽/)
}

test("하자 등록 창: 홈에서 열고, 사진 없이 보내면 안내하고, 취소하면 닫힌다", async () => {
  await login()
  fireEvent.click(screen.getByRole("button", { name: /하자 등록/ }))
  const dialog = await screen.findByRole("dialog")
  expect(within(dialog).getByText("새 하자 기록")).toBeTruthy()
  // 부위 목록을 받으면 기본 부위(욕실 · 천장)를 골라 둬요
  await expect
    .poll(
      () =>
        within(dialog).queryByRole("combobox", { name: "부위" })?.textContent,
    )
    .toMatch(/^천장/)
  fireEvent.change(within(dialog).getByLabelText("기록 제목"), {
    target: { value: "천장 물 자국" },
  })
  fireEvent.change(within(dialog).getByLabelText("상태 설명"), {
    target: { value: "얼룩이 있어요" },
  })
  fireEvent.click(within(dialog).getByRole("button", { name: /기록 남기기/ }))
  expect(within(dialog).getByRole("alert").textContent).toBe(
    "수리 전 사진을 1장 이상 첨부해 주세요.",
  )
  fireEvent.click(within(dialog).getByRole("button", { name: "취소" }))
  await expect.poll(() => screen.queryByText("새 하자 기록")).toBeNull()
})

test("내 계정 창: 계정 정보를 보여 주고 로그아웃하면 로그인 화면으로 간다", async () => {
  await login()
  fireEvent.click(screen.getAllByRole("button", { name: /김냥냥 님/ })[0])
  const dialog = await screen.findByRole("dialog")
  expect(within(dialog).getByText("내 계정")).toBeTruthy()
  expect(within(dialog).getByText("test@gmail.com")).toBeTruthy()
  fireEvent.click(within(dialog).getByRole("button", { name: /로그아웃/ }))
  expect(
    await screen.findByRole("button", { name: "로그인", exact: true }),
  ).toBeTruthy()
})
