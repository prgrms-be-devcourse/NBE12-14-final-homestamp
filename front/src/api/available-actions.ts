import type {
  DemoState,
  FactCorrection,
  Inspection,
  Payment,
  Role,
  Room,
  Settlement,
  Transfer,
} from "@/data/demo"
import type {
  CorrectionAction,
  InspectionAction,
  InspectionItemAction,
  PaymentAction,
  RoomAction,
  SettlementAction,
  SettlementItemAction,
  TransferAction,
} from "./actions"

// 수리 건 밖 기록의 availableActions(06 4-0 '할 수 있는 행동'). 화면은 이 인터페이스만 불러요.
// 목업(api/mock/available-actions.ts)은 서버 대신 계산하고,
// HTTP(api/http-available-actions.ts)는 서버 응답에 담긴 값을 그대로 꺼내요.
// guest·readOnly는 화면 쪽 사정(링크로 보는 중 등)이라 어느 쪽이든 빈 목록이에요.
export interface Viewer {
  guest?: boolean
  readOnly?: boolean
}

export interface AvailableActionsApi {
  // GET /api/rooms/{id}
  room(room: Room, role: Role, viewer?: Viewer): RoomAction[]
  // GET /api/inspections/{id}
  inspection(
    db: DemoState,
    room: Room,
    kind: "in" | "out",
    role: Role,
    viewer?: Viewer,
  ): InspectionAction[]
  // 점검 상세 응답 안의 항목마다
  inspectionItem(
    item: Inspection,
    role: Role,
    viewer?: Viewer,
  ): InspectionItemAction[]
  // GET /api/settlements/{id}
  settlement(
    room: Room,
    role: Role,
    viewer?: Viewer,
    items?: Settlement[],
  ): SettlementAction[]
  // 정산 상세 응답 안의 항목·송금마다
  settlementItem(
    item: Settlement,
    role: Role,
    viewer?: Viewer,
  ): SettlementItemAction[]
  transfer(t: Transfer, role: Role, viewer?: Viewer): TransferAction[]
  // 수리 건 상세 응답 안의 지불마다
  payment(p: Payment, role: Role, viewer?: Viewer): PaymentAction[]
  // 고친 내용마다(수리 건·지불·점검 항목 공통). list는 같은 기록의 고친 내용 전체
  correction(
    c: FactCorrection<string | number>,
    role: Role,
    list: readonly FactCorrection<string | number>[],
    viewer?: Viewer,
  ): CorrectionAction[]
}
