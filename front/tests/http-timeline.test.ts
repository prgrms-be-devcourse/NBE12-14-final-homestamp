import { describe, expect, test } from "vitest"
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
const of = (...items: TimelineItemBody[]) =>
  eventsFromTimeline("r1", { mySide: "TENANT", asOf: null, items }, [
    { id: "c1", title: "천장 누수" },
  ])

describe("경과 → 화면 줄", () => {
  test("수리 건 행동은 '제목 · 행동 · 사유'로, 그 수리 건 경과에 붙는다", () => {
    expect(
      of(
        item({ type: "NOT_YET", side: "LANDLORD", reason: " 아직 물이 새요 " }),
      ),
    ).toEqual([
      {
        id: "a1",
        roomId: "r1",
        text: "천장 누수 · 아직 안 됐어요 · 아직 물이 새요",
        by: "임대인",
        at: "2026-11-27T05:00:00Z",
        target: { kind: "repair", id: "c1" },
        ref: { kind: "REPAIR_CASE", id: "c1" },
      },
    ])
  })

  test("응답은 payload.answer(맞아요·보류·이견)를 함께 보인다", () => {
    expect(
      of(
        item({ type: "RESPOND_DEFECT", payload: { answer: "ACK" } }),
        item({
          id: "a2",
          type: "RESPOND_DEFECT",
          payload: { answer: "NEED_MORE" },
          reason: "사진 더 주세요",
        }),
      ).map((e) => e.text),
    ).toEqual([
      "천장 누수 · 하자 응답 · 맞아요",
      "천장 누수 · 하자 응답 · 보류 · 사진 더 주세요",
    ])
  })

  test("서버 행동은 '서버 자동', 모르는 행동은 '기록', 수리 건이 아닌 대상은 ref로만 남는다", () => {
    const [e] = of(
      item({
        type: "SOMETHING_NEW",
        side: null,
        target: { kind: "CAUSE_NOTE", id: "n1" },
      }),
    )
    expect(e).toMatchObject({ text: "기록", by: "서버 자동" })
    expect(e.target).toBeUndefined()
    expect(e.ref).toEqual({ kind: "CAUSE_NOTE", id: "n1" })
  })

  test("호실 기록 자체가 대상(target null)이면 대상 없이 행동만 보인다", () => {
    const [e] = of(item({ type: "CREATE_ROOM", target: null }))
    expect(e.text).toBe("호실 만들기")
    expect(e.target).toBeUndefined()
    expect(e.ref).toBeUndefined()
  })

  test("경과는 서버로 보내는 API 목록에 있다", () => {
    expect(isConnected("GET", "/api/rooms/r1/timeline")).toBe(true)
  })
})
