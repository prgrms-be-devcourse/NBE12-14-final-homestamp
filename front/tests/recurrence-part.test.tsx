import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockStore } from "../src/api/mock/store"
import { Dialog, DialogContent } from "../src/components/ui/dialog"
import { RepairCreateForm } from "../src/features/repair/repair-create-form"
import { ZONE_PART_ERROR } from "../src/lib/record-commands"

function setup(
  state: "hidden" | "disabled" | "missing" | "available",
  legacy = false,
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
    parts.push({ ...original, id: "same-name-other-part" })
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

  test("사용 가능한 이전 부위 ID가 있으면 같은 이름이 있어도 원래 부위를 유지해요", async () => {
    const { submit } = setup("available")
    const field = await screen.findByRole("combobox", { name: "부위" })
    await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러 \(1\)/))
    submit()
    expect(screen.getByRole("alert").textContent).toBe(
      "수리 전 사진을 1장 이상 첨부해 주세요.",
    )
  })

  test("부위 ID가 없는 이전 기록은 기존 구역·이름으로 찾아요", async () => {
    const { submit } = setup("available", true)
    const field = await screen.findByRole("combobox", { name: "부위" })
    await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러 \(1\)/))
    submit()
    expect(screen.getByRole("alert").textContent).toBe(
      "수리 전 사진을 1장 이상 첨부해 주세요.",
    )
  })
})
