import type { RepairCasesApi, RepairCaseView } from "./repair-cases"
import { endpoints } from "./endpoints"
import type { HttpRequest } from "./http-client"

// 수리 건 API의 HTTP 구현(06 4장). 상태를 바꾸는 요청은 http-client가 Idempotency-Key를 붙여요.
// 응답 본문 형식이 명세에 아직 없어, 백엔드가 생기면 toView만 서버 모양(OpenAPI 타입)에 맞춰 바꿔요.
const toView = (body: unknown) => body as RepairCaseView

export function createHttpRepairCases(request: HttpRequest): RepairCasesApi {
  const act = async (
    id: string,
    action: Parameters<typeof endpoints.repairAction>[1],
    body?: unknown,
  ) => {
    // 행동 요청 뒤에는 상세를 다시 받아 availableActions까지 새로 그려요
    await request("POST", endpoints.repairAction(id, action), { body })
    return toView(await request<unknown>("GET", endpoints.repairCase(id)))
  }
  return {
    get: async (_s, id) =>
      toView(await request<unknown>("GET", endpoints.repairCase(id))),
    respond: (_s, id, b) => act(id, "respond", b),
    supplement: (_s, id, b) => act(id, "supplement", b),
    start: (_s, id) => act(id, "start"),
    requestClose: (_s, id) => act(id, "request-close"),
    confirmClose: (_s, id, b) => act(id, "confirm-close", b),
    notYet: (_s, id, b) => act(id, "not-yet", b),
    closeAlone: (_s, id) => act(id, "close-alone"),
    reopen: (_s, id, b) => act(id, "reopen", b),
    // 하자 등록: 서버가 만든 수리 건 ID를 받아 상세를 다시 읽어요(06 4-0: 만들기면 201 + 만든 ID)
    create: async (_s, roomId, b) => {
      const made = await request<{ id: string }>(
        "POST",
        endpoints.repairCases(roomId),
        { body: b },
      )
      return toView(
        await request<unknown>("GET", endpoints.repairCase(made.id)),
      )
    },
    addPhotos: (_s, id, b) => act(id, "photos", b),
    addCauseNote: (_s, id, b) => act(id, "cause-notes", b),
    respondCauseNote: async (_s, id, causeId, b) => {
      await request("POST", endpoints.causeNoteRespond(causeId), { body: b })
      return toView(await request<unknown>("GET", endpoints.repairCase(id)))
    },
  }
}
