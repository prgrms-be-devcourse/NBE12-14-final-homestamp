import type { Event, Repair, Role } from "@/data/demo"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"

// 경과(GET /api/rooms/{id}/timeline, 06 4장·REP-11)의 서버 모양. 칸 이름은 백엔드 RoomTimeline과 같아요.
type ServerSide = "TENANT" | "LANDLORD"

export interface TimelineItemBody {
  id: string
  at: string
  type: string
  side: ServerSide | null
  target: { kind: string; id: string } | null
  reason: string | null
  payload: Record<string, unknown> | null
  photos: unknown[]
}

export interface RoomTimelineBody {
  mySide: ServerSide
  asOf: string | null
  items: TimelineItemBody[]
}

export const getTimeline = (request: HttpRequest, roomId: string) =>
  request<RoomTimelineBody>("GET", endpoints.timeline(roomId))

// 행동 이름(06 1장 기능 이름, 화면 버튼 말). 목록에 없는 행동은 "기록"으로 보여요
const ACTION_LABEL: Record<string, string> = {
  CREATE_ROOM: "호실 만들기",
  EDIT_ROOM_INFO: "호실 정보 고치기",
  ADD_PART: "부위 더하기",
  HIDE_PART: "부위 숨기기",
  SHOW_PART: "부위 다시 보이기",
  TOGGLE_PART: "설비 켜고 끄기",
  INVITE: "상대 초대",
  CHANGE_INVITE_PHONE: "초대 번호 바꾸기",
  REJECT_INVITE: "초대 거절",
  CONNECT: "연결",
  JOIN: "기록 함께 보기",
  SHARE_CONSENT: "공유 동의",
  STOP_SHARING: "공유 그만두기",
  RESUME_SHARING: "다시 공유하기",
  MOVE_OUT: "퇴거",
  REQUEST_END: "거주 종료 요청",
  CONFIRM_END: "거주 종료 확인",
  REJECT_END: "거주 종료 거절",
  CONFIRM_LIVING: "거주 확인",
  LINK_VIEW: "링크 열람",
  OPT_OUT: "문자 수신 거부",
  RESEND_NOTICE: "알림 다시 보내기",
  RENOTIFY: "다시 알림",
  MARK_UNCONFIRMED: "확인 없음",
  MARK_UNDELIVERED: "문자 못 보냄",
  REGISTER: "하자 등록",
  SUPPLEMENT: "보충하고 다시 확인 요청",
  RESPOND_DEFECT: "하자 응답",
  START_REPAIR: "수리 시작",
  REQUEST_CLOSE: "종료 요청",
  CONFIRM_CLOSE: "종료 확인",
  NOT_YET: "아직 안 됐어요",
  CLOSE_ALONE: "혼자 종료",
  REOPEN: "다시 열기",
  ADD_PHOTO: "사진 더하기",
  ASK_PROGRESS: "진행 상황 묻기",
  NOTE_PROGRESS: "진행 상황 남기기",
  RESPOND_SITUATION: "바뀐 상황 응답",
  NOTE_CAUSE: "원인 설명",
  RESPOND_CAUSE: "원인 설명 응답",
  CORRECT: "고치기",
  RESPOND_CORRECTION: "고친 내용 응답",
  ACCEPT_ALL_CORRECTIONS: "고친 내용 모두 확인",
  RESUBMIT_CORRECTION: "고친 내용 다시 요청",
  WITHDRAW_CORRECTION: "고친 내용 거두기",
  RECORD_PAYMENT: "지불 기록",
  RESPOND_AMOUNT: "금액 응답",
  SUPPLEMENT_PAYMENT: "지불 보충",
  PROPOSE: "비용 부담 제안",
  AGREE: "제안 동의",
  DISPUTE_PROPOSAL: "제안 이견",
  COUNTER: "다른 금액 제안",
  WITHDRAW: "제안 거두기",
  START_INSPECTION: "점검 시작",
  ADD_ITEM: "점검 항목 더하기",
  ADD_PART_PHOTO: "부위 사진 더하기",
  REQUEST_INSPECTION_CONFIRM: "점검 확인 요청",
  RESPOND_ITEM: "점검 항목 응답",
  SUPPLEMENT_ITEM: "점검 항목 보충",
  CONFIRM_ALL: "점검 모두 확인",
  LOCK: "점검 잠금",
  FINISH_INSPECTION: "점검 마치기",
  OPEN_SETTLEMENT: "정산 시작",
  INCLUDE_CANDIDATE: "정산에 넣기",
  ADD_SETTLEMENT_ITEM: "정산 항목 더하기",
  PROPOSE_EXCLUDE: "정산 항목 빼기 제안",
  RECORD_TRANSFER: "송금 기록",
  RESPOND_TRANSFER: "송금 응답",
  PROPOSE_FINISH: "정산 완료 요청",
  CONFIRM_FINISH: "정산 완료 확인",
  NOT_YET_FINISH: "아직 안 끝났어요",
  FINISH_ALONE: "혼자 정산 완료",
  CLOSE_FINISH_REQUEST: "정산 완료 요청 닫힘",
  AUTOCLOSE: "자동으로 닫힘",
  HIDE_PHOTO: "사진 숨기기",
  SHOW_PHOTO: "사진 다시 보이기",
  MARK_PHOTO_WRONG: "잘못 올린 사진 표시",
  UNMARK_PHOTO_WRONG: "잘못 올린 사진 표시 풀기",
  ERASE_PHOTO: "사진 지우기",
}

