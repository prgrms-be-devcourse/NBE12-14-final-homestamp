import { useState } from "react"
import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { ApiProvider } from "../src/api"
import { isConnected } from "../src/api/connected"
import { endpoints } from "../src/api/endpoints"
import type { HttpRequest } from "../src/api/http-client"
import { createHttpRoomParts } from "../src/api/http-parts"
import { createHttpRepairCases } from "../src/api/http-repair-cases"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockRoomParts } from "../src/api/mock/parts"
import { createMockStore } from "../src/api/mock/store"
import { PartField } from "../src/components/common/part-field"
import { PART_TEMPLATES, type RoomPart } from "../src/lib/room-parts"
import { requestItemEdits } from "../src/lib/inspection-rules"
import type { DemoState, Inspection } from "../src/data/demo"

// 호실 부위(06 4-3 /api/rooms/{id}/parts, ROOM-3, #356). 수리 건·점검 화면이 같은 부위 ID(roomPartId)를 써요
const tenant = { accountId: "dev-tenant" }
const setup = () => {
  const store = createMockStore(createSampleSeed)
  return { store, parts: createMockRoomParts(store) }
}

describe("목업 부위 API", () => {
  test("호실마다 V7 기본 29개를 같은 순서로 복사하고, 부위 ID는 호실마다 달라요(ROOM-1a)", async () => {
    const { parts } = setup()
    const a = await parts.list(tenant, "302")
    const b = await parts.list(tenant, "201")
    expect(a.asOf).toBeNull()
    expect(a.parts.map((p) => [p.zone, p.name])).toEqual(
      PART_TEMPLATES.map((t) => [t.zone, t.name]),
    )
    // 설비·가전만 켜고 끌 수 있어요
    expect(a.parts.filter((p) => p.toggleable).map((p) => p.zone)).toEqual(
      Array(5).fill("설비·가전"),
    )
    const ids = new Set([...a.parts, ...b.parts].map((p) => p.id))
    expect(ids.size).toBe(58)
  })

  test("남의 호실은 404예요", async () => {
    const { parts } = setup()
    await expect(
      parts.list({ accountId: "nobody" }, "302"),
    ).rejects.toMatchObject({ status: 404, code: "NOT_FOUND" })
  })

  test("부위 더하기: 고른 구역 맨 뒤에 생기고 행동이 남아요. 6구역 밖·빈 이름·50자 초과는 VALIDATION_FAILED(ROOM-3c)", async () => {
    const { store, parts } = setup()
    const made = await parts.add(tenant, "302", { zone: "방", name: "  다락 " })
    expect(made).toMatchObject({
      zone: "방",
      name: "다락",
      enabled: true,
      hidden: false,
      toggleable: false,
    })
    const list = (await parts.list(tenant, "302")).parts
    const room = list.filter((p) => p.zone === "방")
    expect(room.at(-1)?.id).toBe(made.id)
    // 다음 구역(주방)보다 앞이에요
    expect(list.indexOf(room.at(-1)!)).toBeLessThan(
      list.findIndex((p) => p.zone === "주방"),
    )
    expect(store.getState().events[0].text).toBe("부위 추가 · 방 · 다락")
    // 같은 이름은 막지 않아요
    await expect(
      parts.add(tenant, "302", { zone: "방", name: "다락" }),
    ).resolves.toMatchObject({ name: "다락" })
    for (const bad of [
      { zone: "거실", name: "벽" },
      { zone: "방", name: "   " },
      { zone: "방", name: "가".repeat(51) },
    ])
      await expect(parts.add(tenant, "302", bad)).rejects.toMatchObject({
        code: "VALIDATION_FAILED",
      })
  })
})

test("정산이 끝난 호실에는 부위를 더할 수 없어요(SETTLED, 06 4-10)", async () => {
  const { store, parts } = setup()
  store.setState((d) => ({
    ...d,
    rooms: d.rooms.map((r) =>
      r.id === "302" ? { ...r, status: "정산 완료" } : r,
    ),
  }))
  await expect(
    parts.add(tenant, "302", { zone: "방", name: "다락" }),
  ).rejects.toMatchObject({ status: 409, code: "SETTLED" })
})

