import { beforeEach, expect, test, vi } from "vitest"
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import { ApiError } from "../src/api/errors"
import type { AccountPatch, PendingAcknowledgement } from "../src/api/auth"
import App from "../src/App"

// 내 계정 창의 이름·문자 설정·비밀번호 바꾸기(06 S-18 대신 내 계정 창, ACC-4·6, #416).
// 서버 없이 개발할 때의 계정(createLocalAuth)으로 돌리고, 약관 확인 전 거절만 서버처럼 흉내 내요.
const ctl = vi.hoisted(() => ({
  pending: [] as PendingAcknowledgement[],
  updates: [] as AccountPatch[],
  termsRequired: undefined as ((e: Error) => void) | undefined,
}))
vi.mock("../src/api/auth", async (importOriginal) => {
  const m = await importOriginal<typeof import("../src/api/auth")>()
  return {
    ...m,
    createAuth: () => {
      const auth = m.createLocalAuth()
      const update = auth.updateAccount.bind(auth)
      const withPending = <T extends object>(a: T) => ({
        ...a,
        pendingAcknowledgements: ctl.pending,
      })
      const login = auth.login.bind(auth)
      auth.login = async (e, p) => withPending(await login(e, p))
      auth.reload = async () => withPending((await auth.restore())!)
      auth.acknowledge = async () => {
        ctl.pending = []
        return withPending((await auth.restore())!)
      }
      // 서버처럼: 확인 전에는 문자 끄기만 받고, 이름 바꾸기·문자 켜기는 403(CONS-4b)
      auth.updateAccount = async (patch) => {
        ctl.updates.push(patch)
        const onlyOff = patch.name === undefined && patch.smsEnabled === false
        if (ctl.pending.length && !onlyOff) {
          const e = new ApiError({
            status: 403,
            code: "TERMS_UPDATE_REQUIRED",
            detail: "약관이 바뀌었어요. 확인하고 계속해 주세요",
          })
          ctl.termsRequired?.(e)
          throw e
        }
        return withPending(await update(patch))
      }
      auth.onTermsRequired = (listener) => {
        ctl.termsRequired = listener
        return () => {
          ctl.termsRequired = undefined
        }
      }
      return auth
    },
  }
})
beforeEach(() => {
  ctl.pending = []
  ctl.updates = []
  ctl.termsRequired = undefined
})

async function login(password = "aaaa1111") {
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: password },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))
}
async function openAccount() {
  await screen.findAllByText(/이 호실에서 세입자 쪽/)
  fireEvent.click(screen.getByRole("button", { name: "내 계정" }))
  return screen.findByRole("dialog", { name: "내 계정" })
}

test("이름을 바꾸면 내 계정 창과 화면의 이름이 바뀐다", async () => {
  render(<App />)
  await login()
  const dialog = await openAccount()

  fireEvent.click(within(dialog).getByRole("button", { name: "이름 바꾸기" }))
  fireEvent.change(within(dialog).getByLabelText("새 이름"), {
    target: { value: "  김새이름 " },
  })
  fireEvent.click(within(dialog).getByRole("button", { name: "이름 저장" }))

  expect(await within(dialog).findByText("이름을 바꿨어요.")).toBeTruthy()
  expect(within(dialog).getByText("김새이름")).toBeTruthy()
  expect(ctl.updates).toEqual([{ name: "김새이름" }])
})

test("빈 이름은 보내지 않고 안내한다", async () => {
  render(<App />)
  await login()
  const dialog = await openAccount()

  fireEvent.click(within(dialog).getByRole("button", { name: "이름 바꾸기" }))
  fireEvent.change(within(dialog).getByLabelText("새 이름"), {
    target: { value: "   " },
  })
  fireEvent.click(within(dialog).getByRole("button", { name: "이름 저장" }))

  expect((await within(dialog).findByRole("alert")).textContent).toContain(
    "이름은 1~50자",
  )
  expect(ctl.updates).toEqual([])
})

test("문자를 끄고 다시 켤 수 있다", async () => {
  render(<App />)
  await login()
  const dialog = await openAccount()
  expect(within(dialog).getByText("켜 둠")).toBeTruthy()

  fireEvent.click(within(dialog).getByRole("button", { name: "문자 끄기" }))
  expect(await within(dialog).findByText("꺼 둠")).toBeTruthy()
  expect(within(dialog).getByText(/문자를 껐어요/)).toBeTruthy()

  fireEvent.click(within(dialog).getByRole("button", { name: "문자 켜기" }))
  expect(await within(dialog).findByText("켜 둠")).toBeTruthy()
  expect(ctl.updates).toEqual([{ smsEnabled: false }, { smsEnabled: true }])
})

