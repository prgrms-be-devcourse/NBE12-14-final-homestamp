import type { AvailableActionsApi } from "../available-actions"
import { correctionAvailableActions } from "./correction-actions"
import {
  inspectionAvailableActions,
  inspectionItemAvailableActions,
} from "./inspection-actions"
import { paymentAvailableActions } from "./repair-actions"
import { roomAvailableActions } from "./room-actions"
import {
  settlementAvailableActions,
  settlementItemAvailableActions,
  transferAvailableActions,
} from "./settlement-actions"

// 목업: 서버가 연결되기 전까지 availableActions를 서버 대신 계산해요.
export const mockAvailableActions: AvailableActionsApi = {
  room: roomAvailableActions,
  inspection: inspectionAvailableActions,
  inspectionItem: inspectionItemAvailableActions,
  settlement: (room, role, viewer, items) =>
    settlementAvailableActions(room, role, viewer, items),
  settlementItem: settlementItemAvailableActions,
  transfer: transferAvailableActions,
  payment: paymentAvailableActions,
  correction: correctionAvailableActions,
}