describe("점검 항목 부위 고치기(roomPartId)", () => {
  const item = (patch: Partial<Inspection> = {}): DemoState => {
    const d = createSampleSeed()
    d.inspections = [
      {
        id: "i1",
        roomId: "302",
        kind: "in",
        title: "얼룩",
        zone: "방 · 천장",
        roomPartId: "p-room",
        description: "천장 얼룩",
        photos: [],
        ack: "확인 대기",
        author: "임차인",
        art: "wall",
        publication: "draft",
        ...patch,
      },
    ]
    return d
  }
  const next = (zone: string, roomPartId: string) => ({
    title: "얼룩",
    description: "천장 얼룩",
    zone,
    roomPartId,
  })
  test("작성 중이면 바로 바뀌고 부위 ID도 바뀌어요", () => {
    const d = item()
    requestItemEdits(d, "임차인", "i1", next("욕실 · 천장", "p-bath"), "")
    expect(d.inspections[0]).toMatchObject({
      zone: "욕실 · 천장",
      roomPartId: "p-bath",
    })
  })
  test("ID가 같으면 바뀐 것이 없고, 글자가 같아도 ID가 다르면 고친 내용이 남아 반영 때 ID가 바뀌어요", () => {
    const d = item({ publication: "requested" })
    expect(() =>
      requestItemEdits(d, "임차인", "i1", next("방 · 천장", "p-room"), ""),
    ).toThrow("바뀐 내용이 없어요.")
    const [c] = requestItemEdits(
      d,
      "임차인",
      "i1",
      next("방 · 천장", "p-room-2"),
      "",
    )
    expect(c).toMatchObject({ field: "부위", toPartId: "p-room-2" })
    expect(d.inspections[0].roomPartId).toBe("p-room-2")
  })
  test("같은 이름의 다른 부위로 다시 고치면 대기 중인 부위 고치기를 대체해요", () => {
    const d = item({ publication: "requested", ack: "확인됨" })
    const [first] = requestItemEdits(
      d,
      "임차인",
      "i1",
      next("방 · 천장", "p-room-2"),
      "부위 수정",
    )
    expect(first.toPartId).toBe("p-room-2")
    expect(first.status).toBe("확인 대기")
    expect(() =>
      requestItemEdits(
        d,
        "임차인",
        "i1",
        next("방 · 천장", "p-room-2"),
        "같은 부위",
      ),
    ).toThrow("바뀐 내용이 없어요.")
    const [second] = requestItemEdits(
      d,
      "임차인",
      "i1",
      next("방 · 천장", "p-room-3"),
      "다른 부위",
    )
    expect(first.status).toBe("대체됨")
    expect(second).toMatchObject({ status: "확인 대기", toPartId: "p-room-3" })
    expect(d.inspections[0].roomPartId).toBe("p-room")
  })
})

