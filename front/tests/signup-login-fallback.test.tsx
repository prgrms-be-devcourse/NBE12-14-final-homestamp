import { expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import {
  SIGNED_UP_LOGIN_FAILED_MESSAGE,
  SignedUpLoginError,
  type AuthApi,
} from "../src/api/auth"
import { AuthScreen } from "../src/features/auth/auth-screen"

// 가입은 됐는데 이어진 로그인이 실패하면(#208 리뷰) 다시 가입하게 두지 않고
// 로그인 화면으로 옮겨 가입한 이메일을 채워 둬요. 다시 가입하면 쓴 인증 표 때문에 CODE_EXPIRED·EMAIL_TAKEN에 막혀요.
function fakeAuth(): AuthApi {
  return {
    mode: "http",
    requestPhoneCode: vi.fn(async () => ({
      verificationId: "v1",
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      delivered: true,
    })),
    verifyPhoneCode: vi.fn(async () => ({ phoneToken: "T1" })),
    signup: vi.fn(async () => {
      throw new SignedUpLoginError("seoyun@example.com")
    }),
    login: vi.fn(),
    restore: vi.fn(async () => null),
    logout: vi.fn(async () => undefined),
    getToken: () => undefined,
  }
}
const type = (label: RegExp, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })

test("가입 뒤 로그인만 실패하면 로그인 화면으로 옮기고 가입한 이메일을 채운다", async () => {
  const auth = fakeAuth()
  const onDone = vi.fn()
  render(<AuthScreen auth={auth} onDone={onDone} initialMode="signup" />)

  // ① 휴대폰 인증
  type(/휴대폰 번호/, "01012345678")
  fireEvent.click(screen.getByRole("button", { name: "인증번호 받기" }))
  await screen.findByRole("button", { name: "다시 받기" })
  type(/인증번호 6자리/, "123456")
  fireEvent.click(screen.getByRole("button", { name: "인증하기" }))
  // ② 계정 정보
  await screen.findByLabelText(/이메일 · 로그인 아이디/)
  type(/이메일 · 로그인 아이디/, "seoyun@example.com")
  type(/^비밀번호$/, "abcd1234")
  type(/비밀번호 확인/, "abcd1234")
  type(/이름/, "정서윤")
  fireEvent.click(screen.getByRole("button", { name: "다음" }))
  // ③ 확인 항목
  await screen.findByRole("button", { name: "가입하기" })
  for (const box of screen.getAllByRole("checkbox")) fireEvent.click(box)
  fireEvent.click(screen.getByRole("button", { name: "가입하기" }))

  await screen.findByText(SIGNED_UP_LOGIN_FAILED_MESSAGE)
  expect((screen.getByLabelText("이메일") as HTMLInputElement).value).toBe(
    "seoyun@example.com",
  )
  screen.getByRole("button", { name: "로그인" })
  expect(screen.queryByRole("button", { name: "가입하기" })).toBeNull()
  expect(auth.signup).toHaveBeenCalledTimes(1)
  expect(onDone).not.toHaveBeenCalled()
})
