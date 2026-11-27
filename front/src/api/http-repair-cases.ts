import type { RepairCasesApi } from "./repair-cases"
import { notConnected } from "./connected"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"
import {
  causeBasisCode,
  repairFromDetail,
  repairFromListItem,
  type RepairCaseDetailBody,
  type RepairCasePageBody,
} from "./http-repair-view"
import type { Repair } from "@/data/demo"

// 수리 건 API의 HTTP 구현(06 4-6). 상태를 바꾸는 요청은 http-client가 Idempotency-Key를 붙여요.
// 응답은 http-repair-view가 서버 모양(RepairCaseDetail)을 화면 모델로 바꿔요.
// 사진은 업로드 표(upload-tickets, PHO-1·C)를 아직 받지 못해서, 사진을 고른 요청은 서버로 보내지 않고 "연결 전"으로 안내해요.
const noPhotosYet = (photos: readonly unknown[] | undefined) => {
  if (photos?.length) throw notConnected()
}

// 호실의 수리 건 목록(GET /api/rooms/{id}/repair-cases, 06 4-6)을 끝까지 받아 화면 모델로 바꿔요. 새것부터예요.
// asOf는 이 목록의 기준 시각(공유를 그만둔 뒤 보이는 범위를 고정한 시각, 없으면 null)이에요. 첫 쪽 값을 써요.
// 호실 상세의 asOf와 따로 계산되니(06 4-6) 목록 화면은 이 값을 써요
const REPAIR_PAGE_SIZE = 100
export interface RepairList {
  asOf: string | null
  items: Repair[]
}
export async function listAllRepairs(
  request: HttpRequest,
  roomId: string,
): Promise<RepairList> {
  const items: Repair[] = []
  const seen = new Set<string>()
  let asOf: string | null | undefined
  let cursor: string | null = null
  do {
    const page: RepairCasePageBody = await request<RepairCasePageBody>(
      "GET",
      endpoints.repairCasePage(roomId, REPAIR_PAGE_SIZE, cursor ?? undefined),
    )
    // 탈퇴한 상대 등으로 서버가 asOf를 비워 보낼 수 있어요(null·없음 모두 "기준 없음")
    if (asOf === undefined) asOf = page.asOf ?? null
    items.push(...page.items.map((b) => repairFromListItem(b, roomId)))
    cursor = page.nextCursor
    // 같은 커서가 다시 오면(서버 오류) 끝없이 받지 않고 멈춰요
    if (cursor && seen.has(cursor))
      throw new Error("수리 건 목록을 끝까지 받지 못했어요.")
    if (cursor) seen.add(cursor)
  } while (cursor)
  return { asOf: asOf ?? null, items }
}

export function createHttpRepairCases(request: HttpRequest): RepairCasesApi {
  const detail = async (id: string) =>
    repairFromDetail(
      await request<RepairCaseDetailBody>("GET", endpoints.repairCase(id)),
    )
  const act = async (
    id: string,
    action: Parameters<typeof endpoints.repairAction>[1],
    body?: unknown,
  ) => {
    // 행동 요청 뒤에는 상세를 다시 받아 availableActions까지 새로 그려요
    await request("POST", endpoints.repairAction(id, action), { body })
    return detail(id)
  }
  return {
    get: (_s, id) => detail(id),
    respond: (_s, id, b) => act(id, "respond", b),
    // 06 4-6 보충: {description, uploadTickets}
    supplement: async (_s, id, b) => {
      noPhotosYet(b.photos)
      return act(id, "supplement", { description: b.text, uploadTickets: [] })
    },
    start: (_s, id) => act(id, "start"),
    requestClose: (_s, id) => act(id, "request-close"),
    // 하자 "맞아요"를 함께 남길 때만 본 판 번호(seenVersionNo)를 보내요(06 REP-3a)
    confirmClose: (_s, id, b) => act(id, "confirm-close", b),
    notYet: (_s, id, b) => act(id, "not-yet", b),
    closeAlone: (_s, id) => act(id, "close-alone"),
    // 다시 열기는 없앴어요(decisions 2026-11-25). 서버에 API가 없어 보내지 않아요
    reopen: () => Promise.reject(notConnected()),
    // 하자 등록은 부위 목록(A)과 사진 업로드 표(C)가 연결된 뒤에 맞춰요(#179). 지금은 connected.ts에 없어 서버로 가지 않아요
    create: async (_s, roomId, b) => {
      const made = await request<{ id: string }>(
        "POST",
        endpoints.repairCases(roomId),
        { body: b },
      )
      return detail(made.id)
    },
    // 사진 추가(POST /photos)는 서버에 아직 없어요(REP-7·PHO-1). connected.ts에 없어 서버로 가지 않아요
    addPhotos: (_s, id, b) => act(id, "photos", b),
    // 06 4-6 원인 설명: {text, basis, expertName, previousId, uploadTickets}. 업체 이름은 EXPERT일 때만 보내요
    addCauseNote: async (_s, id, b) => {
      noPhotosYet(b.photos)
      const basis = causeBasisCode(b.basis)
      return act(id, "cause-notes", {
        text: b.text,
        basis,
        expertName: basis === "EXPERT" ? (b.company ?? null) : null,
        previousId: b.previousId ?? null,
        uploadTickets: [],
      })
    },
    // 06 4-6 진행 상황: 묻기는 본문 없음, 남기기는 {text, uploadTickets, asksCheck}, 답은 진행 상황에 {answer, reason}
    askProgress: (_s, id) => act(id, "ask-progress"),
    addProgressNote: async (_s, id, b) => {
      noPhotosYet(b.photos)
      return act(id, "progress-notes", {
        text: b.text.trim(),
        uploadTickets: [],
        asksCheck: b.asksCheck,
      })
    },
    respondProgressNote: async (_s, id, noteId, b) => {
      await request("POST", endpoints.progressNoteRespond(noteId), {
        body: b.answer === "ACK" ? { answer: b.answer } : b,
      })
      return detail(id)
    },
    respondCauseNote: async (_s, id, causeId, b) => {
      await request("POST", endpoints.causeNoteRespond(causeId), { body: b })
      return detail(id)
    },
  }
}
