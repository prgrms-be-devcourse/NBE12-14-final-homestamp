import { expect, test } from "vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { createDemo, type Repair, type Room } from "../src/data/demo"
import {
  AFTER_PHOTO_REQUIRED,
  requestClose,
  soloClose,
} from "../src/lib/repair-rules"
import { RepairPayments } from "../src/features/repair/repair-payments"
import { paymentAvailableActions } from "../src/api/mock/repair-actions"

const doing = (afterPhotos?: string[]): Repair => ({
  ...structuredClone(createDemo().repairs[0]),
  status: "처리 중",
  closeBy: undefined,
  soloClosed: undefined,
  afterPhotos,
})

test("수리 후 사진이 없으면 종료 요청·혼자 종료가 저장되지 않는다", () => {
  const r = doing()
  expect(() => requestClose(r, "임차인")).toThrow(AFTER_PHOTO_REQUIRED)
  expect(r.closeBy).toBeUndefined()
  expect(() => soloClose(r, "임차인", "상대 없음")).toThrow(
    AFTER_PHOTO_REQUIRED,
  )
  expect(r.status).toBe("처리 중")
})

test("수리 후 사진이 1장 이상 있으면 종료할 수 있다", () => {
  const r = doing(["data:image/jpeg;base64,AA"])
  requestClose(r, "임차인")
  expect(r.closeBy).toBe("임차인")
  const solo = doing(["data:image/jpeg;base64,AA"])
  soloClose(solo, "임차인", "상대 없음")
  expect(solo.status).toBe("처리 완료")
})

test("지불 기록: 증빙 종류(영수증)는 선택이지만 증빙 사진이 없으면 저장되지 않고 안내한다", () => {
  const demo = createDemo()
  const r: Repair = { ...structuredClone(demo.repairs[0]), payments: [] }
  const room = demo.rooms.find((x) => x.id === r.roomId) as Room
  let saved = 0
  render(
    <RepairPayments
      r={r}
      room={room}
      role="임차인"
      readOnly={false}
      actionsOf={(p) => paymentAvailableActions(p, "임차인")}
      onAction={() => {
        saved++
      }}
    />,
  )
  fireEvent.click(screen.getAllByRole("button", { name: /지불 기록/ })[0])
  expect(screen.getByText("증빙 종류 · 선택 (영수증 없어도 돼요)")).toBeTruthy()
  expect(screen.getByText("증빙 사진 · 필수 (1장 이상)")).toBeTruthy()
  fireEvent.change(screen.getByLabelText("금액 (원)"), {
    target: { value: "50000" },
  })
  fireEvent.click(
    screen.getByRole("button", { name: /기록 남기기|저장|남기기/ }),
  )
  expect(saved).toBe(0)
  expect(screen.getByText(/증빙 사진을 1장 이상 올려 주세요/)).toBeTruthy()
})
