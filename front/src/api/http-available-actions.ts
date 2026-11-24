import type { AvailableActionsApi, Viewer } from "./available-actions"

// HTTP: availableActions는 서버가 상세 조회 응답에 담아 줘요(06 4-0). 화면은 계산하지 않고 꺼내기만 해요.
// 응답 본문 형식이 정해지면 http-records의 변환 함수가 기록마다 availableActions를 붙여 두고, 여기서는 그 값을 읽어요.
// 점검·정산 상세는 아직 받아 오지 않아(http-records의 load 참고) 빈 목록이에요.
const blocked = (v?: Viewer) => Boolean(v?.guest || v?.readOnly)
const fromServer = <T>(record: unknown, v?: Viewer): T[] => {
  if (blocked(v)) return []
  const list = (record as { availableActions?: unknown } | null)
    ?.availableActions
  return Array.isArray(list) ? (list as T[]) : []
}

export const httpAvailableActions: AvailableActionsApi = {
  room: (room, _role, v) => fromServer(room, v),
  inspection: () => [],
  inspectionItem: (item, _role, v) => fromServer(item, v),
  settlement: () => [],
  settlementItem: (item, _role, v) => fromServer(item, v),
  transfer: (t, _role, v) => fromServer(t, v),
  payment: (p, _role, v) => fromServer(p, v),
  correction: (c, _role, _list, v) => fromServer(c, v),
}
