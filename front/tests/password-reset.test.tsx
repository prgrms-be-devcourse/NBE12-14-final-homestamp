import { expect, test } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { createLocalAuth, DEV_FIXED_CODE } from "../src/api/auth"
import { AuthScreen } from "../src/features/auth/auth-screen"

// 로그인 화면의 비밀번호 재설정(06 S-02, ACC-5b·5c, #416). 서버 없이 개발할 때의 계정(createLocalAuth)으로 돌려요.
async function verifyPhone(phone: string) {
  fireEvent.click(
    await screen.findByRole("button", { name: "비밀번호를 잊었나요?" }),
  )
  fireEvent.change(screen.getByLabelText("휴대폰 번호"), {
    target: { value: phone },
  })
  fireEvent.click(screen.getByRole("button", { name: "인증번호 받기" }))
  fireEvent.change(await screen.findByLabelText(/인증번호 6자리/), {
    target: { value: DEV_FIXED_CODE },
  })
  fireEvent.click(screen.getByRole("button", { name: "인증하기" }))
}
function setPassword(next: string, next2 = next) {
  fireEvent.change(screen.getByLabelText("새 비밀번호"), {
    target: { value: next },
  })
  fireEvent.change(screen.getByLabelText("새 비밀번호 확인"), {
    target: { value: next2 },
  })
  fireEvent.click(screen.getByRole("button", { name: "비밀번호 다시 정하기" }))
}
async function login(auth: ReturnType<typeof createLocalAuth>, pw: string) {
  return auth.login("test@gmail.com", pw).then(
    () => true,
    () => false,
  )
}

test("휴대폰 인증 뒤 새 비밀번호를 정하면 로그인으로 돌아가고 새 비밀번호로 로그인된다", async () => {
  const auth = createLocalAuth()
  render(<AuthScreen auth={auth} onDone={() => undefined} />)

  await verifyPhone("010-1111-2222")
  await screen.findByText(/인증을 마쳤어요/)
  setPassword("cccc3333", "cccc3334")
  expect((await screen.findByRole("alert")).textContent).toContain(
    "비밀번호 확인이 일치하지 않아요",
  )
  setPassword("cccc3333")

  expect(await screen.findByText(/비밀번호를 다시 정했어요/)).toBeTruthy()
  expect(screen.getByText("이메일과 비밀번호로 로그인해 주세요.")).toBeTruthy()
  expect(await login(auth, "aaaa1111")).toBe(false)
  expect(await login(auth, "cccc3333")).toBe(true)
})

test("회원이 없는 번호도 인증 문자는 같게 받고, 재설정에서 가입 여부를 따로 알리지 않고 인증부터 다시 한다(ACC-5c)", async () => {
  const auth = createLocalAuth()
  render(<AuthScreen auth={auth} onDone={() => undefined} />)

  await verifyPhone("01099998888")
  await screen.findByText(/인증을 마쳤어요/)
  setPassword("cccc3333")

  const alert = await screen.findByRole("alert")
  expect(alert.textContent).toContain(
    "이 번호로 다시 정할 수 있는 계정이 없어요",
  )
  expect(alert.textContent).not.toContain("가입하지 않은")
  // 인증 단계로 돌아가요
  expect(screen.getByRole("button", { name: "인증번호 받기" })).toBeTruthy()
})

test("가입 인증 표로는 비밀번호를 다시 정할 수 없다", async () => {
  const auth = createLocalAuth()
  const v = await auth.requestPhoneCode("01011112222", "SIGNUP")
  const { phoneToken } = await auth.verifyPhoneCode(
    v.verificationId,
    DEV_FIXED_CODE,
  )

  await expect(
    auth.resetPassword(phoneToken, "cccc3333"),
  ).rejects.toMatchObject({ code: "CODE_EXPIRED" })
  expect(await login(auth, "aaaa1111")).toBe(true)
})
