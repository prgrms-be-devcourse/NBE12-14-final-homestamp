import type { InspectionAction } from "./actions"
import type { AvailableActionsApi, Viewer } from "./available-actions"

// HTTP: availableActions는 서버가 상세 조회 응답에 담아 줘요(06 4-0). 화면은 계산하지 않고 꺼내기만 해요.
// 응답 본문 형식이 정해지면 http-records의 변환 함수가 기록마다 availableActions를 붙여 두고, 여기서는 그 값을 읽어요.
const blocked = (v?: Viewer) => Boolean(v?.guest || v?.readOnly)
const fromServer = <T>(record: unknown, v?: Viewer): T[] => {
  if (blocked(v)) return []
  const list = (record as { availableActions?: unknown } | null)
    ?.availableActions
  return Array.isArray(list) ? (list as T[]) : []
}

export const httpAvailableActions: AvailableActionsApi = {
  // 호실은 상세(GET /api/rooms/{id})의 값을 room.server에 둬요(http-rooms)
  room: (room, _role, v) => fromServer(room.server, v),
  // 점검은 상세(GET /api/inspections/{id})의 값을 room.moveIn.server에 둬요(http-inspection-view). 지금은 입주 점검만 받아요.
  // 점검이 아직 없으면 호실 상세의 inspections(입주 점검 시작만, 06 4-3)가 시작 버튼이에요
  inspection: (_db, room, kind, _role, v) => {
    if (kind !== "in" || blocked(v)) return []
    if (room.moveIn) return fromServer<InspectionAction>(room.moveIn.server, v)
    return fromServer<string>(room.server, v).includes("inspections")
      ? ["start"]
      : []
  },
  inspectionItem: (item, _role, v) => fromServer(item.server, v),
  // 정산은 호실의 정산(GET /api/rooms/{id}/settlement)의 값을 room.settlement에 둬요(http-settlement-view, #442)
  settlement: (room, _role, v) => fromServer(room.settlement, v),
  settlementItem: (item, _role, v) => fromServer(item, v),
  // 송금은 정산 상세의 송금마다 준 값을 transfer.server에 둬요(http-settlement-view)
  transfer: (t, _role, v) => fromServer(t.server, v),
  payment: (p, _role, v) => fromServer(p, v),
  correction: (c, _role, _list, v) => fromServer(c, v),
}
