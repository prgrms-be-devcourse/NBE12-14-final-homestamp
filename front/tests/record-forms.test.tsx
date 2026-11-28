import type { ReactNode } from "react"
import { describe, expect, test, vi } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { Dialog, DialogContent } from "../src/components/ui/dialog"
import { createDemo, type DemoState, type Room } from "../src/data/demo"
import { createSampleSeed } from "../src/api/mock/dev-seed"
import { endpoints } from "../src/api/endpoints"
import type { ApiRequest } from "../src/api/records"
import type { Account } from "../src/api/auth"
import { PhoneForm } from "../src/features/room/phone-form"
import { InviteForm } from "../src/features/room/invite-form"
import { ConnectForm } from "../src/features/room/connect-form"
import { RoomForm } from "../src/features/room/room-form"
import { SettlementItemForm } from "../src/features/settlement/settlement-item-form"
import { InspectionItemForm } from "../src/features/inspection/inspection-item-form"
import { ZONE_PART_ERROR } from "../src/lib/record-commands"
import { MOBILE_PHONE_MESSAGE } from "../src/lib/phone"
import { ApiProvider } from "../src/api"
import { createMockApi } from "../src/api/mock/mock-api"
import { createMockStore } from "../src/api/mock/store"
import { createHttpApi } from "../src/api/http-api"
import type { HttpRequest } from "../src/api/http-client"

// 입력 창 부품(App.tsx에서 기능별로 옮긴 것): 창마다 확인과 보내는 요청이 그대로인지 봐요.
const inDialog = (ui: ReactNode) =>
  render(
    <Dialog open>
      <DialogContent>{ui}</DialogContent>
    </Dialog>,
  )
const submit = (name: RegExp) =>
  fireEvent.click(screen.getByRole("button", { name }))
