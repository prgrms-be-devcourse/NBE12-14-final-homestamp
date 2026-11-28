import { useState } from "react"
import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen, within } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockRoomParts } from "../src/api/mock/parts"
import { createMockStore } from "../src/api/mock/store"
import { PartField } from "../src/components/common/part-field"
import { RoomPartsCard } from "../src/features/room/room-parts-card"
import { partsLockedReason } from "../src/lib/workflows"
import type { Room } from "../src/data/demo"
import type { RoomPart } from "../src/lib/room-parts"

// 부위 숨기기·다시 보이기·설비·가전 켜고 끔(06 4-3, ROOM-3a·3b·3d, #456)
const tenant = { accountId: "dev-tenant" }
const setup = () => {
  const store = createMockStore(createSampleSeed)
  return { store, parts: createMockRoomParts(store) }
}
const find = (list: RoomPart[], zone: string, name: string) =>
  list.find((p) => p.zone === zone && p.name === name)!

describe("목업 부위 바꾸기", () => {
  test("숨기면 목록에 hidden으로 남고, 다시 보이게 하면 돌아와요. 이미 그 상태면 INVALID_STATE(ROOM-3a·3b)", async () => {
    const { store, parts } = setup()
    const closet = find(
      (await parts.list(tenant, "302")).parts,
      "방",
      "붙박이장",
    )
    await expect(parts.hide(tenant, "302", closet.id)).resolves.toMatchObject({
      id: closet.id,
      hidden: true,
    })
    expect(store.getState().events[0].text).toBe("부위 숨기기 · 방 · 붙박이장")
    expect(
      find((await parts.list(tenant, "302")).parts, "방", "붙박이장").hidden,
    ).toBe(true)
    await expect(parts.hide(tenant, "302", closet.id)).rejects.toMatchObject({
      status: 409,
      code: "INVALID_STATE",
    })
    await expect(parts.show(tenant, "302", closet.id)).resolves.toMatchObject({
      hidden: false,
    })
    expect(store.getState().events[0].text).toBe(
      "부위 다시 보이기 · 방 · 붙박이장",
    )
    await expect(parts.show(tenant, "302", closet.id)).rejects.toMatchObject({
      code: "INVALID_STATE",
    })
  })

  test("켜고 끔은 원하는 값으로 하고 숨김은 그대로예요. 이미 그 값이면 INVALID_STATE, 설비·가전이 아니면 VALIDATION_FAILED(ROOM-3d)", async () => {
    const { store, parts } = setup()
    const list = (await parts.list(tenant, "302")).parts
    const fridge = find(list, "설비·가전", "냉장고")
    await expect(
      parts.toggle(tenant, "302", fridge.id, false),
    ).resolves.toMatchObject({ enabled: false, hidden: false })
    expect(store.getState().events[0].text).toBe(
      "설비 끄기 · 설비·가전 · 냉장고",
    )
    await expect(
      parts.toggle(tenant, "302", fridge.id, false),
    ).rejects.toMatchObject({ status: 409, code: "INVALID_STATE" })
    await expect(
      parts.toggle(tenant, "302", fridge.id, true),
    ).resolves.toMatchObject({ enabled: true })
    // 직접 더한 부위도 설비·가전 구역이지만 기본 목록이 아니라 켜고 끌 수 없어요
    const mine = await parts.add(tenant, "302", {
      zone: "설비·가전",
      name: "건조기",
    })
    for (const id of [find(list, "방", "벽").id, mine.id])
      await expect(
        parts.toggle(tenant, "302", id, false),
      ).rejects.toMatchObject({ status: 400, code: "VALIDATION_FAILED" })
  })

  test("그 호실의 부위가 아니면 NOT_FOUND, 정산 완료면 SETTLED, 합류 전 기록이면 JOINED_RECORD, 거주 종료 뒤에는 돼요", async () => {
    const { store, parts } = setup()
    const other = (await parts.list(tenant, "201")).parts[0]
    await expect(parts.hide(tenant, "302", other.id)).rejects.toMatchObject({
      status: 404,
      code: "NOT_FOUND",
    })
    const wall = find((await parts.list(tenant, "302")).parts, "방", "벽")
    const patch = (p: Partial<Room>) =>
      store.setState((d) => ({
        ...d,
        rooms: d.rooms.map((r) => (r.id === "302" ? { ...r, ...p } : r)),
      }))
    patch({ status: "거주 종료" })
    await expect(parts.hide(tenant, "302", wall.id)).resolves.toMatchObject({
      hidden: true,
    })
    patch({ status: "정산 완료" })
    await expect(parts.show(tenant, "302", wall.id)).rejects.toMatchObject({
      status: 409,
      code: "SETTLED",
    })
    patch({ status: "거주 중", preJoinOf: "201" })
    await expect(parts.show(tenant, "302", wall.id)).rejects.toMatchObject({
      status: 409,
      code: "JOINED_RECORD",
    })
  })
})

