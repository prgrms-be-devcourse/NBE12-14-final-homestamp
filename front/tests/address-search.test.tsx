import { expect, test } from "vitest"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createHttpApi } from "../src/api/http-api"
import type { HomeStampApi } from "../src/api/types"
import { AddressSearch } from "../src/components/address-search"

// 테스트 대역: 서버(GET /api/addresses)가 돌려준다고 가정한 응답. 도로명·번지·건물명·우편번호가 섞여 와도 버려야 해요.
const serverBody = [
  {
    sido: "서울특별시",
    sigungu: "마포구",
    dong: "연남동",
    label: "서울특별시 마포구 연남로 1 (연남동)",
    roadAddr: "연남로 1",
    bdNm: "어떤빌딩",
    zipNo: "03991",
  },
]

test("서버 응답에서 시·도·시·군·구·읍·면·동과 보여 줄 이름만 남긴다", async () => {
  let asked = ""
  const api = createHttpApi((async (_method: string, path: string) => {
    asked = path
    return serverBody
  }) as never)
  const list = await api.searchAddress("연남로 1")
  expect(asked).toBe("/api/addresses?keyword=%EC%97%B0%EB%82%A8%EB%A1%9C%201")
  expect(list).toEqual([
    {
      sido: "서울특별시",
      sigungu: "마포구",
      dong: "연남동",
      label: "서울특별시 마포구 연남로 1 (연남동)",
    },
  ])
})

test("주소 검색 → 결과 선택 → 저장 칸에는 시·도 시·군·구 읍·면·동만 들어간다", async () => {
  const api = createHttpApi((async () => serverBody) as never) as HomeStampApi
  const { container } = render(
    <ApiProvider api={api}>
      <form>
        <AddressSearch name="address" inputId="address-search" />
      </form>
    </ApiProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: /주소 검색/ }))
  fireEvent.change(screen.getByPlaceholderText(/연남로/), {
    target: { value: "연남로 1" },
  })
  fireEvent.keyDown(screen.getByPlaceholderText(/연남로/), { key: "Enter" })
  fireEvent.click(
    await screen.findByRole("button", { name: /서울특별시 마포구 연남동/ }),
  )
  await waitFor(() =>
    expect(
      (container.querySelector("input[name=address]") as HTMLInputElement)
        .value,
    ).toBe("서울특별시 마포구 연남동"),
  )
  expect(container.innerHTML).not.toContain("연남로 1")
  expect(screen.getByText("번지·건물명은 저장하지 않아요.")).toBeTruthy()
})

test("검색어가 2글자 미만이면 서버에 묻지 않고 안내한다", async () => {
  let called = false
  const api = createHttpApi((async () => {
    called = true
    return []
  }) as never)
  render(
    <ApiProvider api={api}>
      <AddressSearch name="address" inputId="address-search" />
    </ApiProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: /주소 검색/ }))
  fireEvent.click(screen.getByRole("button", { name: "검색" }))
  expect(await screen.findByRole("alert")).toBeTruthy()
  expect(called).toBe(false)
})
