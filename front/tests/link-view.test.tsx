import { StrictMode } from "react"
import { afterEach, expect, test, vi } from "vitest"
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react"
import App from "../src/App"
import { LinkViewPage } from "../src/features/link/link-view-page"
import type { LinkView } from "../src/api/link-view"

// S-16 링크 열람(06 LINK-1·2, #303 응답 모양). 서버 응답을 그대로 보여 주고, 문구·상태를 화면이 다시 만들지 않아요.

const INSPECTION: LinkView = {
  kind: "RECORD",
  sido: "서울특별시",
  sigungu: "강남구",
  dong: "역삼동",
  yourSide: "LANDLORD",
  hasMoney: false,
  record: {
    targetKind: "INSPECTION",
    partName: null,
    title: "입주 점검",
    description: null,
    statusLines: ["확인 요청", "11월 20일 기준"],
    photos: [],
    history: [
      {
        label: "세입자가 점검을 시작했어요",
        side: "TENANT",
        at: "2026-11-19T01:00:00Z",
      },
      {
        label: "세면대 · 세입자가 하자를 더했어요",
        side: "TENANT",
        at: "2026-11-19T01:05:00Z",
      },
    ],
    items: [
      {
        partName: "세면대",
        description: "수전에서 물이 새요",
        statusLine: "확인 기다림",
        photos: [
          {
            id: "p-shown",
            thumbUrl: "https://photos.invalid/thumb.jpg",
            originalUrl: "https://photos.invalid/original.jpg",
            expiresAt: "2026-11-19T01:10:00Z",
            erased: false,
            erasedAt: null,
          },
          {
            id: "p-erased",
            thumbUrl: null,
            originalUrl: null,
            expiresAt: null,
            erased: true,
            erasedAt: "2026-11-21T03:00:00Z",
          },
        ],
      },
      {
        partName: "벽지",
        description: null,
        statusLine: "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요",
        photos: [],
      },
    ],
  },
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  })
const invalid = () =>
  json(404, {
    status: 404,
    code: "LINK_INVALID",
    detail: "더 이상 열 수 없는 링크예요",
  })

function mockFetch(...answers: (() => Response | Promise<Response>)[]) {
  const queue = [...answers]
  return vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
    const next = queue.shift()
    if (!next) throw new Error("예상하지 않은 요청")
    return next()
  })
}

const open = (token: string) =>
  window.history.replaceState(null, "", `/l#${token}`)
const page = (member = false) => {
  const handlers = { onSignup: vi.fn(), onLogin: vi.fn(), onContinue: vi.fn() }
  render(<LinkViewPage member={member} {...handlers} />)
  return handlers
}

afterEach(() => {
  vi.useRealTimers()
})

test("주소 끝 조각의 토큰을 본문으로 보내고 로그인 토큰·멱등 키를 붙이지 않는다", async () => {
  open("tok-1")
  const fetch = mockFetch(() => json(200, INSPECTION))
  page()

  await screen.findByText("입주 점검")
  expect(fetch).toHaveBeenCalledTimes(1)
  const [url, init] = fetch.mock.calls[0]
  expect(url).toBe("/api/public/links/view")
  expect(String(url)).not.toContain("tok-1")
  expect(init?.method).toBe("POST")
  expect(JSON.parse(String(init?.body))).toEqual({ token: "tok-1" })
  const headers = init?.headers as Record<string, string>
  expect(headers.Authorization).toBeUndefined()
  expect(headers["Idempotency-Key"]).toBeUndefined()
})

