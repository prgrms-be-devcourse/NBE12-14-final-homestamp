import type { InspectionAction, InspectionItemAction } from "@/api/actions"
import type { DemoState, Inspection, Role, Room } from "@/data/demo"
import {
  confirmableItems,
  inspectionStatus,
  sessionOf,
} from "@/lib/inspection-rules"

type Viewer = { guest?: boolean; readOnly?: boolean }

// 서버가 연결되기 전 Mock: GET /api/inspections/{id}의 availableActions를 서버 대신 계산해요.
// 서버가 연결되면 응답에 담긴 값을 그대로 쓰고 이 함수는 쓰지 않아요.
export function inspectionAvailableActions(
  db: DemoState,
  room: Room,
  kind: "in" | "out",
  role: Role,
  viewer: Viewer = {},
): InspectionAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const items = db.inspections.filter(
    (i) => i.roomId === room.id && i.kind === kind,
  )
  const session = sessionOf(room, kind)
  const status = inspectionStatus(room, items, kind)
  const mine = !session || session.author === role
  // 입주일이 비어 있으면 입주 점검을 시작하기 전에 입주일부터 받아요(INSP-2b)
  const needMoveInDate = kind === "in" && !session && !room.since
  const drafting =
    (status === "없음" && !needMoveInDate) || (status === "작성 중" && mine)
  const out: InspectionAction[] = []
  const add = (a: InspectionAction, when: boolean) => {
    if (when) out.push(a)
  }
  add("start", needMoveInDate)
  add(
    "items",
    status === "잠김" ||
      (status === "확인 요청" && !mine) ||
      (mine && !needMoveInDate),
  )
  add("request-confirm", drafting)
  add(
    "confirm-all",
    status === "확인 요청" &&
      !mine &&
      (Boolean(session?.noDefects) || confirmableItems(items, role).length > 0),
  )
  add("finish-alone", drafting && room.connection === "연결 전")
  return out
}

// 점검 항목마다의 availableActions
export function inspectionItemAvailableActions(
  i: Inspection,
  role: Role,
  viewer: Viewer = {},
): InspectionItemAction[] {
  if (viewer.guest || viewer.readOnly) return []
  const mine = i.author === role
  const shown = i.publication !== "draft" && !i.solo && !i.removed
  const settled = !i.addRequest || i.addRequest.status === "반영"
  const out: InspectionItemAction[] = []
  if (!mine && shown && settled && i.ack !== "확인됨") out.push("respond")
  if (mine && settled && (i.ack === "보충 요청" || i.ack === "이견"))
    out.push("supplement")
  if (mine && !i.removed && settled) out.push("corrections", "photos")
  return out
}
