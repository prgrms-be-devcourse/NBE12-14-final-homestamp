import {
  inspectionIdIn,
  notInSpec,
  photoIdOf,
  post,
  postWithPhotos,
  type RecordRequest,
} from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { useApi } from "@/api/hooks"
import { useState, type ReactNode } from "react"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Status } from "@/components/common/blocks"
import { WaitStatus } from "@/components/common/wait-status"
import { ActionDialog } from "@/components/common/action-dialog"
import {
  CorrectionHistory,
  CorrectionPanel,
  CorrectionRow,
} from "@/components/common/correction-box"
import {
  type DemoState,
  type FactCorrection,
  type Inspection,
  type Role,
  type Room,
} from "@/data/demo"
import { readPhotos } from "@/lib/photos"
import { pendingValue } from "@/lib/corrections"
import {
  acceptAllItem,
  addedItemAnswerText,
  addItemPhotos,
  answer,
  confirmAll,
  confirmableItems,
  confirmReach,
  isItemStamped,
  requestAdd,
  requestItemEdits,
  resubmit,
  toggleItemWrongPhoto,
  withdraw,
} from "@/lib/inspection-rules"
import { sideText } from "@/lib/words"
import {
  hasAction,
  type CorrectionAction,
  type InspectionItemAction,
} from "@/api/actions"
import {
  RespondButtons,
  type RespondAnswer,
} from "@/components/common/respond-buttons"
import { Fold } from "@/components/common/fold"
import { MaskedPhotoNotes, PhotoManageList } from "@/features/photo/photo-masks"
import { maskOf, maskPhoto, objectToMask } from "@/lib/photo-privacy"
import {
  Camera,
  History,
  Image as ImageIcon,
  Link2,
  Pencil,
} from "lucide-react"
import { MoreMenu } from "@/components/common/more-menu"
import { PartField } from "@/components/common/part-field"
import { ZONE_PART_ERROR } from "@/lib/record-commands"
import { partLabel, splitPartLabel, type RoomPart } from "@/lib/room-parts"

export type OnInspectionAct = (
  request: RecordRequest,
  text: string,
  update: (d: DemoState) => void,
  targetId: string,
  // 저장 결과(성공 true). 기다릴 창만 써요(App.tsx commit)
) => void | Promise<boolean>
// 빠뜨린 하자 더하기가 실패했을 때 창에 남기는 한 줄(이유는 앱 안내에 따로 떠요). 쓴 설명과 고른 사진은 그대로 둬요
export const MISSING_DEFECT_FAILED =
  "하자를 더하지 못했어요. 쓴 설명과 사진은 그대로 있으니 다시 시도해 주세요."
// 규칙 위반은 복사본에서 먼저 확인합니다.
function check(db: DemoState, update: (d: DemoState) => void) {
  try {
    update(structuredClone(db))
    return ""
  } catch (e) {
    return e instanceof Error ? e.message : "처리할 수 없어요."
  }
}