const type = (label: string | RegExp, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
const room = (patch: Partial<Room> = {}): Room => ({
  ...structuredClone(createDemo().rooms[0]),
  connection: "연결 전",
  phone: undefined,
  ...patch,
})
const account: Account = {
  id: "me",
  name: "나",
  email: "me@example.com",
  phone: "01011112222",
} as Account

describe("입력 창 공통 틀", () => {
  test("주민등록번호 형태가 있으면 보내지 않고 안내한다", () => {
    const commit = vi.fn()
    inDialog(
      <SettlementItemForm
        roomId="r1"
        role="임차인"
        zones={[]}
        commit={commit}
        onCancel={() => {}}
      />,
    )
    type("정산 항목", "900101-1234567")
    type("금액 (원)", "1000")
    type("제안 사유", "사유")
    submit(/제안 보내기/)
    expect(screen.getByRole("alert").textContent).toBe(
      "주민등록번호 형태는 적을 수 없어요.",
    )
    expect(commit).not.toHaveBeenCalled()
  })
})

describe("상대 번호 수정(초대 전)", () => {
  test("잘못된 번호·내 번호·같은 번호는 막고, 바른 번호만 보낸다", () => {
    const commit = vi.fn()
    inDialog(
      <PhoneForm
        room={room({ phone: "01033334444" })}
        role="임차인"
        sessionPhone="01011112222"
        commit={commit}
        onCancel={() => {}}
      />,
    )
    const input = "새 상대 휴대폰 번호"
    for (const [value, msg] of [
      ["0101234", MOBILE_PHONE_MESSAGE],
      ["01011112222", "내 계정 번호는 상대로 넣을 수 없어요."],
      ["01033334444", "기존 번호와 같아요."],
    ]) {
      type(input, value)
      submit(/번호 저장/)
      expect(screen.getByRole("alert").textContent).toBe(msg)
    }
    expect(commit).not.toHaveBeenCalled()
    type(input, "010-5555-6666")
    submit(/번호 저장/)
    expect(commit).toHaveBeenCalledTimes(1)
  })
})

describe("초대", () => {
  test("번호를 넣으면 06 초대 주소로 번호와 동의를 보낸다", () => {
    const commit = vi.fn()
    const toast = vi.fn()
    const r = room()
    inDialog(
      <InviteForm
        room={r}
        role="임차인"
        sessionPhone="01011112222"
        commit={commit}
        toast={toast}
        onCancel={() => {}}
      />,
    )
    type(/휴대폰 번호/, "01011112222")
    submit(/동의하고 보여 주기/)
    expect(screen.getByRole("alert").textContent).toBe(
      "내 계정 번호는 상대로 넣을 수 없어요.",
    )
    type(/휴대폰 번호/, "01055556666")
    submit(/동의하고 보여 주기/)
    const [request] = commit.mock.calls[0] as [ApiRequest]
    expect(request.path).toBe(endpoints.invite(r.id))
    expect(request.body).toMatchObject({ phone: "01055556666", consent: true })
    expect(toast).toHaveBeenCalledTimes(1)
  })
})

describe("호실 만들기", () => {
  test("주소(법정동)·선택 번호가 틀리면 막고, 맞으면 호실 주소로 보낸 뒤 만든 호실로 간다", async () => {
    const send = vi.fn().mockResolvedValue({ id: "new-room" })
    const onCreated = vi.fn()
    inDialog(
      <ApiProvider api={createMockApi(createMockStore())}>
        <RoomForm
          account={account}
          initialSide="임대인"
          send={send}
          onCreated={onCreated}
          onCancel={() => {}}
        />
      </ApiProvider>,
    )
    type("호실 별칭", "302호")
    submit(/만들기/)
    expect(screen.getByRole("alert").textContent).toBe(
      "주소를 동까지 골라 주세요.",
    )
    // 동 이름으로 찾아 고르면 법정동 코드가 들어가요(06 4-3, decisions 2026-11-13)
    type("주소 · 시·구·동까지", "연남")
    fireEvent.click(screen.getByRole("button", { name: /^찾기$/ }))
    fireEvent.click(
      await screen.findByRole("button", { name: /서울특별시 마포구 연남동/ }),
    )
    type(/세입자 휴대폰 번호/, "0101")
    submit(/만들기/)
    expect(screen.getByRole("alert").textContent).toBe(
      `선택 전화번호: ${MOBILE_PHONE_MESSAGE}`,
    )
    type(/세입자 휴대폰 번호/, "01055556666")
    submit(/만들기/)
    const [request, by] = send.mock.calls[0] as [ApiRequest, string]
    expect(request.path).toBe(endpoints.rooms)
    // 서버 모양(06 4-3): 쪽은 TENANT·LANDLORD, 상대 번호는 숫자만 counterpartPhone으로 보내요(서버가 NUMBER_ONLY로 저장, #406)
    expect(request.body).toEqual({
      side: "LANDLORD",
      regionCode: "1144012400",
      alias: "302호",
      moveInDate: undefined,
      counterpartPhone: "01055556666",
    })
    expect(by).toBe("임대인")
    // 목업은 넣은 번호를 호실의 기준 연락처로 저장해요
    const update = send.mock.calls[0][3] as (d: DemoState) => void
    const d = { rooms: [] as Room[] } as DemoState
    update(d)
    expect(d.rooms[0]).toMatchObject({
      phone: "01055556666",
      creator: "임대인",
    })
    // 서버처럼 기본 부위 29개를 그 호실의 부위로 복사해요(ROOM-1a)
    expect(d.roomParts?.[d.rooms[0].id]).toHaveLength(29)
    await vi.waitFor(() => expect(onCreated).toHaveBeenCalledWith("new-room"))
  })

  test("HTTP에서도 상대 번호 칸을 보여 주고 바로 초대되지 않는다고 안내한다", () => {
    const http = createHttpApi((async () => []) as unknown as HttpRequest)
    inDialog(
      <ApiProvider api={http}>
        <RoomForm
          account={account}
          initialSide="임차인"
          send={vi.fn()}
          onCreated={() => {}}
          onCancel={() => {}}
        />
      </ApiProvider>,
    )
    // 서버가 만들 때 상대 번호를 받아 NUMBER_ONLY로 저장해요(06 4-3, #406). 문자는 초대할 때 나가요
    expect(screen.getByLabelText(/집주인 휴대폰 번호 · 선택/)).toBeTruthy()
    expect(
      screen.getByText(
        "번호를 넣어도 바로 초대되지 않아요. 초대할 때 이 번호를 써요.",
      ),
    ).toBeTruthy()
  })
})

describe("정산 항목", () => {
  test("금액이 한도를 넘으면 막고, 맞으면 정산 항목 주소로 보낸다(기본 방향 차감)", () => {
    const commit = vi.fn()
    inDialog(
      <SettlementItemForm
        roomId="r1"
        role="임대인"
        zones={["욕실 · 천장"]}
        commit={commit}
        onCancel={() => {}}
      />,
    )
    type("정산 항목", "관리비")
    type("제안 사유", "퇴거 월")
    type("금액 (원)", "999999999999")
    submit(/제안 보내기/)
    expect(screen.getByRole("alert").textContent).toBe(
      "금액은 0 이상의 정수로 입력해 주세요.",
    )
    type("금액 (원)", "30000")
    submit(/제안 보내기/)
    const [request] = commit.mock.calls[0] as [ApiRequest]
    expect(request.path).toBe(endpoints.settlementAction("r1", "items"))
    expect(request.body).toMatchObject({
      title: "관리비",
      amount: 30000,
      direction: "차감",
    })
  })
})

describe("점검 기록", () => {
  test("구역·부위를 고르지 않으면 보내지 않는다", async () => {
    const commit = vi.fn()
    inDialog(
      <ApiProvider
        api={createMockApi(createMockStore(createSampleSeed))}
        accountId="dev-tenant"
      >
        <InspectionItemForm
          kind="in"
          room={room()}
          role="임차인"
          locked={false}
          defectClosed={false}
          commit={commit}
          onCancel={() => {}}
        />
      </ApiProvider>,
    )
    expect(screen.getByText("입주 당시 기록 남기기")).toBeTruthy()
    await screen.findByRole("combobox", { name: "구역" })
    submit(/기록 남기기/)
    expect(screen.getByRole("alert").textContent).toBe(ZONE_PART_ERROR)
    expect(commit).not.toHaveBeenCalled()
  })

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

  test("HTTP에서는 제목 칸 없이 부위·설명·사진만 서버 점검 주소로 보낸다(상대가 작성 중에 더해도 서버가 정함)", async () => {
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
    const http = createHttpApi((async (_m: string, path: string) =>
      path === endpoints.roomParts("302") ? parts : []) as HttpRequest)
    const commit = vi.fn()
    inDialog(
      <ApiProvider api={http} accountId="dev-tenant">
        <InspectionItemForm
          kind="in"
          room={room({
            id: "302",
            moveIn: {
              author: "임대인",
              status: "작성 중",
              server: {
                id: "insp-1",
                availableActions: ["items"],
                lockedAt: null,
                lockReason: null,
                counterpartCanRequestAt: "2026-12-01T00:00:00Z",
                asOf: null,
              },
            },
          })}
          role="임차인"
          locked={false}
          defectClosed={false}
          commit={commit}
          onCancel={() => {}}
        />
      </ApiProvider>,
    )
    await choose("구역", "욕실")
    await choose("부위", "천장")
    fireEvent.click(screen.getByRole("checkbox"))
    expect(screen.queryByLabelText("기록 제목")).toBeNull()
    type("상태 설명", "천장에 물 자국이 있어요")
    type(/사진 메모/, "왼쪽 모서리")
    submit(/기록 남기기/)

    expect(screen.queryByRole("alert")).toBeNull()
    const [request, text] = commit.mock.calls[0] as [ApiRequest, string]
    expect(request).toEqual({
      path: endpoints.inspectionAction("insp-1", "items"),
      body: {
        roomPartId: "part-ceiling",
        description: "천장에 물 자국이 있어요\n\n사진 메모: 왼쪽 모서리",
      },
      photos: [],
    })
    expect(text).toMatch(/^입주 점검 · 욕실 · 천장 등록/)
  })
})

describe("호실 연결", () => {
  test("초대 번호와 내 번호가 다르면 연결 버튼을 막고 안내한다", () => {
    const shared = room({ phone: "01099998888", creator: "임대인" })
    inDialog(
      <ConnectForm
        sharedRoom={shared}
        role="임차인"
        account={account}
        guest
        db={createDemo()}
        myRooms={[]}
        act={vi.fn()}
        onConnected={() => {}}
        onRejectToOwn={() => {}}
        onReject={() => {}}
        onCancel={() => {}}
      />,
    )
    expect(
      screen.getByText(/초대받은 번호와 내 인증 번호가 달라요/),
    ).toBeTruthy()
    expect(
      (
        screen.getByRole("button", {
          name: /동의하고 연결하기/,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true)
  })
})
