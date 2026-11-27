import type { RepairCasesApi } from "./repair-cases"
import { notConnected } from "./connected"
import { endpoints } from "./endpoints"
import { ApiError } from "./errors"
import type { HttpRequest } from "./http-client"
import {
  causeBasisCode,
  photoIdsOf,
  repairFromDetail,
  repairFromListItem,
  type RepairCaseDetailBody,
  type RepairCasePageBody,
} from "./http-repair-view"
import { createHttpPhotoUploads, type PhotoUploadsApi } from "./photo-uploads"
import type { Repair } from "@/data/demo"
import type { RoomPart } from "@/lib/room-parts"

// 수리 건 API의 HTTP 구현(06 4-6). 상태를 바꾸는 요청은 http-client가 Idempotency-Key를 붙여요.
// 응답은 http-repair-view가 서버 모양(RepairCaseDetail)을 화면 모델로 바꿔요.
// 사진을 고른 행동은 먼저 사진을 올려 업로드 표를 받고(photo-uploads, PHO-1) 그 표를 본문에 넣어요(#366).

// 한 번에 받는 사진 주소(06 0-1 photo.url.batch.max)
const PHOTO_URL_BATCH = 50
// 사진 썸네일 주소를 받아요(06 4-5 view-urls, 조회라 멱등 키 없음). 받지 못해도 상세는 보여요(사진만 "불러오지 못함")
async function thumbUrls(
  request: HttpRequest,
  ids: readonly string[],
): Promise<Map<string, string>> {
  const urls = new Map<string, string>()
  for (let i = 0; i < ids.length; i += PHOTO_URL_BATCH) {
    const photoIds = ids.slice(i, i + PHOTO_URL_BATCH)
    try {
      const { items } = await request<{
        items: { photoId: string; url: string | null }[]
      }>("POST", endpoints.photoViewUrls, {
        body: { photoIds, variant: "THUMB" },
        idempotent: false,
      })
      for (const it of items) if (it.url) urls.set(it.photoId, it.url)
    } catch (e) {
      // 로그인이 풀린 것(재발급까지 실패한 401)은 상세 오류로 알려요. 그 밖의 실패는 그 사진만 "불러오지 못함"으로 두고 다음 묶음을 받아요(#371 리뷰)
      if (e instanceof ApiError && e.status === 401) throw e
    }
  }
  return urls
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

// partsOf: 호실 부위 목록. 목록을 받지 못해도 서버의 partName으로 상세를 보여요.
export function createHttpRepairCases(
  request: HttpRequest,
  photoUploads: PhotoUploadsApi = createHttpPhotoUploads(request),
  partsOf?: (roomId: string) => Promise<RoomPart[]>,
): RepairCasesApi {
  // 수리 건 → 호실. 업로드 표는 호실마다 받아서(06 4-5) 상세를 받을 때 기억해 둬요
  const roomOf = new Map<string, string>()
  const body = async (id: string) => {
    const b = await request<RepairCaseDetailBody>(
      "GET",
      endpoints.repairCase(id),
    )
    roomOf.set(id, b.roomRecordId)
    return b
  }
  const detail = async (id: string) => {
    const b = await body(id)
    const parts = await partsOf?.(b.roomRecordId).catch(() => undefined)
    return repairFromDetail(b, await thumbUrls(request, photoIdsOf(b)), parts)
  }
  // 고른 사진을 올리고 업로드 표를 돌려줘요. 사진이 없으면 빈 목록
  const ticketsOf = async (id: string, photos: readonly string[] = []) => {
    if (!photos.length) return []
    const roomId = roomOf.get(id) ?? (await body(id)).roomRecordId
    return photoUploads.upload(roomId, photos)
  }
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
    supplement: async (_s, id, b) =>
      act(id, "supplement", {
        description: b.text,
        uploadTickets: await ticketsOf(id, b.photos),
      }),
    start: (_s, id) => act(id, "start"),
    requestClose: (_s, id) => act(id, "request-close"),
    // 하자 "맞아요"를 함께 남길 때만 본 판 번호(seenVersionNo)를 보내요(06 REP-3a)
    confirmClose: (_s, id, b) => act(id, "confirm-close", b),
    notYet: (_s, id, b) => act(id, "not-yet", b),
    closeAlone: (_s, id) => act(id, "close-alone"),
    // 다시 열기는 없앴어요(decisions 2026-11-25). 서버에 API가 없어 보내지 않아요
    reopen: () => Promise.reject(notConnected()),
    // 하자 등록은 사진 업로드 표(C)가 연결된 뒤에 맞춰요(#179). 지금은 connected.ts에 없어 서버로 가지 않아요.
    // 부위는 06 4-6 본문처럼 roomPartId로 보내요(#356)
    create: async (_s, roomId, b) => {
      const made = await request<{ id: string }>(
        "POST",
        endpoints.repairCases(roomId),
        {
          body: {
            roomPartId: b.roomPartId,
            title: b.title,
            description: b.description,
            photos: b.photos,
            recurrenceOf: b.recurrenceOf,
          },
        },
      )
      return detail(made.id)
    },
    // 사진 추가(POST /photos)는 서버에 아직 없어요(REP-7·PHO-1). connected.ts에 없어 서버로 가지 않아요
    addPhotos: (_s, id, b) => act(id, "photos", b),
    // 06 4-6 원인 설명: {text, basis, expertName, previousId, uploadTickets}. 업체 이름은 EXPERT일 때만 보내요
    addCauseNote: async (_s, id, b) => {
      const basis = causeBasisCode(b.basis)
      const uploadTickets = await ticketsOf(id, b.photos)
      return act(id, "cause-notes", {
        text: b.text,
        basis,
        expertName: basis === "EXPERT" ? (b.company ?? null) : null,
        previousId: b.previousId ?? null,
        uploadTickets,
      })
    },
    // 06 4-6 진행 상황: 묻기는 본문 없음, 남기기는 {text, uploadTickets, asksCheck}, 답은 진행 상황에 {answer, reason}
    askProgress: (_s, id) => act(id, "ask-progress"),
    addProgressNote: async (_s, id, b) =>
      act(id, "progress-notes", {
        text: b.text.trim(),
        uploadTickets: await ticketsOf(id, b.photos),
        asksCheck: b.asksCheck,
      }),
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
