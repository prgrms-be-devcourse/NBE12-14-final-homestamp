import type { RepairAction } from "@/api/actions"
import { ApiError } from "@/api/errors"
import type { RepairCasesApi, RepairCaseView } from "@/api/repair-cases"
import type { Session } from "@/api/types"
import {
  uid,
  type DemoState,
  type Repair,
  type Role,
  type Room,
} from "@/data/demo"
import {
  addCauseNote,
  addRepairPhotos,
  confirmClose,
  confirmDefect,
  confirmSoloClosed,
  notYet,
  reopen,
  requestClose,
  respondCauseNote,
  respondDefect,
  roomSoloReason,
  soloClose,
  startRepair,
  supplementDefect,
  syncRepairSettlements,
} from "@/lib/repair-rules"
import { canAccessRoom, logRepairNotice, sideOf } from "@/lib/workflows"
import { versionOf } from "@/lib/versions"
import { recordAction, type MockStore } from "./store"
import { repairAvailableActions } from "./repair-actions"
import { roomAvailableActions } from "./room-actions"

// 서버가 연결되기 전 Mock: 수리 건 API를 서버 대신 처리해요.
// 서버가 할 일(누가 부르는지 확인 → 지금 할 수 있는 행동인지 확인 → 규칙 적용 → 행동 기록)을 그대로 흉내 내요.
export function createMockRepairCases(store: MockStore): RepairCasesApi {
  const fail = (status: number, code: string, detail: string): never => {
    throw new ApiError({ status, code, detail })
  }
  // 호실 회원만, 남의 호실도 404(06 4-3)
  function locate(s: Session, id: string, d = store.getState()) {
    const repair = d.repairs.find((r) => r.id === id)
    const room = repair && d.rooms.find((r) => r.id === repair.roomId)
    const role = room && sideOf(room, s.accountId)
    if (!repair || !room || !role || !canAccessRoom(room, role))
      return fail(404, "NOT_FOUND", "찾을 수 없는 기록이에요.")
    return { repair, room, role }
  }
  const readOnly = (room: Room) =>
    room.status === "정산 완료" || Boolean(room.preJoinOf)
  function view(s: Session, id: string): RepairCaseView {
    const { repair, room, role } = locate(s, id)
    return {
      repair: structuredClone(repair),
      availableActions: repairAvailableActions(repair, room, role, {
        readOnly: readOnly(room),
      }),
    }
  }
  // 명령 하나: 행동 확인 → 복사본에 먼저 적용해 규칙 위반을 오류로 돌려줌 → 행동 기록
  function command(
    s: Session,
    id: string,
    action: RepairAction,
    text: (r: Repair, role: Role, room: Room) => string,
    update: (r: Repair, role: Role, room: Room) => void,
  ): RepairCaseView {
    const { repair, room, role } = locate(s, id)
    const allowed = repairAvailableActions(repair, room, role, {
      readOnly: readOnly(room),
    })
    if (!allowed.includes(action))
      fail(
        409,
        "INVALID_STATE",
        "지금은 할 수 없는 행동이에요. 화면을 새로 불러왔어요.",
      )
    try {
      update(structuredClone(repair), role, room)
    } catch (e) {
      fail(
        400,
        "VALIDATION_FAILED",
        e instanceof Error ? e.message : "처리할 수 없어요.",
      )
    }
    const label = `${repair.title} · ${text(repair, role, room)}`
    recordAction(
      store,
      role,
      label,
      (d: DemoState) => {
        update(
          d.repairs.find((r) => r.id === id)!,
          role,
          room,
        )
        syncRepairSettlements(d, id, role)
      },
      { roomId: room.id, target: { kind: "repair", id } },
    )
    return view(s, id)
  }
  const later = <T>(f: () => T) =>
    new Promise<T>((resolve, reject) => {
      try {
        resolve(f())
      } catch (e) {
        reject(e)
      }
    })

  return {
    get: (s, id) => later(() => view(s, id)),
    respond: (s, id, b) =>
      later(() =>
        command(
          s,
          id,
          "respond",
          () =>
            b.answer === "ACK"
              ? `하자 사실 확인 · 본 모습 ${b.seenVersionNo}번`
              : `하자 ${b.answer === "NEED_MORE" ? "보충 요청" : "이견"} · ${b.reason ?? ""}`,
          (r, role) =>
            b.answer === "ACK"
              ? confirmDefect(r, role, b.seenVersionNo)
              : respondDefect(
                  r,
                  role,
                  b.answer === "NEED_MORE" ? "보류" : "이견",
                  b.reason ?? "",
                ),
        ),
      ),
    supplement: (s, id, b) =>
      later(() =>
        command(
          s,
          id,
          "supplement",
          () =>
            `보충하고 다시 확인 요청${b.text ? ` · ${b.text}` : ""}${b.photos.length ? ` · 사진 ${b.photos.length}장` : ""}`,
          (r, role) => supplementDefect(r, role, b),
        ),
      ),
    start: (s, id) =>
      later(() =>
        command(
          s,
          id,
          "start",
          (r) =>
            `수리 시작${r.ack === "이견" ? " · 이견 유지 중 수리 시작" : r.ack === "보충 요청" ? " · 보충 요청 중 수리 시작" : r.ack !== "확인됨" ? " · 상대 확인 전 수리 시작" : ""}`,
          (r) => startRepair(r),
        ),
      ),
    requestClose: (s, id) =>
      later(() =>
        command(
          s,
          id,
          "request-close",
          () => "수리 종료 확인 요청",
          (r, role) => requestClose(r, role),
        ),
      ),
    // 종료 확인: 혼자 종료된 건의 늦은 확인도 같은 행동이에요(08 8장)
    confirmClose: (s, id, b) =>
      later(() =>
        command(
          s,
          id,
          "confirm-close",
          (r) =>
            r.soloClosed
              ? "혼자 종료한 건 종료 확인 · 양측 확인"
              : `수리 종료 · 양측 확인${b?.defect ? ` · 하자 ${b.defect.answer === "ACK" ? "확인" : b.defect.answer === "NEED_MORE" ? "보충 요청" : "이견"}` : ""}`,
          (r, role) => {
            if (r.soloClosed) return confirmSoloClosed(r, role)
            if (b?.defect)
              if (b.defect.answer === "ACK")
                confirmDefect(r, role, versionOf(r))
              else
                respondDefect(
                  r,
                  role,
                  b.defect.answer === "NEED_MORE" ? "보류" : "이견",
                  b.defect.reason ?? "",
                )
            confirmClose(r, role)
          },
        ),
      ),
    notYet: (s, id, b) =>
      later(() =>
        command(
          s,
          id,
          "not-yet",
          () => `아직 안 됐어요 · ${b.reason}`,
          (r, role) => notYet(r, role, b.reason),
        ),
      ),
    // 혼자 종료: 종료를 요청했는데 답이 없으면 '상대 미확인', 아니면 호실의 혼자 진행 이유(서버가 정함)
    closeAlone: (s, id) =>
      later(() =>
        command(
          s,
          id,
          "close-alone",
          (r, role, room) =>
            r.closeBy === role
              ? `미확인 뒤 혼자 종료 · 처리 완료(${role} 확인) · 상대 미확인`
              : `수리 혼자 종료 · 처리 완료(${role} 확인) · ${roomSoloReason(room)}`,
          (r, role, room) =>
            soloClose(
              r,
              role,
              r.closeBy === role ? "상대 미확인" : roomSoloReason(room)!,
            ),
        ),
      ),
    reopen: (s, id, b) =>
      later(() =>
        command(
          s,
          id,
          "reopen",
          () => `다시 열기 · ${b.reason}`,
          (r, role) => reopen(r, role, b.reason),
        ),
      ),
    // 하자 등록(REP-1): 그 호실 회원이, 거주 중일 때(ROOM_ENDED). 사진 1장 이상(PHOTO_REQUIRED)
    create: (s, roomId, b) =>
      later(() => {
        const d = store.getState()
        const room = d.rooms.find((r) => r.id === roomId)
        const role = room && sideOf(room, s.accountId)
        if (!room || !role || !canAccessRoom(room, role))
          return fail(404, "NOT_FOUND", "찾을 수 없는 기록이에요.")
        if (
          !roomAvailableActions(room, role, {
            readOnly: readOnly(room),
          }).includes("repair-cases")
        )
          return fail(
            409,
            "ROOM_ENDED",
            "거주가 끝난 호실에는 새 수리 건을 남길 수 없어요.",
          )
        if (!b.title.trim())
          return fail(400, "VALIDATION_FAILED", "제목을 입력해 주세요.")
        if (!b.photos.length)
          return fail(
            400,
            "PHOTO_REQUIRED",
            "수리 전 사진을 1장 이상 첨부해 주세요.",
          )
        const id = uid()
        recordAction(
          store,
          role,
          `${b.title} · 하자 등록${b.recurrenceOf ? " · 이전 수리 건 이후 재발" : ""}${room.connection === "연결 전" ? " · 혼자 남긴 기록" : " · 상대 확인 요청"}`,
          (draft) => {
            draft.repairs.unshift({
              id,
              roomId,
              title: b.title,
              zone: b.zone,
              description: b.description,
              photos: b.photos,
              art: "wall",
              ack: "확인 대기",
              recurrenceOf: b.recurrenceOf || undefined,
              author: role,
              status: "확인 전",
              date: new Date().toLocaleDateString("ko-KR"),
            })
            if (room.connection === "초대됨") logRepairNotice(draft.repairs[0])
          },
          { roomId, target: { kind: "repair", id } },
        )
        return view(s, id)
      }),
    // 사진 추가(덧붙이기, 03 G): afterRecord가 있으면 수리 후 기록, 없으면 처음 기록한 쪽의 사진 추가
    addPhotos: (s, id, b) =>
      later(() => {
        const recordId = uid()
        return command(
          s,
          id,
          "photos",
          (r) =>
            b.afterRecord
              ? `수리 후 사진 ${b.photos.length}장 · ${b.afterRecord.description}`
              : `사진 추가 · ${b.photos.length}장${r.ack === "확인됨" || (r.status === "처리 완료" && !r.soloClosed) ? " · 확인 뒤 추가" : ""}`,
          (r, role) => {
            if (!b.afterRecord) return addRepairPhotos(r, role, b.photos)
            if (!b.photos.length)
              throw new Error("수리 후 사진을 1장 이상 첨부해 주세요.")
            const after = b.afterRecord
            r.afterPhotos = [...(r.afterPhotos || []), ...b.photos]
            r.afterRecords = [
              ...(r.afterRecords || []),
              {
                id: recordId,
                title: after.title || "수리 후 사진",
                by: role,
                at: new Date().toLocaleString("ko-KR"),
                photos: [...b.photos],
                description: after.description,
              },
            ]
            r.notes = [
              ...(r.notes || []),
              { text: after.description, by: role, kind: "수리 후 기록" },
            ]
          },
        )
      }),
    // 원인 설명(REP-8): 근거 종류 필수(BASIS_REQUIRED), 고치면 새 설명
    addCauseNote: (s, id, b) =>
      later(() => {
        // 복사본 검사와 실제 기록이 같은 설명 번호를 쓰게 미리 정해요
        const causeId = uid()
        return command(
          s,
          id,
          "cause-notes",
          (r, role) =>
            `원인 설명${(r.causes || []).some((c) => c.by === role && !c.supersededBy) ? " · 새 설명" : ""} · 근거 ${b.basis}`,
          (r, role) => addCauseNote(r, role, b, causeId),
        )
      }),
    respondCauseNote: (s, id, causeId, b) =>
      later(() =>
        command(
          s,
          id,
          "cause-notes",
          (r) => {
            const c = r.causes?.find((v) => v.id === causeId)
            return `원인 설명 ${b.answer === "ACK" ? "동의" : `이견 · ${b.reason ?? ""}`} · ${c?.by ?? ""}의 설명`
          },
          (r, role) =>
            respondCauseNote(
              r,
              role,
              causeId,
              b.answer === "ACK",
              b.reason ?? "",
            ),
        ),
      ),
  }
}
