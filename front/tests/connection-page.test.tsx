import { describe, expect, test } from "vitest"
import { render, screen } from "@testing-library/react"
import { ConnectionPage } from "../src/pages/connection-page"
import { roomFromSummary } from "../src/api/http-rooms"
import type { PartyStatus } from "../src/data/demo"

// 연결 화면의 상대 카드 안내: 상대가 탈퇴했으면 '연결된 계정'이라고 하지 않아요(#241 리뷰, 03 L 탈퇴)
const show = (counterpartStatus: PartyStatus) =>
  render(
    <ConnectionPage
      room={roomFromSummary(
        {
          id: "r1",
          alias: "302호",
          region: {
            code: "3611010100",
            sido: "세종특별자치시",
            sigungu: null,
            dong: "반곡동",
          },
          status: "LIVING",
          mySide: "LANDLORD",
          counterpartStatus,
          joinedAway: false,
          nextStep: "NONE",
          createdAt: "2026-11-25T01:02:03Z",
        },
        "me",
      )}
      role="임대인"
      readOnly
      sessionPhone="01012345678"
      roomHas={() => false}
      open={() => {}}
    />,
  )

describe("연결 화면 상대 카드", () => {
  test("상대가 탈퇴했으면 탈퇴한 계정으로 안내한다", () => {
    show("WITHDRAWN")
    expect(screen.getByText(/탈퇴한 계정/)).toBeTruthy()
    expect(screen.queryByText("휴대폰 인증 후 연결된 계정")).toBeNull()
  })

  test("정상 연결은 그대로 연결된 계정이다", () => {
    show("CONNECTED")
    expect(screen.getByText("휴대폰 인증 후 연결된 계정")).toBeTruthy()
  })
})
