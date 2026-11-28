import { beforeEach, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiError } from "../src/api/errors"
import App from "../src/App"

// #411 리뷰: 문자 링크(/l#토큰)는 공개 열람이라 로그인 복원이 끝나기 전에도, 실패해도 열려야 해요.
// fail은 한 번만 실패하고 그다음 복원은 로그인 전(null)으로 끝나요
const ctl = vi.hoisted(() => ({
  mode: "pending" as "pending" | "fail" | "done",
}))
vi.mock("../src/api/auth", async (importOriginal) => {
  const m = await importOriginal<typeof import("../src/api/auth")>()
  return {
    ...m,
    createAuth: () => {
      const auth = m.createLocalAuth()
      auth.restore = () => {
        if (ctl.mode === "pending") return new Promise(() => {})
        if (ctl.mode === "done") return Promise.resolve(null)
        ctl.mode = "done"
        return Promise.reject(
          new ApiError({
            status: 0,
            code: "NETWORK",
            detail: "연결이 불안정해요. 잠시 뒤 다시 시도해 주세요.",
          }),
        )
      }
      return auth
    },
  }
})

const VIEW = {
  kind: "ROOM_INVITE",
  sido: "서울특별시",
  sigungu: "강남구",
  dong: "역삼동",
  yourSide: "LANDLORD",
  record: null,
  hasMoney: false,
}

beforeEach(() => {
  window.history.replaceState(null, "", "/l#tok-restore")
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () =>
      new Response(JSON.stringify(VIEW), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
  )
})

test("로그인 복원이 끝나기 전에도 링크 화면을 열고, 가입·로그인 대신 확인 중을 보여 준다", async () => {
  ctl.mode = "pending"
  render(<App />)

  expect(await screen.findByText(/집주인으로 초대받았어요/)).toBeTruthy()
  expect(screen.getByText("로그인 상태를 확인하는 중이에요")).toBeTruthy()
  expect(screen.queryByRole("button", { name: /가입하고/ })).toBeNull()
  expect(screen.queryByRole("button", { name: /로그인하고/ })).toBeNull()
  expect(screen.queryByText("HomeStamp를 여는 중이에요")).toBeNull()
})

test("로그인 복원이 실패해도 링크 화면은 그대로이고, 다시 불러오면 가입·로그인을 고를 수 있다", async () => {
  ctl.mode = "fail"
  render(<App />)

  expect(await screen.findByText(/집주인으로 초대받았어요/)).toBeTruthy()
  expect((await screen.findByRole("alert")).textContent).toContain(
    "연결이 불안정해요",
  )
  expect(screen.queryByRole("button", { name: /가입하고/ })).toBeNull()

  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }))

  expect(
    await screen.findByRole("button", { name: "가입하고 연결하기" }),
  ).toBeTruthy()
  expect(screen.getByText(/집주인으로 초대받았어요/)).toBeTruthy()
})
