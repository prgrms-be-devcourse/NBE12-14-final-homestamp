import { expect, test, vi } from "vitest"
import { fireEvent, render, screen, within } from "@testing-library/react"
import { ApiError } from "../src/api/errors"
import App from "../src/App"

// 목업 인증의 로그아웃만 처음 한 번 연결 오류로 실패하게 바꿔요.
// 로그아웃이 실패했는데 화면만 로그인 화면으로 가면, 리프레시 쿠키가 남아 새로고침할 때 다시 로그인돼요.
const logoutFailures = vi.hoisted(() => ({ left: 0 }))
vi.mock("../src/api/auth", async (importOriginal) => {
  const m = await importOriginal<typeof import("../src/api/auth")>()
  return {
    ...m,
    createAuth: () => {
      const auth = m.createLocalAuth()
      const logout = auth.logout.bind(auth)
      auth.logout = async () => {
        if (logoutFailures.left > 0) {
          logoutFailures.left--
          throw new ApiError({
            status: 0,
            code: "NETWORK",
            detail: "연결이 불안정해요. 잠시 뒤 다시 시도해 주세요.",
          })
        }
        return logout()
      }
      return auth
    },
  }
})

test("로그아웃이 실패하면 로그인 상태를 지키고 안내하며, 다시 누르면 로그아웃된다", async () => {
  logoutFailures.left = 1
  render(<App />)
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "aaaa1111" },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))
  await screen.findAllByText(/이 호실에서 세입자 쪽/)

  fireEvent.click(screen.getByRole("button", { name: "내 계정" }))
  const dialog = await screen.findByRole("dialog")
  fireEvent.click(within(dialog).getByRole("button", { name: /로그아웃/ }))

  const alert = await within(dialog).findByRole("alert")
  expect(alert.textContent).toContain("로그아웃하지 못했어요")
  expect(screen.queryByLabelText("비밀번호")).toBeNull()

  fireEvent.click(within(dialog).getByRole("button", { name: /로그아웃/ }))
  expect(await screen.findByLabelText("비밀번호")).toBeTruthy()
})