describe("HTTP 부위 API", () => {
  const serverParts: RoomPart[] = [
    {
      id: "p-room",
      zone: "방",
      name: "천장",
      enabled: true,
      hidden: false,
      toggleable: false,
    },
    {
      id: "p-bath",
      zone: "욕실",
      name: "천장",
      enabled: true,
      hidden: false,
      toggleable: false,
    },
  ]
  test("부위 목록·더하기·숨김·다시 보이기·켜고 끔은 서버로 보내요(connected)", () => {
    expect(isConnected("GET", endpoints.roomParts("r1"))).toBe(true)
    expect(isConnected("POST", endpoints.roomParts("r1"))).toBe(true)
    for (const verb of ["hide", "show", "toggle"] as const)
      expect(isConnected("POST", endpoints.roomPart("r1", "p1", verb))).toBe(
        true,
      )
    expect(isConnected("POST", "/api/rooms/r1/parts/p1/delete")).toBe(false)
  })

  test("숨김·다시 보이기는 본문 없이, 켜고 끔은 원하는 값 {enabled}를 보내요(ROOM-3d)", async () => {
    const request = vi.fn(async () => serverParts[0])
    const api = createHttpRoomParts(request as unknown as HttpRequest)
    await api.hide(tenant, "r1", "p1")
    expect(request).toHaveBeenLastCalledWith(
      "POST",
      "/api/rooms/r1/parts/p1/hide",
    )
    await api.show(tenant, "r1", "p1")
    expect(request).toHaveBeenLastCalledWith(
      "POST",
      "/api/rooms/r1/parts/p1/show",
    )
    await api.toggle(tenant, "r1", "p1", false)
    expect(request).toHaveBeenLastCalledWith(
      "POST",
      "/api/rooms/r1/parts/p1/toggle",
      { body: { enabled: false } },
    )
  })

  test("목록은 부를 때마다 새로 받고(보는 사람 기준·상대 변경), 더하면 {zone, name}을 보내요", async () => {
    const request = vi.fn(async (method: string) =>
      method === "GET"
        ? { asOf: null, parts: serverParts }
        : { ...serverParts[0], id: "p-new", name: "다락" },
    )
    const api = createHttpRoomParts(request as unknown as HttpRequest)
    expect((await api.list(tenant, "r1")).parts).toHaveLength(2)
    await api.partsOf("r1")
    expect(request).toHaveBeenCalledTimes(2)
    const made = await api.add(tenant, "r1", { zone: "방", name: "다락" })
    expect(made.id).toBe("p-new")
    expect(request).toHaveBeenLastCalledWith("POST", "/api/rooms/r1/parts", {
      body: { zone: "방", name: "다락" },
    })
    await api.list(tenant, "r1")
    expect(request).toHaveBeenCalledTimes(4)
  })

  test("수리 건 상세는 roomPartId로 구역을 찾아 이름이 같은 방·욕실 천장을 가려요. 목록을 못 받으면 서버의 partName", async () => {
    const detail = (roomPartId: string) => ({
      id: "c1",
      roomRecordId: "r1",
      mySide: "TENANT",
      registeredBy: "TENANT",
      status: "NOT_STARTED",
      closeRequestedBy: null,
      closedBy: null,
      closeObjection: null,
      solo: false,
      createdAt: "2026-11-25T16:30:00Z",
      asOf: null,
      content: {
        no: 1,
        roomPartId,
        partName: "천장",
        title: "물 자국",
        description: null,
      },
      defect: { ack: "PENDING", nextRerequestAt: null },
      money: { status: "UNDECIDED" },
      photos: [],
      causeNotes: [],
      availableActions: [],
    })
    let part = "p-bath"
    const request = (async () => detail(part)) as unknown as HttpRequest
    const api = createHttpRepairCases(
      request,
      undefined,
      async () => serverParts,
    )
    expect((await api.get(tenant, "c1")).repair).toMatchObject({
      roomPartId: "p-bath",
      zone: "욕실 · 천장",
    })
    part = "p-room"
    expect((await api.get(tenant, "c1")).repair.zone).toBe("방 · 천장")
    const failing = createHttpRepairCases(request, undefined, async () => {
      throw new Error("down")
    })
    expect((await failing.get(tenant, "c1")).repair.zone).toBe("천장")
  })
})

