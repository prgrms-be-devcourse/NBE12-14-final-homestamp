import { isConnected, notConnected } from "./connected"
import {
  emptyState,
  type DemoState,
  type Event,
  type Inspection,
  type Notice,
  type Repair,
  type Room,
} from "@/data/demo"
import { listAllRepairs, type RepairList } from "./http-repair-cases"
import {
  getInspection,
  inspectionFromDetail,
  moveInInspectionId,
} from "./http-inspection-view"
import { endpoints } from "./endpoints"
import { ApiError } from "./errors"
import type { HttpRequest } from "./http-client"
import {
  eventsFromTimeline,
  getTimeline,
  type RoomTimelineBody,
} from "./http-timeline"
import {
  getRoomDetail,
  isNotFound,
  listAllRooms,
  roomFromDetail,
  roomFromSummary,
} from "./http-rooms"
import { inspectionIdOf, isMissing, isNone, type RecordsApi } from "./records"
import type { Session } from "./types"

// 점검을 아직 받지 못해 서버 점검 ID를 모를 때(받기가 실패했거나 받는 중)
const inspectionNotLoaded = () =>
  new ApiError({
    status: 0,
    code: "INSPECTION_NOT_LOADED",
    detail: "점검을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  })

// HTTP 기록 API: 서버에서 받은 기록을 화면 모델로 바꿔 두고, 기록을 바꾸는 요청은 POST로 보낸 뒤 다시 받아요.
// 호실은 http-rooms가 서버 모양(06 4-3)을 화면 모델로 바꿔요. 나머지는 연결할 때 변환 함수를 서버 모양에 맞춰 바꿔요.
const toNotices = (body: unknown) => body as Notice[]
// 아직 연결 전인 목록은 빈 목록으로 두고, 다른 목록(호실)은 그대로 받아요
const unlessNotConnected =
  <T>(fallback: T) =>
  (e: unknown) => {
    if (e instanceof ApiError && e.code === "NOT_CONNECTED") return fallback
    throw e
  }
// 수리 건은 서버 목록과 같은 순서(등록 시각 새것부터, 같으면 ID, 06 4-6)로 둬요
const atMs = (r: Repair) => (r.createdAt ? Date.parse(r.createdAt) || 0 : 0)
const newestFirst = (a: Repair, b: Repair) =>
  atMs(b) - atMs(a) || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0)

export function createHttpRecords(request: HttpRequest): RecordsApi {
  let state: DemoState = emptyState()
  const listeners = new Set<() => void>()
  const emit = () => listeners.forEach((l) => l())
  // 받기를 시작한 차례. 호실마다 마지막으로 반영한 받기의 시작 차례를 freshAt에 둬요.
  // 늦게 끝난 받기(load·loadRoom)가 더 늦게 시작한 받기의 값을 덮지 않게 해요
  let tick = 0
  const freshAt = new Map<string, number>()
  const newer = (roomId: string, startedAt: number) =>
    (freshAt.get(roomId) ?? 0) > startedAt
  // 지금 기록의 계정. api는 앱에서 한 번만 만들어 로그아웃 뒤에도 남아요(App.tsx).
  // 다른 계정으로 받기 시작하면 앞 계정의 기록을 모두 비워요. 경과·수리 건은 측마다 보이는 범위가 달라(03 3-1)
  // "앞서 받은 값 두기"가 앞 계정의 값을 보이면 안 돼요(#368 리뷰)
  let owner: string | undefined
  const forAccount = (s: Session) => {
    if (owner === s.accountId) return
    const had = owner !== undefined
    owner = s.accountId
    state = emptyState()
    freshAt.clear()
    if (had) emit()
  }
  // 기다리는 사이 계정이 바뀌었으면 그 받기의 값은 버려요
  const stale = (s: Session) => owner !== s.accountId
  // 호실 상세와 수리 건 목록(GET /api/rooms/{id}/repair-cases, 06 4-6)을 따로 받아요(한쪽 실패가 다른 쪽을 버리지 않게).
  // - 상세가 404(남의 호실·없는 호실)면 호실을 빼요(ROOM-2a). 수리 건 목록만 404여도 호실은 빼지 않아요
  // - 상세가 다른 오류(500·연결 끊김)면 목록 값은 두고 오류를 함께 돌려줘요. 부른 쪽이 화면에 알려요(버튼이 조용히 사라지지 않게)
  // - 수리 건 목록만 실패하면 그 호실의 수리 건은 앞서 받은 것을 둬요(list 없음). 알리지 않아요(다음 받기 때 다시 받아요)
  // 목록에 아직 없는 호실(before 없음)은 상세만으로 만들어요.
  // 경과(GET /api/rooms/{id}/timeline, REP-11)도 함께 받아요. 경과만 실패하면 수리 건 목록처럼 앞서 받은 경과를 둬요(#359)
  // 입주 점검은 그 경과의 점검 시작 줄에서 ID를 찾아 상세(GET /api/inspections/{id})를 받아요(#357).
  // 경과나 점검 상세만 실패하면 앞서 받은 점검을 둬요(inspections 없음). 점검이 없으면 빈 목록이에요
  type Detail = {
    room: Room | null
    list?: RepairList
    timeline?: RoomTimelineBody
    inspections?: Inspection[]
    error?: unknown
  }
  const keptMoveIn = (roomId: string) =>
    state.rooms.find((r) => r.id === roomId)?.moveIn
  const moveInOf = async (timeline: RoomTimelineBody | undefined) => {
    if (!timeline) return undefined
    try {
      const id = moveInInspectionId(timeline)
      if (!id) return { session: undefined, items: [] as Inspection[] }
      return inspectionFromDetail(await getInspection(request, id))
    } catch {
      return undefined
    }
  }
  const detailOf = async (
    s: Session,
    roomId: string,
    before?: Room,
  ): Promise<Detail> => {
    const [d, l, t] = await Promise.allSettled([
      getRoomDetail(request, roomId),
      listAllRepairs(request, roomId),
      getTimeline(request, roomId),
    ])
    if (d.status === "rejected") {
      if (isNotFound(d.reason))
        return { room: null, list: { asOf: null, items: [] }, inspections: [] }
      const room = before ? { ...before, moveIn: keptMoveIn(roomId) } : null
      return { room, error: d.reason }
    }
    // 줄 모양은 withEvents가 확인해요(다르면 그 호실은 앞서 받은 경과를 둬요)
    const timeline =
      t.status === "fulfilled" && t.value && typeof t.value === "object"
        ? t.value
        : undefined
    const moveIn = await moveInOf(timeline)
    const room = roomFromDetail(d.value, s.accountId, before)
    return {
      room: {
        ...room,
        moveIn: moveIn ? moveIn.session : keptMoveIn(roomId),
      },
      list: l.status === "fulfilled" ? l.value : undefined,
      timeline,
      inspections: moveIn?.items,
    }
  }
  // 받은 호실의 점검 항목으로 바꿔요. 받지 못한 호실은 앞서 받은 것을 두고, 목록에서 빠진 호실 것은 지워요
  const withInspections = (
    rooms: readonly Room[],
    fresh: ReadonlyMap<string, Inspection[]>,
  ): Pick<DemoState, "inspections"> => {
    const roomIds = new Set(rooms.map((r) => r.id))
    return {
      inspections: [
        ...state.inspections.filter(
          (i) => roomIds.has(i.roomId) && !fresh.has(i.roomId),
        ),
        ...[...fresh.values()].flat(),
      ],
    }
  }
  // 받은 호실의 경과와 경과 기준 시각(asOf, 공유를 그만둔 뒤 "○월 ○일 기준")으로 바꿔요.
  // 받지 못했거나 모양이 다른 줄이 있는 호실은 앞서 받은 경과를 두고(호실·수리 건 반영은 막지 않아요), 목록에서 빠진 호실 것은 지워요.
  // 대상 이름(수리 건 제목)은 방금 받은 수리 건 목록에서 찾아요
  const withEvents = (
    rooms: readonly Room[],
    fresh: ReadonlyMap<string, RoomTimelineBody>,
    repairs: readonly Repair[],
  ): Pick<DemoState, "events" | "timelineAsOf"> => {
    const roomIds = new Set(rooms.map((r) => r.id))
    const converted = new Map<string, Event[]>()
    for (const [id, b] of fresh) {
      try {
        converted.set(id, eventsFromTimeline(id, b, repairs))
      } catch {
        // 앞서 받은 경과를 둬요(다음 받기 때 다시 받아요)
      }
    }
    const events = [
      ...state.events.filter(
        (e) => roomIds.has(e.roomId) && !converted.has(e.roomId),
      ),
      ...[...converted.values()].flat(),
    ]
    const timelineAsOf: Record<string, string | null> = {}
    for (const id of roomIds) {
      const asOf = converted.has(id)
        ? (fresh.get(id)!.asOf ?? null)
        : state.timelineAsOf?.[id]
      if (asOf !== undefined) timelineAsOf[id] = asOf
    }
    return { events, timelineAsOf }
  }
  // 받은 호실의 수리 건·목록 기준 시각으로 바꿔요. 받지 못한 호실은 앞서 받은 것을 두고, 목록에서 빠진 호실 것은 지워요
  const withRepairs = (
    rooms: readonly Room[],
    fresh: ReadonlyMap<string, RepairList>,
  ): Pick<DemoState, "repairs" | "repairListAsOf"> => {
    const roomIds = new Set(rooms.map((r) => r.id))
    const repairs = [
      ...state.repairs.filter(
        (r) => roomIds.has(r.roomId) && !fresh.has(r.roomId),
      ),
      ...[...fresh.values()].flatMap((l) => l.items),
    ].sort(newestFirst)
    const repairListAsOf: Record<string, string | null> = {}
    for (const id of roomIds) {
      const list = fresh.get(id)
      const asOf = list ? list.asOf : state.repairListAsOf?.[id]
      if (asOf !== undefined) repairListAsOf[id] = asOf
    }
    return { repairs, repairListAsOf }
  }
  const load = async (s: Session) => {
    forAccount(s)
    const startedAt = ++tick
    // 06 4장의 목록 조회로 받을 수 있는 것: 내 호실, 알림.
    // 호실 화면 칸(입주일·만든 측·행동)은 상세에만 있어 호실마다 상세도 받아요.
    // 수리 건 목록·경과도 호실마다 상세와 함께 받아요(detailOf). 점검·부위 사진·정산은 서버 응답 모양이 정해지면 여기에서 채워요.
    const [summaries, notices] = await Promise.all([
      listAllRooms(request),
      request<unknown>("GET", endpoints.notifications)
        .then(toNotices)
        .catch(unlessNotConnected<Notice[]>([])),
    ])
    const before = new Map(state.rooms.map((r) => [r.id, r]))
    const listed = summaries.map((b) =>
      roomFromSummary(b, s.accountId, before.get(b.id)),
    )
    const results = await Promise.all(listed.map((r) => detailOf(s, r.id, r)))
    if (stale(s)) return
    const now = new Map(state.rooms.map((r) => [r.id, r]))
    // 기다리는 사이 더 늦게 시작한 받기(loadRoom)가 반영한 호실은 호실·수리 건 모두 그 값을 둬요
    const rooms = results
      .map(({ room }) =>
        room && newer(room.id, startedAt) ? (now.get(room.id) ?? null) : room,
      )
      .filter((r): r is Room => r !== null)
    const fresh = new Map<string, RepairList>()
    const freshTimelines = new Map<string, RoomTimelineBody>()
    const freshInspections = new Map<string, Inspection[]>()
    for (const { room, list, timeline, inspections, error } of results) {
      if (!room || error || newer(room.id, startedAt)) continue
      freshAt.set(room.id, startedAt)
      if (list) fresh.set(room.id, list)
      if (timeline) freshTimelines.set(room.id, timeline)
      if (inspections) freshInspections.set(room.id, inspections)
    }
    const repaired = withRepairs(rooms, fresh)
    state = {
      ...state,
      rooms,
      ...repaired,
      ...withEvents(rooms, freshTimelines, repaired.repairs),
      ...withInspections(rooms, freshInspections),
      notices,
    }
    emit()
    const failed = results.find((r) => r.error)
    if (failed) throw failed.error
  }
  // 목록에 없는 호실도 상세를 받아 넣어요. 만든 뒤 목록 다시 받기가 실패해도 새 호실 화면이 열려요(#241 리뷰).
  // 남의 호실·없는 호실(404)이면 넣지 않아 '찾을 수 없는 기록'이 그대로 보여요.
  const loadRoom = async (s: Session, roomId: string) => {
    forAccount(s)
    const startedAt = ++tick
    const {
      room: next,
      list,
      timeline,
      inspections,
      error,
    } = await detailOf(
      s,
      roomId,
      state.rooms.find((r) => r.id === roomId),
    )
    // 기다리는 사이 더 늦게 시작한 받기가 이미 반영했거나 계정이 바뀌었으면 이 값(오류 포함)은 버려요(#293·#368 리뷰)
    if (stale(s) || newer(roomId, startedAt)) return
    if (error) throw error
    freshAt.set(roomId, startedAt)
    const known = state.rooms.some((r) => r.id === roomId)
    const rooms = !next
      ? state.rooms.filter((r) => r.id !== roomId)
      : known
        ? state.rooms.map((r) => (r.id === roomId ? next : r))
        : [next, ...state.rooms]
    const fresh = new Map<string, RepairList>(list ? [[roomId, list]] : [])
    const repaired = withRepairs(rooms, fresh)
    const freshTimelines = new Map<string, RoomTimelineBody>(
      timeline ? [[roomId, timeline]] : [],
    )
    const freshInspections = new Map<string, Inspection[]>(
      inspections ? [[roomId, inspections]] : [],
    )
    state = {
      ...state,
      rooms,
      ...repaired,
      ...withEvents(rooms, freshTimelines, repaired.repairs),
      ...withInspections(rooms, freshInspections),
    }
    emit()
  }
  // 점검 주소는 화면이 서버 점검 ID로 만들어요(inspectionIdIn, #357). 점검을 받기 전이라 목업 자리표(inspectionIdOf)가 든
  // 주소가 연결된 API로 가려 하면 보내지 않아요(없는 점검으로 404가 나지 않게). 연결 전 API는 http-client가 안내해요
  // 퇴거 점검은 아직 받지 않아(연결 전) 자리표면 "연결 전"으로 안내해요(#357 리뷰)
  const placeholderError = (roomId: string, path: string) => {
    const has = (kind: "in" | "out") =>
      path.startsWith(
        `/api/inspections/${encodeURIComponent(inspectionIdOf(roomId, kind))}/`,
      )
    if (has("out")) return notConnected()
    if (has("in") && isConnected("POST", path)) return inspectionNotLoaded()
    return undefined
  }
  return {
    subscribe: (l) => {
      listeners.add(l)
      return () => {
        listeners.delete(l)
      }
    },
    getSnapshot: () => state,
    load,
    loadRoom,
    send: async (s, c) => {
      if (isNone(c.request)) return {}
      if (isMissing(c.request)) throw notConnected()
      const list = Array.isArray(c.request) ? c.request : [c.request]
      let made: { id?: string } | undefined
      const before = state.rooms.find((r) => r.id === c.options.roomId)
      for (const r of list) {
        const e = placeholderError(c.options.roomId, r.path)
        if (e) throw e
      }
      try {
        for (const r of list)
          made = await request<{ id?: string } | undefined>("POST", r.path, {
            body: r.body,
          })
      } catch (e) {
        // 상대가 먼저 점검을 시작했으면 그 점검을 다시 받아 열어요(06 4-7 INSPECTION_EXISTS "그쪽으로 열게요")
        if (e instanceof ApiError && e.code === "INSPECTION_EXISTS" && before)
          await loadRoom(s, before.id).catch(() => {})
        throw e
      }
      // 보내기는 이미 성공했어요. 다시 받기(목록·상세)가 실패해도 실패로 돌려주지 않아요
      // (같은 기록을 다시 보내 두 번 만들지 않게). 새 호실은 그 화면에 들어갈 때 상세로 받고(loadRoom), 그때 실패하면 알려요.
      // 아는 호실의 행동이면 그 호실만 다시 받아요(#293 리뷰). 다만 호실 상태가 바뀌면(거주 종료 등) 목록에만 있는
      // nextStep도 바뀌니 목록도 다시 받아요. 모르는 호실(새로 만듦·연결 수락)도 목록을 다시 받아요
      const refresh = async () => {
        if (!before || c.options.connecting) return load(s)
        await loadRoom(s, before.id)
        const after = state.rooms.find((r) => r.id === before.id)
        if (after?.status !== before.status) await load(s)
      }
      await refresh().catch(() => {})
      return { id: made?.id }
    },
  }
}
