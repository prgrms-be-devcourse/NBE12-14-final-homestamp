import { describe, expect, it } from "vitest"
import type { Room } from "../src/data/demo"
import { roomOptions } from "../src/lib/room-options"

const room = (
  id: string,
  name: string,
  address: string,
  since = "",
  createdAt?: string,
): Room =>
  ({
    id,
    name,
    address,
    since,
    createdAt,
    status: "거주 중",
    connection: "연결 전",
    received: false,
  }) as Room

describe("호실 고르기 항목", () => {
  it("값은 호실 ID라서 이름이 같아도 항목이 겹치지 않아요", () => {
    const options = roomOptions([
      room("a", "302호", "가동 1"),
      room("b", "302호", "나동 2"),
    ])
    expect(options.map((o) => o.value)).toEqual(["a", "b"])
  })

  it("이름이 겹치지 않으면 이름만 보여요", () => {
    expect(
      roomOptions([room("a", "302호", "가동 1"), room("b", "101호", "가동 1")]),
    ).toEqual([
      { value: "a", label: "302호" },
      { value: "b", label: "101호" },
    ])
  })

  it("이름이 겹치면 주소를, 주소까지 같으면 입주일과 만든 날을 붙여요", () => {
    const labels = roomOptions([
      room("a", "302호", "가동 1"),
      room("b", "302호", "나동 2", "", "2026-11-01"),
      room("c", "302호", "나동 2", "2026-03-01", "2026-02-20"),
      room("d", "101호", "가동 1"),
    ]).map((o) => o.label)
    expect(labels).toEqual([
      "302호 · 가동 1",
      "302호 · 나동 2 · 2026-11-01 만듦",
      "302호 · 나동 2 · 2026-03-01 입주 · 2026-02-20 만듦",
      "101호",
    ])
  })

  it("입주일도 만든 날도 없으면 상태를 붙여요", () => {
    const labels = roomOptions([
      room("a", "302호", "나동 2"),
      room("b", "302호", "나동 2", "2026-03-01"),
    ]).map((o) => o.label)
    expect(labels).toEqual([
      "302호 · 나동 2 · 거주 중",
      "302호 · 나동 2 · 2026-03-01 입주",
    ])
  })
})