describe("부위 칸(PartField)", () => {
  // 화면처럼 고른 부위를 값으로 다시 넘겨요
  function Field({ onChange }: { onChange: (p?: RoomPart) => void }) {
    const [value, setValue] = useState("")
    return (
      <PartField
        roomId="302"
        value={value}
        onChange={(p) => {
          setValue(p?.id ?? "")
          onChange(p)
        }}
        defaultPart={{ zone: "욕실", name: "천장" }}
      />
    )
  }
  const renderField = (onChange = vi.fn()) => {
    const store = createMockStore(createSampleSeed)
    store.setState((d) => {
      const next = structuredClone(d)
      next.roomParts!["302"].forEach((p) => {
        if (p.name === "붙박이장") p.hidden = true
        if (p.name === "냉장고") p.enabled = false
      })
      return next
    })
    render(
      <ApiProvider api={createMockApi(store)} accountId="dev-tenant">
        <Field onChange={onChange} />
      </ApiProvider>,
    )
    return { store, onChange }
  }
  // 고르기 칸(Base UI Select)은 jsdom에서 클릭으로 고르지 않아 키보드로 골라요
  const choose = async (label: string, option: string) => {
    const box = await screen.findByRole("combobox", { name: label })
    if (box.getAttribute("aria-expanded") !== "true") fireEvent.click(box)
    const o = await screen.findByRole("option", { name: option })
    o.focus()
    fireEvent.keyDown(o, { key: "Enter" })
    await vi.waitFor(() =>
      expect(screen.getByRole("combobox", { name: label }).textContent).toMatch(
        option,
      ),
    )
  }

  test("목록을 받으면 기본 부위를 ID로 골라 두고, 숨긴 부위·꺼 둔 설비·가전은 고를 수 없어요(ROOM-3a·3d)", async () => {
    const { store, onChange } = renderField()
    await vi.waitFor(() => expect(onChange).toHaveBeenCalled())
    const bath = store
      .getState()
      .roomParts!["302"].find((p) => p.zone === "욕실" && p.name === "천장")!
    expect(onChange.mock.calls[0][0].id).toBe(bath.id)
    await choose("구역", "방")
    fireEvent.click(await screen.findByRole("combobox", { name: "부위" }))
    await vi.waitFor(() =>
      expect(
        screen.queryAllByRole("option").map((o) => o.textContent),
      ).toContain("창문·창틀"),
    )
    expect(
      screen.getByRole("option", { name: "기타 (직접 입력)" }),
    ).toBeTruthy()
    expect(screen.queryByRole("option", { name: "붙박이장" })).toBeNull()
    await choose("부위", "창문·창틀")
    await choose("구역", "설비·가전")
    fireEvent.click(await screen.findByRole("combobox", { name: "부위" }))
    expect(await screen.findByRole("option", { name: "보일러" })).toBeTruthy()
    expect(screen.queryByRole("option", { name: "냉장고" })).toBeNull()
  })

  test("숨긴 부위가 값으로 오면(이전 기록) 고른 것으로 두지 않고 비워요(ROOM-3a)", async () => {
    const store = createMockStore(createSampleSeed)
    const hidden = store
      .getState()
      .roomParts!["302"].find((p) => p.name === "붙박이장")!
    store.setState((d) => {
      const n = structuredClone(d)
      n.roomParts!["302"].find((p) => p.id === hidden.id)!.hidden = true
      return n
    })
    const onChange = vi.fn()
    render(
      <ApiProvider api={createMockApi(store)} accountId="dev-tenant">
        <PartField
          roomId="302"
          value={hidden.id}
          onChange={onChange}
          defaultPart={{ zone: "방", name: "붙박이장" }}
        />
      </ApiProvider>,
    )
    await vi.waitFor(() => expect(onChange).toHaveBeenCalledWith(undefined))
    const box = await screen.findByRole("combobox", { name: "부위" })
    expect(box.textContent).toMatch(/^부위 선택/)
    expect(screen.getByRole("combobox", { name: "구역" }).textContent).toMatch(
      /^방/,
    )
  })

  test("부위를 더하지 못하면 서버 문구를 보여 주고 고르지 않아요", async () => {
    const { store, onChange } = renderField()
    await vi.waitFor(() => expect(onChange).toHaveBeenCalled())
    store.setState((d) => ({
      ...d,
      rooms: d.rooms.map((r) =>
        r.id === "302" ? { ...r, status: "정산 완료" } : r,
      ),
    }))
    await choose("부위", "기타 (직접 입력)")
    fireEvent.change(screen.getByLabelText(/부위 직접 입력/), {
      target: { value: "식탁" },
    })
    fireEvent.click(screen.getByRole("button", { name: "부위 더하기" }))
    expect(
      await screen.findByText("정산이 끝난 호실은 바꿀 수 없어요."),
    ).toBeTruthy()
    expect(onChange).toHaveBeenLastCalledWith(undefined)
  })

  test("기타 (직접 입력)으로 이름을 넣고 더하면 그 구역에 부위가 생기고 그 부위를 골라요(06 S-10 ② 부위)", async () => {
    const { store, onChange } = renderField()
    await vi.waitFor(() => expect(onChange).toHaveBeenCalled())
    await choose("구역", "주방")
    await choose("부위", "기타 (직접 입력)")
    expect(onChange).toHaveBeenLastCalledWith(undefined)
    fireEvent.change(screen.getByLabelText(/부위 직접 입력/), {
      target: { value: "식탁" },
    })
    fireEvent.click(screen.getByRole("button", { name: "부위 더하기" }))
    await vi.waitFor(() =>
      expect(onChange.mock.lastCall?.[0]).toMatchObject({
        zone: "주방",
        name: "식탁",
      }),
    )
    const added = store
      .getState()
      .roomParts!["302"].find((p) => p.name === "식탁")
    expect(onChange.mock.lastCall?.[0].id).toBe(added?.id)
  })
})
