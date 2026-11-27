import type { Repair } from "@/data/demo"
import type { RepairAction } from "./actions"
import type { Session } from "./types"

// 수리 건 API(06 4장 `/api/repair-cases/{id}` …). 화면은 이 인터페이스만 불러요.
// 뒤에는 목업(api/mock/repair-cases.ts) 또는 HTTP(api/http-repair-cases.ts)가 들어가요.
// 응답 본문 형식이 명세에 아직 없어 화면 모델(Repair)을 그대로 쓰고, 백엔드가 생기면 변환 함수만 바꿔요.

// GET /api/repair-cases/{id}: 수리 건 상세 + 지금 이 사람이 누를 수 있는 행동
export interface RepairCaseView {
  repair: Repair
  availableActions: RepairAction[]
}

// 응답 두 버튼(08 2장): ACK = 맞아요, NEED_MORE = 보류, DISPUTE = 이견(사실과 달라요)
export type RespondAnswer = "ACK" | "NEED_MORE" | "DISPUTE"

// 하자 등록 본문(POST /api/rooms/{id}/repair-cases). 사진은 지금 화면 값(data URL)이고,
// 백엔드가 생기면 업로드 표(upload-tickets, PHO-1) 번호로 바뀌어요.
export interface NewRepairCase {
  title: string
  zone: string
  description: string
  photos: string[]
  // 같은 곳 재발이면 이전 수리 건(REP 재발)
  recurrenceOf?: string
}

// 원인 설명(REP-8, 08 5-3): 근거 종류는 필수, 업체·전문가 소견이면 업체명, 들은 말이면 누구에게서
export interface NewCauseNote {
  basis: string
  text: string
  company?: string
  source?: string
  photos: string[]
  // 고칠 때는 내 지금 설명 ID(06 4-6 `previousId`). 처음 남기면 비워요
  previousId?: string
}

// 진행 상황 남기기(REP-6, 08 5-2): 글이나 사진 하나 이상. asksCheck면 상대에게 확인 요청(하자를 둘 다 확인한 건만, 7일에 한 번)
export interface NewProgressNote {
  text: string
  photos: string[]
  asksCheck: boolean
}

export interface RepairCasesApi {
  get(s: Session, id: string): Promise<RepairCaseView>
  // POST /api/rooms/{roomId}/repair-cases: 하자 등록(재발 등록 포함)
  create(
    s: Session,
    roomId: string,
    body: NewRepairCase,
  ): Promise<RepairCaseView>
  // POST /photos: 사진 추가. afterRecord가 있으면 수리 후 기록(제목·설명)으로 남아요
  addPhotos(
    s: Session,
    id: string,
    body: {
      photos: string[]
      afterRecord?: { title: string; description: string }
    },
  ): Promise<RepairCaseView>
  // POST /cause-notes: 원인 설명 남기기. 고치면 언제나 새 설명으로 남아요(decisions 2026-11-13)
  addCauseNote(
    s: Session,
    id: string,
    body: NewCauseNote,
  ): Promise<RepairCaseView>
  // POST /api/cause-notes/{causeId}/respond: 상대 쪽의 동의(ACK)·이견(DISPUTE)
  // 06 4장은 NEED_MORE도 두지만 08 5-3은 동의·이견만이라, 화면은 두 문서에 모두 맞는 두 가지만 보내요.
  respondCauseNote(
    s: Session,
    id: string,
    causeId: string,
    body: { answer: "ACK" | "DISPUTE"; reason?: string },
  ): Promise<RepairCaseView>
  // POST /ask-progress: 진행 상황 묻기(본문 없음, 누가 물었든 한 건에 7일에 한 번)
  askProgress(s: Session, id: string): Promise<RepairCaseView>
  // POST /progress-notes {text, uploadTickets, asksCheck}
  addProgressNote(
    s: Session,
    id: string,
    body: NewProgressNote,
  ): Promise<RepairCaseView>
  // POST /api/progress-notes/{noteId}/respond: [상황이 바뀌었어요]에 상대가 답해요(맞아요·보류·이견)
  respondProgressNote(
    s: Session,
    id: string,
    noteId: string,
    body: { answer: RespondAnswer; reason?: string },
  ): Promise<RepairCaseView>
  // POST /respond {answer, reason, seenVersionNo}
  respond(
    s: Session,
    id: string,
    body: { answer: RespondAnswer; reason?: string; seenVersionNo: number },
  ): Promise<RepairCaseView>
  // POST /supplement: 보충하고 다시 확인 요청(새 근거 하나 이상, 7일에 한 번)
  supplement(
    s: Session,
    id: string,
    body: { text: string; photos: string[] },
  ): Promise<RepairCaseView>
  // POST /start
  start(s: Session, id: string): Promise<RepairCaseView>
  // POST /request-close
  requestClose(s: Session, id: string): Promise<RepairCaseView>
  // POST /confirm-close: 하자 인정이 "확인 기다림"이면 alsoAckDefect로 하자 "맞아요"까지 함께 남겨요(06 REP-3a).
  // 보류·이견은 하자 응답(respond)으로 따로 해요(decisions 2026-11-17)
  confirmClose(
    s: Session,
    id: string,
    // alsoAckDefect면 본 판 번호(seenVersionNo, 상세 content.no)도 함께 보내요(하자 응답과 같음)
    body?: { alsoAckDefect?: boolean; seenVersionNo?: number },
  ): Promise<RepairCaseView>
  // POST /not-yet {reason}
  notYet(
    s: Session,
    id: string,
    body: { reason: string },
  ): Promise<RepairCaseView>
  // POST /close-alone: 혼자 종료(이유는 서버가 정해요)
  closeAlone(s: Session, id: string): Promise<RepairCaseView>
  // POST /reopen {reason}
  reopen(
    s: Session,
    id: string,
    body: { reason: string },
  ): Promise<RepairCaseView>
}