// 점검 항목 카드 안의 고치기(도장 전이면 바로, 도장 뒤에는 상대 확인)와 사진 추가, 빠진 하자 추가 응답
export function InspectionItemCorrections({
  i,
  db,
  role,
  readOnly,
  guest,
  onAct,
  history,
  onShare,
  actions,
  correctionActionsOf,
}: {
  i: Inspection
  db: DemoState
  role: Role
  readOnly: boolean
  guest?: boolean
  onAct: OnInspectionAct
  history?: ReactNode
  onShare?: () => void
  // 이 항목에서 서버가 준 지금 누를 수 있는 행동(availableActions)
  actions: readonly InspectionItemAction[]
  // 고친 내용·빠뜨린 하자마다 서버가 준 지금 누를 수 있는 행동
  correctionActionsOf: (
    c: FactCorrection<string | number>,
    list: readonly FactCorrection<string | number>[],
  ) => readonly CorrectionAction[]
}) {
  const [mode, setMode] = useState<"edit" | "photos" | null>(null)
  // 고치기 창의 부위: 기다리는 부위 고치기가 있으면 그 부위, 없으면 지금 부위(roomPartId, 06 4-7)
  // pick은 부위 칸이 넘긴 부위예요. 처음 부위 start는 부위 칸이 고를 수 있는지 본 뒤에 넘겨요(#402)
  const [start, setStart] = useState({ id: "", label: "" })
  const [pick, setPick] = useState<RoomPart>()
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  const run = (
    request: RecordRequest,
    text: string,
    update: (d: DemoState) => void,
  ) => {
    const e = check(db, update)
    if (e) {
      setError(e)
      return e
    }
    setError("")
    onAct(request, text, update, i.id)
  }
  // 고친 내용·빠뜨린 하자에 대한 응답: 맞아요 / 더 필요해요(보류) / 사실과 달라요(이견)
  const respond = (
    cid: string,
    field: string,
    a: RespondAnswer,
    reason: string,
  ) =>
    a === "ACK"
      ? run(
          post(endpoints.correctionAction(cid, "respond"), {
            answer: "ACK",
            seenVersionNo: i.version ?? 1,
          }),
          `${i.title} · ${field} 확인 · 본 모습 ${i.version ?? 1}번`,
          (d) => answer(d, role, i.id, cid, true),
        )
      : run(
          post(endpoints.correctionAction(cid, "respond"), {
            answer: a,
            reason,
          }),
          `${i.title} · ${field} ${a === "NEED_MORE" ? "보충 요청" : "이견"} · ${reason}`,
          (d) =>
            answer(
              d,
              role,
              i.id,
              cid,
              false,
              reason,
              a === "NEED_MORE" ? "보류" : "이견",
            ),
        )
  const canEdit = hasAction(actions, "corrections")
  const canAddPhotos = hasAction(actions, "photos")
  const draft = i.publication === "draft"
  // 바로 고쳐지는 때: 작성 중이거나, 상대가 이 항목에 아직 답하지 않았고 점검도 잠기기 전(상태와 규칙 G·H)
  const direct = draft || !isItemStamped(db, i)
  const add = i.addRequest
  // 경과(최신순): 사진 추가 내역 · 끝난 고치기 · 이견. 본문에는 지금 기록과 확인 대기 요약만 남겨요.
  const photoAdds = [...(i.photoAdds || [])].reverse()
  const historyCount =
    photoAdds.length +
    (i.corrections || []).filter((c) => c.status !== "확인 대기").length +
    (i.edits?.length || 0)
  return (
    <div className="rec-extra space-y-2">
      {(add ||
        i.removed ||
        i.wait ||
        i.photoAdds?.some((p) => p.afterConfirm)) && (
        <div className="badges">
          {add && (
            <Badge variant="secondary">빠진 하자로 추가 요청한 항목</Badge>
          )}
          {i.removed && <Status>고치기로 삭제됨 · 원래 기록 보존</Status>}
          {i.photoAdds?.some((p) => p.afterConfirm) && (
            <Status>확인 뒤 사진 추가</Status>
          )}
          <WaitStatus stage={i.wait} long remindPrefix="확인 요청" />
        </div>
      )}
      {add && add.status === "확인 대기" && (
        <CorrectionRow c={add} stage={add.wait}>
          {hasAction(correctionActionsOf(add, [add]), "respond") && (
            <div className="mt-2">
              <RespondButtons
                size="sm"
                onRespond={(a, reason) =>
                  respond(add.id, "빠뜨린 하자", a, reason)
                }
              />
            </div>
          )}
          {hasAction(correctionActionsOf(add, [add]), "withdraw") && (
            <div className="actions mt-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  run(
                    post(endpoints.correctionAction(add.id, "withdraw")),
                    `${i.title} · 빠뜨린 하자 더하기 거둠`,
                    (d) => withdraw(d, role, i.id, add.id),
                  )
                }
              >
                거두기
              </Button>
            </div>
          )}
        </CorrectionRow>
      )}
      <CorrectionPanel
        list={i.corrections || []}
        stageOf={(c) => c.wait}
        role={role}
        guest={guest}
        readOnly={readOnly}
        actionsOf={(c) => correctionActionsOf(c, i.corrections || [])}
        onRespond={(c, a, reason) => respond(c.id, c.field, a, reason)}
        onResubmit={(c, data) =>
          run(
            post(endpoints.correctionAction(c.id, "resubmit"), data),
            `${i.title} · 고친 내용 사진 더해서 다시 요청(${c.field})${data.text ? ` · ${data.text}` : ""}${data.photos.length ? ` · 사진 ${data.photos.length}장` : ""}`,
            (d) => resubmit(d, role, i.id, c.id, data),
          )
        }
        onWithdraw={(c) =>
          run(
            post(endpoints.correctionAction(c.id, "withdraw")),
            `${i.title} · 고친 내용 거둠(${c.field})`,
            (d) => withdraw(d, role, i.id, c.id),
          )
        }
        onAcceptAll={() =>
          run(
            post(endpoints.acceptAllCorrections, {
              target: { kind: "inspection-item", id: i.id },
              seenVersionNo: i.version ?? 1,
            }),
            `${i.title} · 고친 내용 모두 확인`,
            (d) => acceptAllItem(d, role, i.id),
          )
        }
      />
      {/* 보조 행동(고치기 · 사진 추가 · 공유 링크 복사)은 카드 오른쪽 위 ⋯ 메뉴로. 노출 조건은 그대로 */}
      <MoreMenu
        className="absolute top-2 right-2"
        items={[
          ...(canEdit
            ? [
                {
                  key: "edit",
                  label: "고치기",
                  icon: Pencil,
                  onSelect: () => {
                    const pending = i.corrections?.find(
                      (c) => c.status === "확인 대기" && c.field === "부위",
                    )
                    const id = pending
                      ? (pending.toPartId ?? "")
                      : (i.roomPartId ?? "")
                    setStart({
                      id,
                      label: String(
                        pendingValue(i.corrections, "부위") ?? i.zone,
                      ),
                    })
                    setPick(undefined)
                    setError("")
                    setMode("edit")
                  },
                },
              ]
            : []),
          ...(canAddPhotos
            ? [
                {
                  key: "photos",
                  label: "사진 추가",
                  icon: Camera,
                  onSelect: () => {
                    setPhotos([])
                    setError("")
                    setMode("photos")
                  },
                },
              ]
            : []),
          ...(onShare
            ? [
                {
                  key: "share",
                  label: "공유 링크 복사",
                  icon: Link2,
                  onSelect: onShare,
                },
              ]
            : []),
        ]}
      />
      {(historyCount > 0 || history) && (
        <Fold
          title="경과"
          count={historyCount || undefined}
          icon={<History size={16} />}
        >
          <div className="max-h-80 space-y-2 overflow-y-auto overscroll-contain pr-1.5 [scrollbar-gutter:stable]">
            {photoAdds.map((p, n) => (
              <div className="history-item" key={"p" + n}>
                <div className="history-head">
                  <strong>사진 추가 · {p.count}장</strong>
                  {p.afterConfirm && <Status>확인 뒤 추가</Status>}
                </div>
                <small className="muted">
                  {sideText(p.by)} · {p.at}
                </small>
              </div>
            ))}
            {history}
            <CorrectionHistory
              list={i.corrections || []}
              edits={i.edits}
              stageOf={(c) => c.wait}
              guest={guest}
              inline
            />
          </div>
        </Fold>
      )}
      <MaskedPhotoNotes
        role={role}
        readOnly={readOnly || guest}
        items={(i.privacyMasks || []).map((m) => ({
          mask: m,
          src: i.photos[Number(m.key)],
          label: `사진 ${Number(m.key) + 1}`,
        }))}
        onObject={(key, reason) =>
          run(
            notInSpec("가린 사진에 이견 달기"),
            `${i.title} · 가린 사진에 이견 · 사진 ${Number(key) + 1}`,
            (d) =>
              objectToMask(
                d.inspections.find((v) => v.id === i.id)!,
                key,
                role,
                reason,
              ),
          )
        }
      />
      {!readOnly && !guest && i.author === role && i.photos.length > 0 && (
        <Fold title="사진 관리" icon={<ImageIcon size={16} />}>
          <PhotoManageList
            items={i.photos.map((src, n) => ({
              key: String(n),
              src,
              label: `사진 ${n + 1}`,
              wrong: Boolean(i.wrongPhotos?.includes(n)),
              canWrong: true,
              masked: Boolean(maskOf(i, String(n))),
            }))}
            onWrong={(keys, mark) =>
              run(
                keys.map((k) =>
                  post(
                    endpoints.photoAction(
                      photoIdOf(i.id, k),
                      mark ? "mark-wrong" : "unmark-wrong",
                    ),
                  ),
                ),
                `${i.title} · ${mark ? "잘못 올린 사진 표시" : "잘못 올린 사진 표시 떼기"} · ${keys.map((k) => `사진 ${Number(k) + 1}`).join(", ")}`,
                (d) =>
                  keys.forEach((k) =>
                    toggleItemWrongPhoto(d, role, i.id, Number(k)),
                  ),
              )
            }
            onMask={(keys) =>
              run(
                notInSpec(
                  "개인정보 사진 가림(06은 지우기·가린 사본 올리기 PHO-5·7)",
                ),
                `${i.title} · 개인정보 이유로 사진 가림 · ${keys.map((k) => `사진 ${Number(k) + 1}`).join(", ")}`,
                (d) => {
                  const x = d.inspections.find((v) => v.id === i.id)!
                  keys.forEach((k) => maskPhoto(x, k, role, x.author))
                },
              )
            }
          />
        </Fold>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={mode === "edit"}
        title="고치기"
        description="바꿀 칸만 고쳐 주세요. 사진은 [사진 추가]로 더해요."
        submitLabel={direct ? "고치기" : "확인 요청"}
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          if (!pick) return ZONE_PART_ERROR
          const next = {
            title: String(f.get("title") || ""),
            description: String(f.get("text") || ""),
            zone: partLabel(pick),
            roomPartId: pick.id,
          }
          return run(
            post(endpoints.corrections, {
              target: { kind: "inspection-item", id: i.id },
              fields: next,
              reason: String(f.get("reason") || ""),
            }),
            draft
              ? `${i.title} · 작성 중 고치기`
              : direct
                ? `${i.title} · 고쳤어요`
                : `${i.title} · 고친 내용 남김 · 상대 확인 전`,
            (d) => {
              requestItemEdits(
                d,
                role,
                i.id,
                next,
                String(f.get("reason") || ""),
              )
            },
          )
        }}
      >
        <div className="field">
          <label htmlFor="ie-title">제목 · 필수</label>
          <Input
            id="ie-title"
            name="title"
            maxLength={80}
            defaultValue={String(
              pendingValue(i.corrections, "제목") ?? i.title,
            )}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="ie-text">설명 · 필수</label>
          <Textarea
            id="ie-text"
            name="text"
            defaultValue={String(
              pendingValue(i.corrections, "설명") ?? i.description,
            )}
            required
          />
        </div>
        <PartField
          key={start.id + start.label}
          roomId={i.roomId}
          value={pick?.id ?? ""}
          onChange={setPick}
          defaultPart={{
            ...splitPartLabel(start.label),
            id: start.id || undefined,
          }}
        />
        {!draft && (
          <div className="field">
            <label htmlFor="ie-reason">
              왜 바꾸나요{direct ? " · 선택" : " · 필수"}
            </label>
            <Textarea
              id="ie-reason"
              name="reason"
              required={!direct}
              placeholder="예: 다시 보니 부위가 달랐어요"
            />
          </div>
        )}
        {!direct && i.corrections?.some((c) => c.status === "확인 대기") && (
          <p className="fix-note">
            확인을 기다리는 고친 내용이 채워져 있어요. 다시 고친 칸은 이전 고친
            내용이 ‘새로 고쳐서 바뀜’으로 닫히고, 확인 요청은 하나로 묶여요.
          </p>
        )}
        <p className="fix-notice">
          {draft
            ? "작성 중(확인 요청 전)이라 바로 고쳐져요."
            : direct
              ? "상대가 이 항목을 아직 확인하기 전이라 바로 고쳐져요. 고친 이력은 남아요."
              : "고친 내용을 남겨요. 상대가 확인하면 바뀌어요."}
        </p>
      </ActionDialog>
      <ActionDialog
        open={mode === "photos"}
        title="사진 추가"
        description="사진은 바꾸지 않고 더하기만 해요. 기존 사진은 그대로 남고, 같이 확인한 뒤라면 ‘확인 뒤 추가’로 표시돼요."
        submitLabel="사진 추가"
        onClose={() => setMode(null)}
        onSubmit={() =>
          run(
            notInSpec("점검 항목에 사진 더하기"),
            `${i.title} · 사진 추가 · ${photos.length}장`,
            (d) => addItemPhotos(d, role, i.id, photos),
          )
        }
      >
        <div className="field">
          <label htmlFor="ia-photos">
            사진 · 계약서·신분증·통장 사진은 올리지 마세요
          </label>
          <Input
            id="ia-photos"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={(e) =>
              readPhotos(e.target.files, photos.length)
                .then((p) => setPhotos((o) => [...o, ...p]))
                .catch((err) => setError(err.message))
            }
          />
          {photos.length > 0 && (
            <small className="muted">{photos.length}장 선택됨</small>
          )}
        </div>
      </ActionDialog>
    </div>
  )
}