describe("부위 관리 카드", () => {
  // 하자 등록 창처럼 같은 호실의 부위 칸이 함께 떠 있는 화면
  function Screen({ canEdit = true }: { canEdit?: boolean }) {
    const [value, setValue] = useState("")
    return (
      <>
        <RoomPartsCard
          roomId="302"
          canEdit={canEdit}
          lockedReason="공유를 그만둔 동안에는 부위를 바꿀 수 없어요."
        />
        <PartField
          roomId="302"
          value={value}
          onChange={(p) => setValue(p?.id ?? "")}
          defaultPart={{ zone: "방", name: "벽" }}
        />
      </>
    )
  }
  const renderScreen = (canEdit?: boolean) => {
    const store = createMockStore(createSampleSeed)
    render(
      <ApiProvider api={createMockApi(store)} accountId="dev-tenant">
        <Screen canEdit={canEdit} />
      </ApiProvider>,
    )
    return store
  }
  const partOptions = async () => {
    fireEvent.click(await screen.findByRole("combobox", { name: "부위" }))
    await screen.findAllByRole("option")
    const names = screen.queryAllByRole("option").map((o) => o.textContent)
    // 고르기 칸을 닫아요
    fireEvent.keyDown(document.activeElement ?? document.body, {
      key: "Escape",
    })
    return names
  }
  const openFold = async (title: string) =>
    fireEvent.click(
      await screen.findByRole("button", { name: new RegExp(title) }),
    )

  test("숨기면 부위 칸에서 빠지고 숨긴 부위 묶음에 생기며, 다시 보이게 하면 돌아와요", async () => {
    const store = renderScreen()
    expect(await partOptions()).toContain("붙박이장")
    await openFold("부위 관리")
    fireEvent.click(
      await screen.findByRole("button", { name: "방 · 붙박이장 숨기기" }),
    )
    await screen.findByText("방 · 붙박이장을(를) 숨겼어요.")
    await vi.waitFor(async () =>
      expect(await partOptions()).not.toContain("붙박이장"),
    )
    expect(
      find(store.getState().roomParts!["302"], "방", "붙박이장").hidden,
    ).toBe(true)
    await openFold("숨긴 부위")
    fireEvent.click(
      await screen.findByRole("button", { name: "방 · 붙박이장 다시 보이기" }),
    )
    await screen.findByText("방 · 붙박이장을(를) 다시 보이게 했어요.")
    await vi.waitFor(async () =>
      expect(await partOptions()).toContain("붙박이장"),
    )
  })

  // 꺼 둔 설비·가전이 부위 칸에서 빠지는 것은 room-parts.test(PartField)가 봐요
  test("설비·가전을 끄면 꺼 둠으로 보이고 켤 수 있어요. 숨김은 그대로예요", async () => {
    const store = renderScreen()
    await openFold("부위 관리")
    const zone = await screen.findByRole("region", { name: "설비·가전" })
    fireEvent.click(
      within(zone).getByRole("button", { name: "설비·가전 · 냉장고 끄기" }),
    )
    await screen.findByText("설비·가전 · 냉장고을(를) 껐어요.")
    expect(await within(zone).findByText("꺼 둠")).toBeTruthy()
    expect(
      find(store.getState().roomParts!["302"], "설비·가전", "냉장고"),
    ).toMatchObject({ enabled: false, hidden: false })
    fireEvent.click(
      await within(zone).findByRole("button", {
        name: "설비·가전 · 냉장고 켜기",
      }),
    )
    await screen.findByText("설비·가전 · 냉장고을(를) 켰어요.")
    await vi.waitFor(() => expect(within(zone).queryByText("꺼 둠")).toBeNull())
  })

  test("상대가 먼저 바꿨으면(INVALID_STATE) 서버 문구를 보이고 목록을 다시 받아요", async () => {
    const store = renderScreen()
    await openFold("부위 관리")
    await screen.findByRole("button", { name: "방 · 붙박이장 숨기기" })
    // 화면을 연 뒤 상대가 같은 부위를 숨겼어요
    store.setState((d) => {
      const next = structuredClone(d)
      find(next.roomParts!["302"], "방", "붙박이장").hidden = true
      return next
    })
    fireEvent.click(
      screen.getByRole("button", { name: "방 · 붙박이장 숨기기" }),
    )
    await screen.findByText("이미 숨긴 부위예요.")
    await vi.waitFor(() =>
      expect(
        screen.queryByRole("button", { name: "방 · 붙박이장 숨기기" }),
      ).toBeNull(),
    )
  })

  test("꺼 둔 설비·가전을 숨기면 숨긴 부위에 꺼 둠으로 보이고 개수에도 들어가요(숨김과 켜고 끔은 따로)", async () => {
    renderScreen()
    await openFold("부위 관리")
    fireEvent.click(
      await screen.findByRole("button", { name: "설비·가전 · 냉장고 끄기" }),
    )
    await screen.findByText("설비·가전 · 냉장고을(를) 껐어요.")
    fireEvent.click(
      await screen.findByRole("button", { name: "설비·가전 · 냉장고 숨기기" }),
    )
    await screen.findByText("설비·가전 · 냉장고을(를) 숨겼어요.")
    await screen.findByText(
      "보이는 부위 28개 · 숨긴 부위 1개 · 꺼 둔 설비·가전 1개",
    )
    await openFold("숨긴 부위")
    expect(
      await screen.findByText(
        "숨긴 설비·가전은 다시 보이게 한 뒤 켜고 끌 수 있어요.",
      ),
    ).toBeTruthy()
    const row = screen.getByText("설비·가전 · 냉장고").closest("li")!
    expect(within(row).getByText("꺼 둠")).toBeTruthy()
    expect(within(row).queryByRole("button", { name: /켜기/ })).toBeNull()
  })

  test("부위 바꾸기(parts)가 없으면 목록만 보이고 바꾸는 버튼 대신 이유를 알려요", async () => {
    renderScreen(false)
    await openFold("부위 관리")
    await screen.findByText("붙박이장")
    expect(
      screen.queryByRole("button", { name: /숨기기|끄기|켜기/ }),
    ).toBeNull()
    expect(screen.queryByRole("button", { name: "부위 더하기" })).toBeNull()
    expect(
      screen.getByText("공유를 그만둔 동안에는 부위를 바꿀 수 없어요."),
    ).toBeTruthy()
  })
})

test("바꿀 수 없는 이유: 정산 완료·합류 전 기록·공유를 그만둔 사람", () => {
  const base = createSampleSeed().rooms[0]
  expect(partsLockedReason({ ...base, status: "정산 완료" })).toMatch(
    "정산이 끝난",
  )
  expect(partsLockedReason({ ...base, preJoinOf: "201" })).toMatch("합류 전")
  expect(
    partsLockedReason({
      ...base,
      status: "거주 중",
      server: {
        ...base.server!,
        myStatus: "SHARING_STOPPED",
      } as Room["server"],
    }),
  ).toMatch("공유를 그만둔")
})
