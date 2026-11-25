import { expect, test, vi } from "vitest"
import { fireEvent, render, screen, within } from "@testing-library/react"
import App from "../src/App"
import { activeTabOf, nav } from "../src/components/common/app-nav"
import { parsePath, toPath, type Route } from "../src/lib/router"

const base: Route = { page: "home", roomId: "302", repairId: "leak" }

test("주소와 화면 상태를 서로 바꿀 수 있다", () => {
  expect(parsePath("/rooms/302/repairs/leak", base)).toMatchObject({
    page: "detail",
    roomId: "302",
    repairId: "leak",
    notFound: false,
  })
  expect(parsePath("/rooms/302/checkout", base).page).toBe("checkout")
  expect(parsePath("/notifications", base).page).toBe("notifications")
  expect(parsePath("/없는/주소", base).notFound).toBe(true)
  expect(toPath({ ...base, page: "detail" })).toBe("/rooms/302/repairs/leak")
  expect(toPath({ ...base, page: "money" })).toBe("/rooms/302/money")
  expect(toPath({ ...base, roomId: "" })).toBe("/")
})

test("로그인 전에는 로그인 화면만 보이고 호실 메뉴는 없다", async () => {
  render(<App />)
  expect(
    await screen.findByRole("button", { name: "로그인", exact: true }),
  ).toBeTruthy()
  expect(screen.queryByRole("navigation", { name: "주 메뉴" })).toBeNull()
})

test("휴대폰 인증 → 계정 → 확인 항목으로 가입하고, 호실을 만들면 그 호실의 내 측으로 대시보드가 열린다", async () => {
  const info = vi.spyOn(console, "info").mockImplementation(() => {})
  render(<App />)
  fireEvent.click(await screen.findByRole("tab", { name: "회원가입" }))
  fireEvent.change(screen.getByLabelText("휴대폰 번호"), {
    target: { value: "01012345678" },
  })
  fireEvent.click(screen.getByRole("button", { name: "인증번호 받기" }))
  // 서버 없이 개발할 때는 문자를 보내지 않았다고 알리고, 인증번호는 콘솔에만 남겨요.
  expect(await screen.findByText(/문자를 보내지 않았어요/)).toBeTruthy()
  const code = String(info.mock.calls.at(-1)?.[0]).match(/(\d{6})$/)![1]
  fireEvent.change(screen.getByLabelText(/인증번호 6자리/), {
    target: { value: "000000" === code ? "111111" : "000000" },
  })
  fireEvent.click(screen.getByRole("button", { name: "인증하기" }))
  expect(await screen.findByRole("alert")).toBeTruthy()
  fireEvent.change(screen.getByLabelText(/인증번호 6자리/), {
    target: { value: code },
  })
  fireEvent.click(screen.getByRole("button", { name: "인증하기" }))
  fireEvent.change(await screen.findByLabelText("이메일 · 로그인 아이디"), {
    target: { value: "me@example.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "abcdefgh" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호 확인"), {
    target: { value: "abcdefgh" },
  })
  fireEvent.change(screen.getByLabelText(/^이름/), {
    target: { value: "정서윤" },
  })
  fireEvent.click(screen.getByRole("button", { name: /다음/ }))
  expect(screen.getByRole("alert").textContent).toMatch(/영문과 숫자/)
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "abc12345" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호 확인"), {
    target: { value: "abc12345" },
  })
  fireEvent.click(screen.getByRole("button", { name: /다음/ }))
  const boxes = await screen.findAllByRole("checkbox")
  boxes.forEach((b) => fireEvent.click(b))
  fireEvent.click(screen.getByRole("button", { name: "가입하기" }))
  expect(await screen.findByText("정서윤님, 환영해요")).toBeTruthy()
  expect(screen.queryByText(/전환/)).toBeNull()

  fireEvent.click(screen.getByRole("button", { name: /호실 기록 만들기/ }))
  fireEvent.click(
    await screen.findByRole("radio", { name: /이 집을 빌려줬어요/ }),
  )
  fireEvent.change(screen.getByLabelText(/주소/), {
    target: { value: "연남" },
  })
  fireEvent.click(screen.getByRole("button", { name: /^찾기$/ }))
  fireEvent.click(
    await screen.findByRole("button", { name: /서울특별시 마포구 연남동/ }),
  )
  fireEvent.change(screen.getByLabelText("호실 별칭"), {
    target: { value: "302호" },
  })
  fireEvent.submit(screen.getByRole("dialog").querySelector("form")!)
  expect(await screen.findByText("정서윤님, 안녕하세요")).toBeTruthy()
  expect(screen.getAllByText(/이 호실에서 집주인 쪽/).length).toBeGreaterThan(0)
  info.mockRestore()
})

test("주 메뉴는 4개 핵심 탭으로 통합되고 세부 화면은 서브탭으로 전환된다", async () => {
  expect(nav.map((n) => n.id)).toEqual([
    "home",
    "repairs",
    "inspection",
    "rooms",
  ])
  expect(activeTabOf("money")).toBe("repairs")
  expect(activeTabOf("detail")).toBe("repairs")
  expect(activeTabOf("checkout")).toBe("inspection")
  expect(activeTabOf("connection")).toBe("rooms")
  expect(activeTabOf("timeline")).toBe("rooms")

  render(<App />)
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "aaaa1111" },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))
  await screen.findAllByText(/이 호실에서 세입자 쪽/)

  const desktopNav = screen.getByRole("navigation", { name: "주 메뉴" })
  const mobileNav = screen.getByRole("navigation", { name: "모바일 주 메뉴" })
  expect(within(desktopNav).getAllByRole("button")).toHaveLength(4)
  expect(within(mobileNav).getAllByRole("button")).toHaveLength(4)

  fireEvent.click(
    within(desktopNav).getByRole("button", { name: /하자 · 수리/ }),
  )
  const repairSubtabs = await screen.findByRole("tablist", {
    name: "세부 메뉴",
  })
  expect(
    within(repairSubtabs).getByRole("tab", { name: /수리 진행/ }),
  ).toBeTruthy()
  fireEvent.click(
    within(repairSubtabs).getByRole("tab", { name: /수리비 · 부담/ }),
  )
  expect(await screen.findByText("수리 건별 비용 부담 현황")).toBeTruthy()

  fireEvent.click(
    within(desktopNav).getByRole("button", { name: /호실 · 연결/ }),
  )
  const roomSubtabs = await screen.findByRole("tablist", { name: "세부 메뉴" })
  fireEvent.click(within(roomSubtabs).getByRole("tab", { name: /활동 이력/ }))
  expect(
    within(roomSubtabs)
      .getByRole("tab", { name: /활동 이력/ })
      .getAttribute("aria-selected"),
  ).toBe("true")
})