// 빠진 하자 추가(사실 정정). 잠긴 점검의 '정정 요청하기'에서 엽니다.
export function MissingDefectDialog({
  open,
  kind,
  db,
  room,
  role,
  onClose,
  onAct,
}: {
  open: boolean
  kind: "in" | "out"
  db: DemoState
  room: Room
  role: Role
  onClose: () => void
  onAct: OnInspectionAct
}) {
  const [part, setPart] = useState<RoomPart>()
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  // HTTP는 서버가 받는 칸(부위 + 설명 + 사진, 06 4-7)만 보여요. 제목·추가 이유 칸이 없고, 규칙은 서버가 정해요(목업 규칙 확인은 하지 않아요)
  const http = useApi().mode === "http"
  return (
    <ActionDialog
      open={open}
      title="빠진 하자 추가"
      description={
        http
          ? // 서버: 더하면 바로 항목이 되고, 받을 상대가 있을 때만 상대(더하지 않은 쪽)에게 확인 요청이 가요. 점검 상태·잠금은 그대로예요(06 4-7 INSP-1d·1e)
            `빠뜨린 하자를 새 항목으로 더해요. ${addedItemAnswerText(confirmReach(room))} 점검이 잠긴 뒤에 더해도 잠금은 그대로예요.`
          : "점검을 고치는 일이라 기준 기록을 쓴 쪽이 확인해야 반영돼요. 빠진 하자 추가도 상대 쪽이 확인해야 항목이 돼요(응답이 없어도 혼자 반영되지 않아요). 기존 항목의 고치기·사진 추가는 각 항목 카드에서 할 수 있어요."
      }
      submitLabel={http ? "하자 더하기" : "추가 요청"}
      onClose={() => {
        setPhotos([])
        setError("")
        onClose()
      }}
      onSubmit={async (f) => {
        if (!part) return ZONE_PART_ERROR
        const data = {
          roomPartId: part.id,
          zone: partLabel(part),
          title: String(f.get("title") || ""),
          description: String(f.get("description") || ""),
          photos: [...photos],
        }
        const reason = String(f.get("reason") || "")
        const update = (d: DemoState) => {
          requestAdd(
            d,
            d.rooms.find((r) => r.id === room.id)!,
            role,
            kind,
            data,
            reason,
          )
        }
        const e = http ? "" : check(db, update)
        if (e) return e
        // 사진 올리기와 항목 저장을 기다려요(ActionDialog가 그동안 버튼을 막아요). 성공이면 창이 닫히며 onClose가 사진을 비우고,
        // 실패면 문구를 돌려줘 창·설명·사진을 그대로 둬요(#405 리뷰)
        const saved = await onAct(
          postWithPhotos(
            endpoints.inspectionAction(inspectionIdIn(room, kind), "items"),
            { roomPartId: data.roomPartId, description: data.description },
            data.photos,
          ),
          `${kind === "in" ? "입주" : "퇴거"} 점검 · 빠뜨린 하자 더하기 · ${data.title || data.zone}`,
          update,
          "",
        )
        if (saved === false) return MISSING_DEFECT_FAILED
      }}
    >
      <PartField
        roomId={room.id}
        value={part?.id ?? ""}
        onChange={setPart}
        defaultPart={{ zone: "욕실", name: "천장" }}
      />
      {!http && (
        <div className="field">
          <label htmlFor="im-title">하자 제목</label>
          <Input id="im-title" name="title" maxLength={80} required />
        </div>
      )}
      <div className="field">
        <label htmlFor="im-desc">상태 설명</label>
        <Textarea id="im-desc" name="description" maxLength={2000} required />
      </div>
      <div className="field">
        <label htmlFor="im-photos">사진 · 선택</label>
        <Input
          id="im-photos"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(e) =>
            readPhotos(e.target.files, photos.length)
              .then((p) => setPhotos((o) => [...o, ...p]))
              .catch((err) => setError(err.message))
          }
        />
        {photos.length > 0 && (
          <small className="muted">{photos.length}장 선택됨</small>
        )}
      </div>
      {!http && (
        <div className="field">
          <label htmlFor="im-reason">추가하는 이유 · 필수</label>
          <Textarea
            id="im-reason"
            name="reason"
            required
            placeholder="예: 입주 점검 때 빠뜨린 하자예요"
          />
        </div>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
    </ActionDialog>
  )
}

// 남은 항목 모두 확인(확인 창 → 항목마다 확인 행동)
export function ConfirmAllItems({
  room,
  items,
  db,
  role,
  onAct,
}: {
  room: Room
  items: Inspection[]
  db: DemoState
  role: Role
  onAct: OnInspectionAct
}) {
  const [open, setOpen] = useState(false)
  const ids = confirmableItems(items, role).map((i) => i.id)
  if (ids.length < 2) return null
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        남은 항목 모두 확인
      </Button>
      <ActionDialog
        open={open}
        title={`${ids.length}개 항목을 확인할까요?`}
        description="확인은 항목마다 ‘확인’ 행동으로 저장돼요. 이견이 있는 항목은 이 창을 닫고 하나씩 이견을 남겨 주세요."
        submitLabel={`${ids.length}개 항목 확인`}
        onClose={() => setOpen(false)}
        onSubmit={() => {
          const seen = Object.fromEntries(
            items.map((i) => [i.id, i.version ?? 1]),
          )
          const update = (d: DemoState) => confirmAll(d, role, ids, seen)
          const e = check(db, update)
          if (e) return e
          onAct(
            post(
              endpoints.inspectionAction(
                inspectionIdIn(room, items[0].kind),
                "confirm-all",
              ),
              // 06 4-7: 화면이 본 미응답 항목과 판 번호(지금 서버의 미응답 항목과 다르면 VERSION_CONFLICT)
              {
                items: ids.map((id) => ({ id, seenVersionNo: seen[id] ?? 1 })),
              },
            ),
            `남은 점검 항목 모두 확인 · ${ids.length}개`,
            update,
            "",
          )
        }}
      >
        <p className="muted">
          {confirmableItems(items, role)
            .map((i) => i.title)
            .join(", ")}
        </p>
      </ActionDialog>
    </>
  )
}
