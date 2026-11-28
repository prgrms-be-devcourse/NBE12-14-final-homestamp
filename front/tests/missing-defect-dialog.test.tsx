import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { createHttpApi } from "../src/api/http-api"
import type { HttpRequest } from "../src/api/http-client"
import { endpoints } from "../src/api/endpoints"
import type { ApiRequest } from "../src/api/records"
import { createDemo, type Room } from "../src/data/demo"
import {
  MISSING_DEFECT_FAILED,
  MissingDefectDialog,
} from "../src/features/inspection/inspection-corrections"

// 사진 고르기는 jsdom에서 그림을 다시 그릴 수 없어, 고른 파일 이름을 사진 값으로 바로 돌려줘요
vi.mock("../src/lib/photos", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/lib/photos")>()),
  readPhotos: async (files: FileList | null) =>
    Array.from(files ?? []).map((f) => `data:image/jpeg;base64,${f.name}`),
}))

// 빠뜨린 하자 더하기(#405 리뷰): 사진 올리기·항목 저장 결과를 기다려 성공이면 닫고, 실패면 설명·사진을 그대로 둬요
const parts = {
  asOf: null,
  parts: [
    {
      id: "part-ceiling",
      zone: "욕실",
      name: "천장",
      enabled: true,
      hidden: false,
      toggleable: false,
    },
  ],
}
const room = (): Room => ({
  ...structuredClone(createDemo().rooms[0]),
  id: "302",
  moveIn: {
    author: "임대인",
    status: "확인 요청",
    startedAt: "2026-11-20T00:00:00Z",
    server: {
      id: "insp-1",
      availableActions: ["items"],
      lockedAt: null,
      lockReason: null,
      counterpartCanRequestAt: "2026-12-01T00:00:00Z",
      asOf: null,
    },
  },
})

function renderDialog(onAct: () => Promise<boolean>) {
  const http = createHttpApi((async (_m: string, path: string) =>
    path === endpoints.roomParts("302") ? parts : []) as HttpRequest)
  const onClose = vi.fn()
  const act = vi.fn(onAct)
  render(
    <ApiProvider api={http} accountId="dev-tenant">
      <MissingDefectDialog
        open
        kind="in"
        db={createDemo()}
        room={room()}
        role="임차인"
        onClose={onClose}
        onAct={act}
      />
    </ApiProvider>,
  )
  return { onClose, act }
}

// 기본 부위(욕실 › 천장)가 골라질 때까지 기다린 뒤 설명·사진을 넣고 보내요
async function fillAndSubmit() {
  await vi.waitFor(() =>
    expect(screen.getByRole("combobox", { name: "부위" }).textContent).toMatch(
      "천장",
    ),
  )
  fireEvent.change(screen.getByLabelText("상태 설명"), {
    target: { value: "천장에 금이 있어요" },
  })
  fireEvent.change(screen.getByLabelText("사진 · 선택"), {
    target: { files: [new File(["x"], "a.jpg", { type: "image/jpeg" })] },
  })
  await screen.findByText("1장 선택됨")
  fireEvent.click(screen.getByRole("button", { name: "하자 더하기" }))
}

describe("빠뜨린 하자 더하기 창", () => {
  test("저장이 끝날 때까지 기다렸다가 성공하면 닫는다", async () => {
    let finish!: (ok: boolean) => void
    const { onClose, act } = renderDialog(
      () => new Promise<boolean>((r) => (finish = r)),
    )
    await fillAndSubmit()

    await vi.waitFor(() => expect(act).toHaveBeenCalledTimes(1))
    const [request] = act.mock.calls[0] as unknown as [ApiRequest]
    expect(request).toEqual({
      path: endpoints.inspectionAction("insp-1", "items"),
      body: { roomPartId: "part-ceiling", description: "천장에 금이 있어요" },
      photos: ["data:image/jpeg;base64,a.jpg"],
    })
    // 기다리는 동안은 닫지 않고, 버튼을 막아 두 번 보내지 않아요
    expect(onClose).not.toHaveBeenCalled()
    expect(
      (screen.getByRole("button", { name: "하자 더하기" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true)

    finish(true)
    await vi.waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole("alert")).toBeNull()
  })

  test("실패하면 창을 닫지 않고 쓴 설명과 고른 사진을 그대로 둔다", async () => {
    const { onClose, act } = renderDialog(async () => false)
    await fillAndSubmit()

    expect((await screen.findByRole("alert")).textContent).toBe(
      MISSING_DEFECT_FAILED,
    )
    expect(onClose).not.toHaveBeenCalled()
    expect(
      (screen.getByLabelText("상태 설명") as HTMLTextAreaElement).value,
    ).toBe("천장에 금이 있어요")
    expect(screen.getByText("1장 선택됨")).toBeTruthy()

    // 그대로 다시 보내면 같은 설명·사진이 다시 가요
    fireEvent.click(screen.getByRole("button", { name: "하자 더하기" }))
    await vi.waitFor(() => expect(act).toHaveBeenCalledTimes(2))
    const [again] = act.mock.calls[1] as unknown as [ApiRequest]
    expect(again.body).toEqual({
      roomPartId: "part-ceiling",
      description: "천장에 금이 있어요",
    })
    expect(again.photos).toEqual(["data:image/jpeg;base64,a.jpg"])
  })
})