test("점검 링크는 보낸 측과 항목마다 부위·설명·서버 상태 문구·그 항목의 사진, 경과를 보여 준다", async () => {
  open("tok-1")
  mockFetch(() => json(200, INSPECTION))
  const { container } = render(
    <LinkViewPage
      member={false}
      onSignup={vi.fn()}
      onLogin={vi.fn()}
      onContinue={vi.fn()}
    />,
  )

  // 링크를 보낸 측은 받는 측(집주인)의 반대. 이름이 아니라 측 말이다
  expect(
    await screen.findByText(
      "서울특별시 강남구 역삼동의 한 호실 · 세입자가 보낸 기록이에요",
    ),
  ).toBeTruthy()
  expect(screen.getByText("확인 요청")).toBeTruthy()
  expect(screen.getByText("11월 20일 기준")).toBeTruthy()

  const items = within(screen.getByRole("list", { name: "항목" })).getAllByRole(
    "listitem",
  )
  expect(items).toHaveLength(2)
  const [sink, wall] = items
  expect(within(sink).getByText("세면대")).toBeTruthy()
  expect(within(sink).getByText("수전에서 물이 새요")).toBeTruthy()
  expect(within(sink).getByText("확인 기다림")).toBeTruthy()
  expect(within(sink).getByRole("img").getAttribute("src")).toBe(
    "https://photos.invalid/thumb.jpg",
  )
  expect(within(sink).getByText(/지운 사진이에요 · 11\. 21\./)).toBeTruthy()
  expect(within(sink).queryByText("사진 없음")).toBeNull()
  // 사진이 없는 항목은 "사진 없음"이고 다른 항목의 사진이 섞이지 않는다
  expect(
    within(wall).getByText(
      "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요",
    ),
  ).toBeTruthy()
  expect(within(wall).getByText("사진 없음")).toBeTruthy()
  expect(within(wall).queryByRole("img")).toBeNull()

  const history = screen.getByRole("region", { name: "경과" })
  expect(
    within(history).getByText(/세면대 · 세입자가 하자를 더했어요/),
  ).toBeTruthy()

  // 답하기 버튼은 없다(LINK-1b). 사진 ID·이름·번호가 화면 글자에 없다
  expect(screen.queryByRole("button", { name: /맞아요|보류|이견/ })).toBeNull()
  expect(container.textContent).not.toMatch(/p-shown|p-erased|님|010/)
  expect(
    screen.getByText("여기서 보기만 해서는 답한 것으로 남지 않아요"),
  ).toBeTruthy()
  expect(screen.getByRole("button", { name: "가입하고 응답하기" })).toBeTruthy()
})

test("LINK_INVALID만 무효 화면이고 서버 내부 오류·연결 오류는 다시 불러오기를 준다", async () => {
  open("gone")
  mockFetch(invalid)
  page()
  expect(await screen.findByText("더 이상 열 수 없는 링크예요")).toBeTruthy()
  expect(screen.queryByRole("button", { name: "다시 불러오기" })).toBeNull()
})

test("서버 내부 오류는 무효 링크로 보이지 않고, 다시 불러오면 새로 요청해 연다", async () => {
  open("tok-1")
  const fetch = mockFetch(
    () => json(500, { status: 500, code: "INTERNAL", detail: "서버 오류" }),
    () => json(200, INSPECTION),
  )
  page()
  expect(
    await screen.findByText(/잠시 문제가 생겨 링크를 열지 못했어요/),
  ).toBeTruthy()
  expect(screen.queryByText("더 이상 열 수 없는 링크예요")).toBeNull()

  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }))
  await screen.findByText("입주 점검")
  expect(fetch).toHaveBeenCalledTimes(2)
})

test("본문 없는 429는 조회 한도 안내, 503은 서버 오류이고 둘 다 무효 링크가 아니다", async () => {
  open("tok-1")
  mockFetch(() => new Response("", { status: 429 }))
  page()
  expect(await screen.findByText(/잠깐 사이에 너무 많이 열었어요/)).toBeTruthy()
  expect(screen.queryByText("더 이상 열 수 없는 링크예요")).toBeNull()
  cleanup()
  vi.restoreAllMocks()

  open("tok-2")
  mockFetch(() => new Response("", { status: 503 }))
  page()
  expect(
    await screen.findByText(/잠시 문제가 생겨 링크를 열지 못했어요/),
  ).toBeTruthy()
})

