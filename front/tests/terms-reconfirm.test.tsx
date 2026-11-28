import { beforeEach, expect, test, vi } from "vitest"
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react"
import { ApiError } from "../src/api/errors"
import type { PendingAcknowledgement } from "../src/api/auth"
import App from "../src/App"

// 화면 쪽(CONS-4a·b, 03 6-2, #332): 로그인 뒤 바뀐 약관이 있으면 확인 창을 먼저 띄우고, 확인 전에는 닫고 보기만 할 수 있어요.
// 상태를 바꾸는 요청이 TERMS_UPDATE_REQUIRED로 거절되면 확인 창을 열고, 확인한 뒤에도 그 요청은 다시 보내지 않아요.
const ctl = vi.hoisted(() => ({
  pending: [] as PendingAcknowledgement[],
  acknowledged: [] as PendingAcknowledgement[][],
  conflictOnce: undefined as PendingAcknowledgement[] | undefined,
  termsRequired: undefined as ((e: Error) => void) | undefined,
}))
vi.mock("../src/api/auth", async (importOriginal) => {
  const m = await importOriginal<typeof import("../src/api/auth")>()
  return {
    ...m,
    createAuth: () => {
      const auth = m.createLocalAuth()
      const login = auth.login.bind(auth)
      const withPending = <T extends object>(a: T) => ({
        ...a,
        pendingAcknowledgements: ctl.pending,
      })
      auth.login = async (email, password) =>
        withPending(await login(email, password))
      auth.reload = async () => withPending((await auth.restore())!)
      auth.acknowledge = async (items) => {
        ctl.acknowledged.push(items)
        if (ctl.conflictOnce) {
          // 그사이 판이 바뀌었어요
          ctl.pending = ctl.conflictOnce
          ctl.conflictOnce = undefined
          throw new ApiError({
            status: 409,
            code: "VERSION_CONFLICT",
            detail: "다른 쪽에서 먼저 바뀌었어요. 다시 불러올게요",
          })
        }
        ctl.pending = []
        return withPending((await auth.restore())!)
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
  ctl.acknowledged = []
  ctl.conflictOnce = undefined
  ctl.termsRequired = undefined
})

const TERMS: PendingAcknowledgement = {
  kind: "TERMS_AGREED",
  textVersion: "2026-12-01",
}
const PRIVACY: PendingAcknowledgement = {
  kind: "PRIVACY_NOTICE_ACK",
  textVersion: "2026-12-01",
}

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
const termsDialog = () =>
  screen.findByRole("dialog", { name: "바뀐 약관을 확인해 주세요" })

test("로그인 뒤 바뀐 약관이 있으면 확인 창을 먼저 띄우고, 모두 체크해야 확인할 수 있다", async () => {
  ctl.pending = [TERMS, PRIVACY]
  render(<App />)
  await login()

  const dialog = await termsDialog()
  expect(within(dialog).getAllByText("판 2026-12-01")).toHaveLength(2)
  // 개인정보 처리 안내는 가입 화면과 같은 문구를 보여 줘요
  expect(within(dialog).getByText(/주민등록번호는 받지 않아요/)).toBeTruthy()
  const confirm = within(dialog).getByRole("button", { name: "확인하고 계속" })
  expect((confirm as HTMLButtonElement).disabled).toBe(true)

  fireEvent.click(within(dialog).getByText("(필수) 이용약관에 동의해요"))
  expect((confirm as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(
    within(dialog).getByText("(필수) 개인정보 처리 안내를 확인했어요"),
  )
  fireEvent.click(confirm)

  await waitFor(() =>
    expect(
      screen.queryByRole("dialog", { name: "바뀐 약관을 확인해 주세요" }),
    ).toBeNull(),
  )
  // 서버가 준 판 그대로 한 번 보내요
  expect(ctl.acknowledged).toEqual([[TERMS, PRIVACY]])
  expect(
    await screen.findByText(/확인했어요. 하려던 일이 있었다면/),
  ).toBeTruthy()
})

test("이용약관 [보기]는 가입 화면의 약관 요약과 안내를 그대로 보여 준다", async () => {
  ctl.pending = [TERMS]
  render(<App />)
  await login()

  const dialog = await termsDialog()
  fireEvent.click(within(dialog).getByRole("button", { name: "보기" }))

  expect(
    within(dialog).getByText(/누가 얼마를 낼지 판단하지 않아요/),
  ).toBeTruthy()
  expect(
    within(dialog).getByText(
      "약관 전문은 서비스 정식 공개 때 이 화면에 게시돼요.",
    ),
  ).toBeTruthy()
})

test("[나중에]로 닫으면 화면을 그대로 보고, 내 계정 창의 [다시 확인]으로 다시 연다", async () => {
  ctl.pending = [TERMS]
  render(<App />)
  await login()

  fireEvent.click(
    within(await termsDialog()).getByRole("button", { name: "나중에" }),
  )
  await waitFor(() =>
    expect(
      screen.queryByRole("dialog", { name: "바뀐 약관을 확인해 주세요" }),
    ).toBeNull(),
  )
  expect(ctl.acknowledged).toEqual([])

  fireEvent.click(screen.getByRole("button", { name: "내 계정" }))
  const account = await screen.findByRole("dialog", { name: "내 계정" })
  expect(within(account).getByText("새 판 2026-12-01 · 확인 전")).toBeTruthy()
  // 서버가 알려 주지 않는 옛 판·바뀐 점은 만들지 않아요
  expect(within(account).queryByText(/옛 판|바뀐 점/)).toBeNull()
  fireEvent.click(within(account).getByRole("button", { name: "다시 확인" }))

  expect(await termsDialog()).toBeTruthy()
})

test("확인할 문구가 없으면 확인 창을 띄우지 않고, 내 계정 창에 확인했다고만 보인다", async () => {
  render(<App />)
  await login()

  expect(
    screen.queryByRole("dialog", { name: "바뀐 약관을 확인해 주세요" }),
  ).toBeNull()
  fireEvent.click(screen.getByRole("button", { name: "내 계정" }))
  const account = await screen.findByRole("dialog", { name: "내 계정" })
  expect(within(account).getAllByText("확인했어요")).toHaveLength(3)
  expect(
    within(account).queryByRole("button", { name: "다시 확인" }),
  ).toBeNull()
})

test("요청이 TERMS_UPDATE_REQUIRED로 거절되면 확인할 문구를 다시 받아 확인 창을 연다", async () => {
  render(<App />)
  await login()
  expect(
    screen.queryByRole("dialog", { name: "바뀐 약관을 확인해 주세요" }),
  ).toBeNull()

  // 쓰는 사이 판이 바뀌었어요
  ctl.pending = [TERMS]
  act(() =>
    ctl.termsRequired?.(
      new ApiError({
        status: 403,
        code: "TERMS_UPDATE_REQUIRED",
        detail: "약관이 바뀌었어요. 확인하고 계속해 주세요",
      }),
    ),
  )

  expect(await termsDialog()).toBeTruthy()
})

test("확인하는 사이 판이 또 바뀌면(VERSION_CONFLICT) 새 판으로 다시 불러와 다시 체크받는다", async () => {
  ctl.pending = [TERMS]
  const newer = { kind: "TERMS_AGREED" as const, textVersion: "2026-12-02" }
  ctl.conflictOnce = [newer]
  render(<App />)
  await login()

  let dialog = await termsDialog()
  fireEvent.click(within(dialog).getByText("(필수) 이용약관에 동의해요"))
  fireEvent.click(within(dialog).getByRole("button", { name: "확인하고 계속" }))

  dialog = await termsDialog()
  expect(await within(dialog).findByText("판 2026-12-02")).toBeTruthy()
  expect(within(dialog).getByRole("alert").textContent).toContain(
    "그사이 문구가 바뀌어 새로 불러왔어요",
  )
  expect(
    (
      within(dialog).getByRole("button", {
        name: "확인하고 계속",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true)
})