// 응답 행동의 payload.answer(맞아요·보류·이견)
const ANSWER_LABEL: Record<string, string> = {
  ACK: "맞아요",
  NEED_MORE: "보류",
  DISPUTE: "이견",
}

const toRole = (s: ServerSide): Role => (s === "TENANT" ? "임차인" : "임대인")

// 남긴 측이 없는(side null) 행동은 서버 행동이거나 운영 행동이에요(RoomTimeline.Item.side).
// 사진 숨김·지움 등 사진 행동은 측 없이 남으면 운영이 한 것이라(06 PHO-7) "운영"으로, 나머지는 "서버 자동"으로 보여요
const OPERATOR_TYPES = new Set([
  "HIDE_PHOTO",
  "SHOW_PHOTO",
  "MARK_PHOTO_WRONG",
  "UNMARK_PHOTO_WRONG",
  "ERASE_PHOTO",
])
const byOf = (it: TimelineItemBody) =>
  it.side ? toRole(it.side) : OPERATOR_TYPES.has(it.type) ? "운영" : "서버 자동"

// 이 모양이 아닌 줄은 경과 전체를 버려요(부른 쪽이 앞서 받은 경과를 둬요)
const isItem = (it: unknown): it is TimelineItemBody => {
  if (!it || typeof it !== "object") return false
  const x = it as Record<string, unknown>
  const target = x.target as Record<string, unknown> | null | undefined
  return (
    typeof x.id === "string" &&
    typeof x.at === "string" &&
    typeof x.type === "string" &&
    (x.side === null || x.side === "TENANT" || x.side === "LANDLORD") &&
    (target === null ||
      (typeof target === "object" &&
        typeof target.kind === "string" &&
        typeof target.id === "string")) &&
    (x.reason === null ||
      x.reason === undefined ||
      typeof x.reason === "string")
  )
}
// 수리 건 제목을 못 찾았을 때(수리 건 목록만 실패한 첫 받기 등)의 대상 이름
const REPAIR_SUBJECT = "수리 건"

export const actionLabel = (type: string) => ACTION_LABEL[type] ?? "기록"

// 경과 한 줄 → 화면 줄(Event). 글자는 "행동 · 응답 · 사유", 대상 이름(수리 건 제목)은 subject에 따로 둬요.
// 호실 활동 이력은 subject를 대상으로 보이고, 수리 건 상세는 보이지 않아요(Timeline stripTitle).
// 모양이 다른 줄이 있으면 던져요(#368 리뷰)
export function eventsFromTimeline(
  roomId: string,
  body: RoomTimelineBody,
  repairs: readonly Pick<Repair, "id" | "title">[],
): Event[] {
  const titles = new Map(repairs.map((r) => [r.id, r.title]))
  if (!Array.isArray(body.items) || !body.items.every(isItem))
    throw new Error("경과 응답 모양이 달라요.")
  return body.items.map((it) => {
    const repairId =
      it.target?.kind === "REPAIR_CASE" ? it.target.id : undefined
    const answer =
      typeof it.payload?.answer === "string"
        ? ANSWER_LABEL[it.payload.answer]
        : undefined
    const text = [actionLabel(it.type), answer, it.reason?.trim()]
      .filter(Boolean)
      .join(" · ")
    return {
      id: it.id,
      roomId,
      text,
      by: byOf(it),
      at: it.at,
      ...(repairId
        ? {
            subject: titles.get(repairId) || REPAIR_SUBJECT,
            target: { kind: "repair" as const, id: repairId },
          }
        : {}),
      ...(it.target ? { ref: { kind: it.target.kind, id: it.target.id } } : {}),
    }
  })
}