test("수리비 기록이 있으면 금액 없이 있다는 한 줄만, 읽을 수 없는 날짜가 와도 화면이 멈추지 않는다", async () => {
  open("tok-1")
  mockFetch(() =>
    json(200, {
      ...INSPECTION,
      hasMoney: true,
      record: {
        ...INSPECTION.record!,
        history: [
          {
            label: "세입자가 점검을 시작했어요",
            side: "TENANT",
            at: "not-a-date",
          },
        ],
      },
    }),
  )
  page()
  expect(
    await screen.findByText(
      "이 건에 수리비 기록이 있어요 · 가입하면 볼 수 있어요",
    ),
  ).toBeTruthy()
  expect(screen.getByText(/세입자가 점검을 시작했어요/)).toBeTruthy()
})

test("연결 오류는 그 안내와 다시 불러오기다", async () => {
  open("tok-1")
  vi.spyOn(globalThis, "fetch").mockRejectedValue(
    new TypeError("Failed to fetch"),
  )
  page()
  expect(
    await screen.findByText("연결이 불안정해요. 잠시 뒤 다시 시도해 주세요."),
  ).toBeTruthy()
  expect(screen.getByRole("button", { name: "다시 불러오기" })).toBeTruthy()
})

test("토큰이 없으면 요청하지 않고 무효 화면이다", async () => {
  window.history.replaceState(null, "", "/l")
  const fetch = mockFetch()
  page()
  expect(screen.getByText("더 이상 열 수 없는 링크예요")).toBeTruthy()
  expect(fetch).not.toHaveBeenCalled()
})

test("다른 링크로 바뀌면 앞 링크의 늦은 응답을 보이지 않는다", async () => {
  open("first")
  let releaseFirst: (r: Response) => void = () => undefined
  mockFetch(
    () => new Promise<Response>((resolve) => (releaseFirst = resolve)),
    invalid,
  )
  page()
  await screen.findByText("링크를 여는 중이에요")

  act(() => {
    window.location.hash = "second"
    window.dispatchEvent(new HashChangeEvent("hashchange"))
  })
  await screen.findByText("더 이상 열 수 없는 링크예요")
  await act(async () => releaseFirst(json(200, INSPECTION)))

  expect(screen.queryByText("입주 점검")).toBeNull()
  expect(screen.getByText("더 이상 열 수 없는 링크예요")).toBeTruthy()
})

test("개발 모드의 두 번 그리기에도 같은 링크를 한 번만 요청한다", async () => {
  open("tok-1")
  const fetch = mockFetch(() => json(200, INSPECTION))
  render(
    <StrictMode>
      <LinkViewPage
        member={false}
        onSignup={vi.fn()}
        onLogin={vi.fn()}
        onContinue={vi.fn()}
      />
    </StrictMode>,
  )
  await screen.findByText("입주 점검")
  expect(fetch).toHaveBeenCalledTimes(1)
})

test("사진을 못 불러오면 사진 없음과 다르게 알리고, 다시 불러오면 새 주소를 받는다", async () => {
  open("tok-1")
  const fetch = mockFetch(
    () => json(200, INSPECTION),
    () => json(200, INSPECTION),
  )
  page()
  const img = await screen.findByRole("img")
  fireEvent.error(img)

  expect(screen.getByText(/사진을 불러오지 못했어요/)).toBeTruthy()
  fireEvent.click(screen.getByRole("button", { name: "다시 불러오기" }))
  await screen.findByRole("img")
  expect(fetch).toHaveBeenCalledTimes(2)
})

