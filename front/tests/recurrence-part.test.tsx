import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockStore } from "../src/api/mock/store"
import { Dialog, DialogContent } from "../src/components/ui/dialog"
import { RepairCreateForm } from "../src/features/repair/repair-create-form"
import { ZONE_PART_ERROR } from "../src/lib/record-commands"

// legacy: 부위 ID가 없는 이전 기록(구역·이름만 있음). duplicate: 같은 구역에 이름이 같은 부위를 하나 더 둠
function setup(
  state: "hidden" | "disabled" | "missing" | "available",
  legacy = false,
  duplicate = true,
) {
  const store = createMockStore(createSampleSeed)
  const original = store
    .getState()
    .roomParts!["302"].find((p) => p.name === "보일러")!
  store.setState((d) => {
    const next = structuredClone(d)
    const parts = next.roomParts!["302"]
    const part = parts.find((p) => p.id === original.id)!
    if (state === "hidden") part.hidden = true
    if (state === "disabled") part.enabled = false
    if (state === "missing") parts.splice(parts.indexOf(part), 1)
    if (duplicate) parts.push({ ...original, id: "same-name-other-part" })
    return next
  })
  const api = createMockApi(store)
  const create = vi.spyOn(api.repairCases, "create")
  const view = render(
    <ApiProvider api={api} accountId="dev-tenant">
      <Dialog open>
        <DialogContent>
          <RepairCreateForm
            room={store.getState().rooms.find((r) => r.id === "302")!}
            accountId="dev-tenant"
            prefill={{
              recurrenceOf: "previous-repair",
              recurrenceTitle: "보일러 고장",
              roomPartId: legacy ? undefined : original.id,
              zone: "설비·가전 · 보일러",
            }}
            onCreated={vi.fn()}
            onCancel={vi.fn()}
          />
        </DialogContent>
      </Dialog>
    </ApiProvider>,
  )
  const submit = () => fireEvent.submit(view.baseElement.querySelector("form")!)
  return { create, submit }
}

describe("재발 등록의 이전 부위", () => {
  test.each(["hidden", "disabled", "missing"] as const)(
    "%s 부위는 같은 이름의 다른 부위로 대체하지 않고 다시 고르게 해요",
    async (state) => {
      const { create, submit } = setup(state)
      const field = await screen.findByRole("combobox", { name: "부위" })
      await vi.waitFor(() => expect(field.textContent).toMatch(/^부위 선택/))
      submit()
      expect(screen.getByRole("alert").textContent).toBe(ZONE_PART_ERROR)
      expect(create).not.toHaveBeenCalled()

      fireEvent.click(field)
      const option = await screen.findByRole("option", { name: "보일러" })
      option.focus()
      fireEvent.keyDown(option, { key: "Enter" })
      await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러/))
      submit()
      expect(screen.getByRole("alert").textContent).toBe(
        "수리 전 사진을 1장 이상 첨부해 주세요.",
      )
      expect(create).not.toHaveBeenCalled()
    },
  )

  // 목록을 받기 전에는 부위 칸이 비어 있어요(불러오는 중). 이전 부위 ID만으로 보내지 않아요(#402)
  test.each(["hidden", "disabled", "missing", "available"] as const)(
    "%s 이전 부위도 목록을 받기 전에 보내면 부위를 고르지 않은 것으로 봐요",
    async (state) => {
      const { create, submit } = setup(state)
      expect(screen.getByText("부위 목록을 불러오는 중이에요.")).toBeTruthy()
      submit()
      expect(screen.getByRole("alert").textContent).toBe(ZONE_PART_ERROR)
      expect(create).not.toHaveBeenCalled()
      await screen.findByRole("combobox", { name: "부위" })
    },
  )

  test("사용 가능한 이전 부위 ID가 있으면 같은 이름이 있어도 원래 부위를 유지해요", async () => {
    const { submit } = setup("available")
    const field = await screen.findByRole("combobox", { name: "부위" })
    await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러 \(1\)/))
    submit()
    expect(screen.getByRole("alert").textContent).toBe(
      "수리 전 사진을 1장 이상 첨부해 주세요.",
    )
  })

  test("부위 ID가 없는 이전 기록은 같은 구역·이름의 부위가 하나뿐이고 고를 수 있으면 그 부위를 골라요", async () => {
    const { submit } = setup("available", true, false)
    const field = await screen.findByRole("combobox", { name: "부위" })
    await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러/))
    submit()
    expect(screen.getByRole("alert").textContent).toBe(
      "수리 전 사진을 1장 이상 첨부해 주세요.",
    )
  })

  // 같은 이름이 둘이면 어느 부위인지 알 수 없어요. 원래 부위가 숨김·꺼 둠이어도 보이는 같은 이름 부위로 바꾸지 않아요
  test.each([
    ["같은 이름 부위가 둘", "available", true],
    ["하나뿐인 부위를 숨김", "hidden", false],
    ["하나뿐인 부위를 꺼 둠", "disabled", false],
    ["숨긴 원래 부위 + 보이는 같은 이름 부위", "hidden", true],
    ["꺼 둔 원래 부위 + 켜진 같은 이름 부위", "disabled", true],
  ] as const)(
    "부위 ID가 없는 이전 기록에서 %s이면 저절로 고르지 않고 다시 고르게 해요",
    async (_label, state, duplicate) => {
      const { create, submit } = setup(state, true, duplicate)
      const field = await screen.findByRole("combobox", { name: "부위" })
      // 목록을 받고 기본 부위 고르기가 끝난 뒤에도(구역 칸은 이전 구역) 부위는 비어 있어요
      await new Promise((r) => setTimeout(r, 50))
      await vi.waitFor(() =>
        expect(
          screen.getByRole("combobox", { name: "구역" }).textContent,
        ).toMatch(/^설비·가전/),
      )
      expect(field.textContent).toMatch(/^부위 선택/)
      submit()
      expect(screen.getByRole("alert").textContent).toBe(ZONE_PART_ERROR)
      expect(create).not.toHaveBeenCalled()
    },
  )
})
