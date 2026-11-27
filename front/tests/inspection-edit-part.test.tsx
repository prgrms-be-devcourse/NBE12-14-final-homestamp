import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockStore } from "../src/api/mock/store"
import { createDemo, type Inspection } from "../src/data/demo"
import { InspectionItemCorrections } from "../src/features/inspection/inspection-corrections"
import { ZONE_PART_ERROR } from "../src/lib/record-commands"

// 점검 항목 [고치기] 창의 부위 칸(#402): 화면이 "부위 선택"이면 지금 부위 ID로도 보내지 않아요
function setup(state: "hidden" | "disabled" | "missing" | "available") {
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
    return next
  })
  const item: Inspection = {
    id: "item-1",
    roomId: "302",
    kind: "in",
    title: "보일러 소음",
    zone: "설비·가전 · 보일러",
    roomPartId: original.id,
    description: "켤 때 소리가 나요",
    photos: [],
    ack: "상대 미확인",
    author: "임차인",
    art: "",
    publication: "draft",
  }
  const db = createDemo()
  db.inspections.push(item)
  const onAct = vi.fn()
  render(
    <ApiProvider api={createMockApi(store)} accountId="dev-tenant">
      <InspectionItemCorrections
        i={item}
        db={db}
        role="임차인"
        readOnly={false}
        onAct={onAct}
        actions={["corrections"]}
        correctionActionsOf={() => []}
      />
    </ApiProvider>,
  )
  fireEvent.click(screen.getByRole("button", { name: "더 보기" }))
  fireEvent.click(screen.getByRole("menuitem", { name: /고치기/ }))
  const submit = () =>
    fireEvent.click(screen.getByRole("button", { name: "고치기" }))
  return { onAct, submit, original }
}

describe("점검 고치기의 부위 칸", () => {
  test.each(["hidden", "disabled", "missing", "available"] as const)(
    "%s 지금 부위도 목록을 받기 전에 보내면 부위를 고르지 않은 것으로 봐요",
    async (state) => {
      const { onAct, submit } = setup(state)
      expect(screen.getByText("부위 목록을 불러오는 중이에요.")).toBeTruthy()
      submit()
      expect((await screen.findByRole("alert")).textContent).toBe(
        ZONE_PART_ERROR,
      )
      expect(onAct).not.toHaveBeenCalled()
      await screen.findByRole("combobox", { name: "부위" })
    },
  )

  test.each(["hidden", "disabled", "missing"] as const)(
    "%s 지금 부위는 목록을 받은 뒤에도 고르지 않은 것으로 보고 보내지 않아요",
    async (state) => {
      const { onAct, submit } = setup(state)
      const field = await screen.findByRole("combobox", { name: "부위" })
      await vi.waitFor(() => expect(field.textContent).toMatch(/^부위 선택/))
      submit()
      expect((await screen.findByRole("alert")).textContent).toBe(
        ZONE_PART_ERROR,
      )
      expect(onAct).not.toHaveBeenCalled()
    },
  )

  test("고를 수 있는 지금 부위는 그대로 골라 두고 그 ID로 보내요", async () => {
    const { onAct, submit, original } = setup("available")
    const field = await screen.findByRole("combobox", { name: "부위" })
    await vi.waitFor(() => expect(field.textContent).toMatch(/^보일러/))
    fireEvent.change(screen.getByLabelText("제목 · 필수"), {
      target: { value: "보일러 소음이 커요" },
    })
    submit()
    await vi.waitFor(() => expect(onAct).toHaveBeenCalled())
    expect(onAct.mock.calls[0][0].body.fields.roomPartId).toBe(original.id)
  })
})