test("약관 확인 전 이름 바꾸기는 확인 창을 열고, 확인한 뒤에도 다시 보내지 않는다(#401)", async () => {
  ctl.pending = [{ kind: "TERMS_AGREED", textVersion: "2026-12-01" }]
  render(<App />)
  await login()
  // 로그인 직후 뜬 확인 창은 [나중에]로 닫아요
  const first = await screen.findByRole("dialog", {
    name: "바뀐 약관을 확인해 주세요",
  })
  fireEvent.click(within(first).getByRole("button", { name: "나중에" }))
  const dialog = await openAccount()

  fireEvent.click(within(dialog).getByRole("button", { name: "이름 바꾸기" }))
  fireEvent.change(within(dialog).getByLabelText("새 이름"), {
    target: { value: "김새이름" },
  })
  fireEvent.click(within(dialog).getByRole("button", { name: "이름 저장" }))

  const terms = await screen.findByRole("dialog", {
    name: "바뀐 약관을 확인해 주세요",
  })
  fireEvent.click(within(terms).getByText("(필수) 이용약관에 동의해요"))
  fireEvent.click(within(terms).getByRole("button", { name: "확인하고 계속" }))
  await waitFor(() =>
    expect(
      screen.queryByRole("dialog", { name: "바뀐 약관을 확인해 주세요" }),
    ).toBeNull(),
  )
  // 확인 창이 닫히면 내 계정 창에 다시 해 달라는 안내가 남아 있어요
  expect((await within(dialog).findByRole("alert")).textContent).toContain(
    "바뀐 약관을 확인한 뒤 다시 해 주세요",
  )
  // 거절된 이름 바꾸기는 한 번만 보냈어요. 쓰던 이름은 칸에 남아 사람이 다시 눌러요
  expect(ctl.updates).toEqual([{ name: "김새이름" }])
  expect((screen.getByLabelText("새 이름") as HTMLInputElement).value).toBe(
    "김새이름",
  )
})

test("약관 확인 전에도 문자 끄기는 된다(CONS-4b)", async () => {
  ctl.pending = [{ kind: "TERMS_AGREED", textVersion: "2026-12-01" }]
  render(<App />)
  await login()
  const first = await screen.findByRole("dialog", {
    name: "바뀐 약관을 확인해 주세요",
  })
  fireEvent.click(within(first).getByRole("button", { name: "나중에" }))
  const dialog = await openAccount()

  fireEvent.click(within(dialog).getByRole("button", { name: "문자 끄기" }))

  expect(await within(dialog).findByText("꺼 둠")).toBeTruthy()
})

test("비밀번호를 바꾸면 이 기기는 그대로이고 새 비밀번호로 다시 로그인할 수 있다", async () => {
  render(<App />)
  await login()
  const dialog = await openAccount()

  fireEvent.click(
    within(dialog).getByRole("button", { name: "비밀번호 바꾸기" }),
  )
  const fill = (current: string, next: string, next2 = next) => {
    fireEvent.change(within(dialog).getByLabelText("지금 비밀번호"), {
      target: { value: current },
    })
    fireEvent.change(within(dialog).getByLabelText("새 비밀번호"), {
      target: { value: next },
    })
    fireEvent.change(within(dialog).getByLabelText("새 비밀번호 확인"), {
      target: { value: next2 },
    })
    fireEvent.click(
      within(dialog).getByRole("button", { name: "비밀번호 저장" }),
    )
  }
  // 규칙에 맞지 않으면 보내지 않아요
  fill("aaaa1111", "short")
  expect((await within(dialog).findByRole("alert")).textContent).toContain(
    "8자 이상",
  )
  // 지금 비밀번호가 틀리면 그 칸만 비워요
  fill("wrong1111", "bbbb2222")
  expect((await within(dialog).findByRole("alert")).textContent).toContain(
    "지금 비밀번호가 맞지 않아요",
  )
  await waitFor(() =>
    expect(
      (within(dialog).getByLabelText("지금 비밀번호") as HTMLInputElement)
        .value,
    ).toBe(""),
  )
  fill("aaaa1111", "bbbb2222")
  expect(await within(dialog).findByText(/비밀번호를 바꿨어요/)).toBeTruthy()

  fireEvent.click(within(dialog).getByRole("button", { name: "로그아웃" }))
  await login("aaaa1111")
  expect((await screen.findByRole("alert")).textContent).toContain(
    "이메일이나 비밀번호가 맞지 않아요",
  )
  await login("bbbb2222")
  expect(await screen.findAllByText(/이 호실에서 세입자 쪽/)).toBeTruthy()
})
