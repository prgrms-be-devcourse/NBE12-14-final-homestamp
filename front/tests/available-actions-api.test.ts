import { describe, expect, it } from "vitest"
import { httpAvailableActions } from "@/api/http-available-actions"
import type { Payment, Room } from "@/data/demo"

// HTTP 쪽 availableActions는 계산하지 않고 서버 응답에 담긴 값을 꺼내기만 해요(06 4-0)
describe("HTTP availableActions", () => {
  const room = {
    id: "r1",
    availableActions: ["invite", "move-out"],
  } as unknown as Room

  it("서버가 준 목록을 그대로 돌려준다", () => {
    expect(httpAvailableActions.room(room, "임차인")).toEqual([
      "invite",
      "move-out",
    ])
  })

  it("서버 값이 없으면 버튼을 만들지 않는다", () => {
    const p = { id: "p1" } as unknown as Payment
    expect(httpAvailableActions.payment(p, "임대인")).toEqual([])
  })

  it("링크로 보는 중(guest)이나 읽기 전용이면 빈 목록이다", () => {
    expect(httpAvailableActions.room(room, "임차인", { guest: true })).toEqual(
      [],
    )
    expect(
      httpAvailableActions.room(room, "임차인", { readOnly: true }),
    ).toEqual([])
  })
})
