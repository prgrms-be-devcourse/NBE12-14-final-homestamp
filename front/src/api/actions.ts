// 상세 조회 응답의 availableActions(06-요구사항-명세 4장 '할 수 있는 행동').
// 서버가 지금 이 사람이 누를 수 있는 행동만 주고, 화면은 이 목록에 있는 버튼만 그려요(08 0장 원칙 6).
// 이름은 API 주소의 끝 단어와 같아요(예: POST /api/repair-cases/{id}/start → "start").

// 수리 건(S-12, GET /api/repair-cases/{id})
export type RepairAction =
  | "respond" // 하자 응답(맞아요 / 아니에요 · 더 필요해요)
  | "supplement" // 보충하고 다시 확인 요청
  | "start" // 수리 시작
  | "request-close" // 종료 요청
  | "confirm-close" // 종료 확인(혼자 종료된 건을 늦게 확인하는 것도 같은 행동)
  | "not-yet" // 아직 안 됐어요
  | "close-alone" // 혼자 종료
  | "reopen" // 다시 열기
  | "photos" // 사진 추가(수리 후 사진 포함)
  | "corrections" // 고치기
  | "recur" // 같은 곳 재발 · 새 수리 건

// 지불 하나(수리 건 상세 응답 안의 지불마다, POST /api/payments/{id}/respond · /supplement, POST /api/corrections)
export type PaymentAction =
  | "respond" // 금액 응답(맞아요 / 아니에요 · 더 필요해요)
  | "supplement" // 보충하고 다시 확인 요청
  | "corrections" // 금액 고치기

// 점검 하나(S-14, GET /api/inspections/{id}). 입주일이 비어 있으면 시작할 때 입주일을 함께 보내요(POST /api/rooms/{id}/inspections).
export type InspectionAction =
  | "start" // 입주일을 받아 점검 시작
  | "items" // 하자 항목 남기기 · 빠뜨린 하자 더하기(POST /api/inspections/{id}/items)
  | "request-confirm" // 확인 요청
  | "confirm-all" // 남은 항목 모두 맞아요(하자 기록 없음 확인 포함)
  | "finish-alone" // 상대 없이 혼자 마치기(06 4장 API 표에는 아직 없음)

// 점검 항목 하나(POST /api/inspection-items/{id}/respond · /supplement, POST /api/corrections)
export type InspectionItemAction =
  | "respond" // 항목 응답(맞아요 / 아니에요 · 더 필요해요)
  | "supplement" // 보충하고 다시 확인 요청
  | "corrections" // 고치기
  | "photos" // 사진 추가

// 고친 내용 하나(POST /api/corrections/{id}/respond · /resubmit · /withdraw)
export type CorrectionAction =
  | "respond" // 고친 내용 응답(맞아요 / 아니에요 · 더 필요해요)
  | "resubmit" // 사진 더해서 다시 요청
  | "withdraw" // 거두기

export const hasAction = <A extends string>(list: readonly A[], a: A) =>
  list.includes(a)
