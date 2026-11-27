import { render, screen } from "@testing-library/react"
import { describe, expect, test } from "vitest"
import { Timeline } from "../src/components/common/blocks"
import { isConnected } from "../src/api/connected"
import {
  eventsFromTimeline,
  type TimelineItemBody,
} from "../src/api/http-timeline"

// 경과(06 4장 GET /api/rooms/{id}/timeline) 한 줄 → 화면 줄(#359)
const item = (patch: Partial<TimelineItemBody> = {}): TimelineItemBody => ({
  id: "a1",
  at: "2026-11-27T05:00:00Z",
  type: "REGISTER",
  side: "TENANT",
  target: { kind: "REPAIR_CASE", id: "c1" },
  reason: null,
  payload: {},
  photos: [],
  ...patch,
})
const of = (
  items: unknown[],
  repairs: { id: string; title: string }[] = [{ id: "c1", title: "천장 누수" }],
) =>
  eventsFromTimeline(
    "r1",
    { mySide: "TENANT", asOf: null, items: items as TimelineItemBody[] },
    repairs,
  )

describe("경과 → 화면 줄", () => {
  test("수리 건 행동은 글자 '행동 · 사유'와 대상 이름(제목)으로, 그 수리 건 경과에 붙는다", () => {
    expect(
      of([
        item({ type: "NOT_YET", side: "LANDLORD", reason: " 아직 물이 새요 " }),
      ]),
    ).toEqual([
      {
        id: "a1",
        roomId: "r1",
        text: "아직 안 됐어요 · 아직 물이 새요",
        subject: "천장 누수",
        by: "임대인",
        at: "2026-11-27T05:00:00Z",
        target: { kind: "repair", id: "c1" },
        ref: { kind: "REPAIR_CASE", id: "c1" },
      },
    ])
  })

  test("수리 건 제목을 못 찾으면 대상 이름은 '수리 건'이고 글자는 밀리지 않는다(#368 리뷰)", () => {
    const [e] = of([item({ type: "NOT_YET", reason: "사유" })], [])
    expect(e).toMatchObject({
      text: "아직 안 됐어요 · 사유",
      subject: "수리 건",
    })
    render(<Timeline events={[e]} />)
    expect(screen.getByText("수리 건")).toBeTruthy()
    expect(screen.getByText("아직 안 됐어요")).toBeTruthy()
    expect(screen.getByText("사유")).toBeTruthy()
  })

  test("수리 건 상세(stripTitle)에서는 대상 이름 없이 행동부터 보인다", () => {
    const [e] = of([item({ type: "NOT_YET", reason: "사유" })])
    render(<Timeline compact events={[e]} stripTitle="천장 누수" />)
    expect(screen.queryByText("천장 누수")).toBeNull()
    expect(screen.getByText("아직 안 됐어요")).toBeTruthy()
  })

  test("응답은 payload.answer(맞아요·보류·이견)를 함께 보인다", () => {
    expect(
      of([
        item({ type: "RESPOND_DEFECT", payload: { answer: "ACK" } }),
        item({
          id: "a2",
          type: "RESPOND_DEFECT",
          payload: { answer: "NEED_MORE" },
          reason: "사진 더 주세요",
        }),
      ]).map((e) => e.text),
    ).toEqual(["하자 응답 · 맞아요", "하자 응답 · 보류 · 사진 더 주세요"])
  })

  test("측 없는 행동은 '서버 자동', 측 없는 사진 행동은 '운영'으로 보인다(#368 리뷰)", () => {
    expect(
      of([
        item({ type: "MARK_UNCONFIRMED", side: null }),
        item({ id: "a2", type: "ERASE_PHOTO", side: null }),
        item({ id: "a3", type: "ERASE_PHOTO", side: "TENANT" }),
      ]).map((e) => e.by),
    ).toEqual(["서버 자동", "운영", "임차인"])
  })

  test("모르는 행동은 '기록', 수리 건이 아닌 대상은 ref로만 남는다", () => {
    const [e] = of([
      item({ type: "SOMETHING_NEW", target: { kind: "CAUSE_NOTE", id: "n1" } }),
    ])
    expect(e).toMatchObject({ text: "기록" })
    expect(e.target).toBeUndefined()
    expect(e.subject).toBeUndefined()
    expect(e.ref).toEqual({ kind: "CAUSE_NOTE", id: "n1" })
  })

  test("호실 기록 자체가 대상(target null)이면 대상 없이 행동만 보인다", () => {
    const [e] = of([item({ type: "CREATE_ROOM", target: null })])
    expect(e.text).toBe("호실 만들기")
    expect(e.target).toBeUndefined()
    expect(e.ref).toBeUndefined()
  })

  test("모양이 다른 줄이 있으면 던진다(부른 쪽이 앞서 받은 경과를 둬요, #368 리뷰)", () => {
    for (const broken of [
      null,
      { ...item(), target: { kind: "REPAIR_CASE" } },
      { ...item(), side: "OPS" },
      { ...item(), id: 1 },
    ])
      expect(() => of([item(), broken])).toThrow()
  })

  test("경과는 서버로 보내는 API 목록에 있다", () => {
    expect(isConnected("GET", "/api/rooms/r1/timeline")).toBe(true)
  })
})
