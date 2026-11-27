import { beforeEach, expect, test, vi } from "vitest"
import { act, fireEvent, render, screen } from "@testing-library/react"
import { ApiError } from "../src/api/errors"
import App from "../src/App"

// 화면 쪽(#191): 쓰는 중에 세션이 끝나면 로그인 화면으로 보내 안내하고,
// 새로고침 뒤 복원이 연결 오류로 실패하면 로그아웃하지 않고 다시 시도하게 해요.
const ctl = vi.hoisted(() => ({
  end: undefined as ((e: Error) => void) | undefined,
  restoreFailures: 0,
}))
vi.mock("../src/api/auth", async (importOriginal) => {
  const m = await importOriginal<typeof import("../src/api/auth")>()
  return {
    ...m,
    createAuth: () => {
      const auth = m.createLocalAuth()
      const restore = auth.restore.bind(auth)
      auth.restore = async () => {
        if (ctl.restoreFailures > 0) {
          ctl.restoreFailures--
          throw new ApiError({
            status: 0,
            code: "NETWORK",
            detail: "연결이 불안정해요. 잠시 뒤 다시 시도해 주세요.",
          })
        }
        return restore()
      }
      auth.onSessionEnd = (listener) => {
        ctl.end = listener
        return () => {
          ctl.end = undefined
        }
      }
      return auth
    },
  }
})
beforeEach(() => {
  ctl.end = undefined
  ctl.restoreFailures = 0
})

async function login() {
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "aaaa1111" },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))
  await screen.findAllByText(/이 호실에서 세입자 쪽/)
}

test("쓰는 중에 재발급이 실패해 세션이 끝나면 로그인 화면으로 보내고 그 문구를 보여 준다", async () => {
  render(<App />)
  await login()
  expect(screen.queryByLabelText("비밀번호")).toBeNull()

  act(() =>
    ctl.end?.(
      new ApiError({
        status: 401,
        code: "REFRESH_REUSED",
        detail: "보안을 위해 로그아웃했어요. 다시 로그인해 주세요",
      }),
    ),
  )

  expect(await screen.findByLabelText("비밀번호")).toBeTruthy()
  expect(screen.getByText(/보안을 위해 로그아웃했어요/)).toBeTruthy()
})

test("새로고침 뒤 복원이 연결 오류로 실패하면 로그인 화면 대신 다시 불러오기를 보여 주고, 누르면 다시 시도한다", async () => {
  ctl.restoreFailures = 1
  render(<App />)

  expect((await screen.findByRole("alert")).textContent).toContain(
    "연결이 불안정해요",
  )
  expect(screen.queryByLabelText("비밀번호")).toBeNull()

  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }))
  // 목업에는 남은 로그인이 없어 로그인 화면으로 가요(서버면 리프레시 쿠키로 로그인이 이어져요)
  expect(await screen.findByLabelText("비밀번호")).toBeTruthy()
})