test("호실 초대 링크는 지역과 받는 측, 가입하고 연결하기 안내만 보여 준다", async () => {
  open("room")
  mockFetch(() =>
    json(200, {
      kind: "ROOM_INVITE",
      sido: "세종특별자치시",
      sigungu: null,
      dong: "어진동",
      yourSide: "TENANT",
      record: null,
      hasMoney: false,
    }),
  )
  const { onSignup } = page()
  expect(
    await screen.findByText(
      "세종특별자치시 어진동의 한 호실에 세입자로 초대받았어요",
    ),
  ).toBeTruthy()
  expect(
    screen.queryByText("여기서 보기만 해서는 답한 것으로 남지 않아요"),
  ).toBeNull()
  fireEvent.click(screen.getByRole("button", { name: "가입하고 연결하기" }))
  expect(onSignup).toHaveBeenCalled()
  expect(
    screen.getByRole("button", {
      name: "이미 회원이에요: 로그인하고 연결하기",
    }),
  ).toBeTruthy()
})

test("정산 초대 링크는 요청이 있다는 것과 가입하고 확인하기만 보여 준다", async () => {
  open("settle")
  mockFetch(() =>
    json(200, {
      kind: "SETTLEMENT_INVITE",
      sido: null,
      sigungu: null,
      dong: null,
      yourSide: null,
      record: null,
      hasMoney: false,
    }),
  )
  page()
  expect(await screen.findByText("퇴거 정산 확인 요청이 있어요")).toBeTruthy()
  expect(screen.getByRole("button", { name: "가입하고 확인하기" })).toBeTruthy()
})

test("받는 측이 빠진 기록 응답은 측을 짐작하지 않고 서버 오류로 보여 준다", async () => {
  open("tok-1")
  mockFetch(() => json(200, { ...INSPECTION, yourSide: null }))
  page()
  expect(
    await screen.findByText(/잠시 문제가 생겨 링크를 열지 못했어요/),
  ).toBeTruthy()
  expect(screen.queryByText(/보낸 기록이에요/)).toBeNull()
})

test("회원이 열면 가입 대신 받은 초대로 이어 간다", async () => {
  open("tok-1")
  mockFetch(() => json(200, INSPECTION))
  const { onContinue } = page(true)
  fireEvent.click(await screen.findByRole("button", { name: "앱으로 가기" }))
  expect(onContinue).toHaveBeenCalled()
  expect(screen.queryByRole("button", { name: "가입하고 응답하기" })).toBeNull()
})

test("앱에서 링크를 열면 로그인 화면 대신 링크 화면이고, 가입으로 갔다가 돌아와도 링크 화면이다", async () => {
  open("tok-app")
  mockFetch(
    () => json(200, INSPECTION),
    () => json(200, INSPECTION),
  )
  render(<App />)
  fireEvent.click(
    await screen.findByRole("button", { name: "가입하고 응답하기" }),
  )
  expect(
    await screen.findByText(/링크로 받은 기록에 답하거나 호실에 연결하려면/),
  ).toBeTruthy()
  expect(screen.getByLabelText(/휴대폰 번호/)).toBeTruthy()

  fireEvent.click(screen.getByRole("button", { name: "공유받은 기록으로" }))
  expect(await screen.findByText("입주 점검")).toBeTruthy()
  expect(window.location.hash).toBe("#tok-app")
})

test("링크 화면에서 로그인을 마치면 주소의 토큰을 지우고 앱으로 간다", async () => {
  open("tok-app")
  mockFetch(() => json(200, INSPECTION))
  render(<App />)
  fireEvent.click(
    await screen.findByRole("button", {
      name: "이미 회원이에요: 로그인하고 응답하기",
    }),
  )
  fireEvent.change(await screen.findByLabelText("이메일"), {
    target: { value: "test@gmail.com" },
  })
  fireEvent.change(screen.getByLabelText("비밀번호"), {
    target: { value: "aaaa1111" },
  })
  fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }))

  await screen.findAllByText(/이 호실에서 세입자 쪽/)
  expect(window.location.pathname).toBe("/")
  expect(window.location.hash).toBe("")
})
