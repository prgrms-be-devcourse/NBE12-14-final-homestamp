import { useImperativeHandle, useState, type ReactNode, type Ref } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Status } from "@/components/prototype"
import { ActionDialog, tryOn } from "@/components/action-dialog"
import { LongText } from "@/features/inspection/inspection-disputes"
import {
  other,
  partGroups,
  type FactCorrection,
  type PhotoMask,
  type Repair,
  type Role,
  type TextEdit,
} from "@/data/demo"
import { readPhotos } from "@/lib/photos"
import {
  acceptAllCorrections,
  addRepairPhotos,
  answerCorrection,
  isHidden,
  repairStamped,
  reaskWaitDays,
  resubmitRepairCorrection,
  requestRepairEdits,
  togglePhoto,
  toggleWrongPhoto,
  withdrawCorrection,
} from "@/lib/repair-rules"
import { safeSharedText } from "@/lib/service-rules"
import {
  groupCorrections,
  groupStatus,
  pendingRequest,
  pendingValue,
} from "@/lib/corrections"
import { versionOf } from "@/lib/versions"
import { hasAction, type CorrectionAction } from "@/api/actions"
import {
  RespondButtons,
  type RespondAnswer,
} from "@/components/common/respond-buttons"
import { SupplementRequest } from "@/components/common/supplement-request"
import { sideName, sideText } from "@/lib/words"
import { Fold } from "@/components/fold"
import { MaskedPhotoNotes, PhotoManageList } from "@/components/photo-masks"
import {
  maskOf,
  maskPhoto,
  objectToMask,
  uploaderOf,
} from "@/lib/photo-privacy"
import { Image as ImageIcon, PencilLine } from "lucide-react"

type OnAction = (text: string, update: (r: Repair) => void) => void

// 고친 내용의 상태 문구(상태와 규칙 G: 도장 전이면 바로 바뀜, 도장 뒤면 상대 확인이 있어야 바뀜)
export function correctionLabel(
  c: FactCorrection<string | number>,
  stage?: string,
) {
  if (c.status === "확인 대기")
    return stage === "미확인"
      ? "확인 없음 · 72시간 동안 답이 오지 않았어요 · 지금 기록 그대로"
      : stage === "재통지"
        ? "확인 전 · 다시 알렸어요"
        : "상대 확인 전"
  if (c.status === "반영")
    return c.mode === "확인 전"
      ? "확인 전에 고침"
      : c.mode === "상대 없음"
        ? "고쳐짐 · 혼자 기록할 때 고침"
        : c.mode === "상대 미확인"
          ? "고쳐짐 · 확인 없음일 때 고침"
          : "고쳐짐 · 상대 확인"
  if (c.status === "거절")
    return c.answerKind === "보류"
      ? "보충 요청됨 · 지금 기록 그대로"
      : c.answerKind === "이견"
        ? "이견 · 지금 기록 그대로"
        : "동의하지 않음 · 지금 기록 그대로"
  if (c.status === "대체됨") return "새로 고쳐서 바뀜"
  if (c.status === "처리 완료로 닫힘") return "처리 완료로 닫힘"
  return "거둠 · 지금 기록 그대로"
}
// 바뀐 글자만 표시: 앞뒤 같은 부분은 그대로, 가운데 바뀐 부분만 지운 글자(취소선)·새 글자(강조)로 보여 줘요.
export function DiffText({ from, to }: { from: string; to: string }) {
  let start = 0
  while (start < from.length && start < to.length && from[start] === to[start])
    start++
  let end = 0
  while (
    end < from.length - start &&
    end < to.length - start &&
    from[from.length - 1 - end] === to[to.length - 1 - end]
  )
    end++
  const removed = from.slice(start, from.length - end),
    added = to.slice(start, to.length - end)
  return (
    <span className="whitespace-pre-wrap [overflow-wrap:anywhere]">
      {from.slice(0, start)}
      {removed && <del className="text-destructive">{removed}</del>}
      {added && (
        <ins className="font-semibold no-underline text-primary">{added}</ins>
      )}
      {from.slice(from.length - end)}
    </span>
  )
}
// 지금 기록과 고친 내용을 나란히 보여 줍니다(상태와 규칙 G). 지불 금액·빠진 하자 추가·항목 삭제에서도 씁니다.
// compact: 고치기 요청 묶음 안에서 칸 이름과 바뀐 값만 보여 줘요(작성자·시각·이유는 묶음 머리에).
export function CorrectionRow({
  c,
  stage,
  format = String,
  safe = (t: string) => t,
  compact,
  showReason,
  children,
}: {
  c: FactCorrection<string | number>
  stage?: string
  format?: (v: string | number) => string
  safe?: (t: string) => string
  compact?: boolean
  showReason?: boolean
  children?: ReactNode
}) {
  const text =
    typeof c.from === "string" && typeof c.to === "string" && format === String
  return (
    <div
      className={
        compact
          ? "fix-row"
          : c.status === "확인 대기"
            ? "dispute-callout mt-2"
            : "note"
      }
    >
      {compact ? (
        <div className="fix-row-head">
          <strong>{c.field}</strong>
          <Status>{correctionLabel(c, stage)}</Status>
        </div>
      ) : (
        <>
          <strong>
            {c.field} 고친 내용 · {sideName(c.by)} · {c.at}
          </strong>
          <div className="badges my-2">
            <Status>{correctionLabel(c, stage)}</Status>
          </div>
        </>
      )}
      <p className="fix-diff">
        {text ? (
          <DiffText from={safe(String(c.from))} to={safe(String(c.to))} />
        ) : (
          <>
            {format(c.from)} → {format(c.to)}
          </>
        )}
      </p>
      {(!compact || showReason) && c.reason && (
        <LongText text={`고친 이유: ${safe(c.reason)}`} more="펼치기" />
      )}
      {c.decidedAt && c.status !== "대체됨" && c.status !== "확인 대기" && (
        <small className="muted block">
          {c.decidedBy
            ? `${sideText(c.decidedBy)} ${c.status === "반영" ? "확인" : c.answerKind === "보류" ? "보충 요청" : c.answerKind === "이견" ? "이견" : "동의하지 않음"}`
            : c.status === "철회"
              ? "고친 쪽이 거둠"
              : "처리"}{" "}
          · {c.decidedAt}
        </small>
      )}
      {c.rejectReason && (
        <div className="dispute-callout">
          {c.answerKind === "보류"
            ? "더 필요한 게 있어요"
            : c.answerKind === "이견"
              ? "사실과 달라요"
              : "동의하지 않은 이유"}
          : {safe(c.rejectReason)}
        </div>
      )}
      {c.answers?.length ? (
        <small className="muted block">
          이전 응답 {c.answers.length}건 · 근거를 더해 다시 요청했어요
          {c.evidencePhotos?.length
            ? ` · 근거 사진 ${c.evidencePhotos.length}장`
            : ""}
        </small>
      ) : null}
      {c.priorDispute && (
        <small className="muted block">
          반영 전 내용에 달려 있던 이견(이력): {safe(c.priorDispute)}
        </small>
      )}
      {children}
    </div>
  )
}

/* ── 이전 방식(바로 반영) 글 수정 이력: 기존 기록 보존용 ── */
export function TextEditRow({
  e,
  safe = (t: string) => t,
}: {
  e: TextEdit
  safe?: (t: string) => string
}) {
  const titleChanged =
    e.titleBefore !== undefined && e.titleBefore !== e.titleAfter
  const bodyChanged = e.before !== e.after
  return (
    <div className="note">
      <strong>
        고친 이력 · {sideName(e.by)} · {e.at}
      </strong>
      {titleChanged && (
        <p>
          제목:{" "}
          <DiffText from={safe(e.titleBefore!)} to={safe(e.titleAfter!)} />
        </p>
      )}
      {bodyChanged && (
        <p>
          설명: <DiffText from={safe(e.before)} to={safe(e.after)} />
        </p>
      )}
      {e.photosAdded ? (
        <p className="muted">이전 방식 기록 · 사진 {e.photosAdded}장 추가</p>
      ) : null}
    </div>
  )
}

/* ── 확인을 기다리는 고치기 요청(수리 건·점검 항목 공통) ──
   본문에는 요약 한 줄만, 칸별 내용과 확인·거절은 펼쳐서. 끝난 고치기는 경과(CorrectionHistory)로 보내요. */
type Fix = FactCorrection<string | number>
export function CorrectionPanel({
  list,
  stageOf,
  role,
  guest,
  actionsOf,
  onRespond,
  onResubmit,
  onWithdraw,
  onAcceptAll,
}: {
  list: Fix[]
  edits?: TextEdit[]
  stageOf: (c: Fix) => string | undefined
  role: Role
  guest?: boolean
  readOnly?: boolean
  // 고친 내용마다 서버가 준 지금 누를 수 있는 행동(availableActions)
  actionsOf: (c: Fix) => readonly CorrectionAction[]
  onRespond: (c: Fix, answer: RespondAnswer, reason: string) => string | void
  onResubmit: (
    c: Fix,
    data: { text: string; photos: string[] },
  ) => string | void
  onWithdraw: (c: Fix) => void
  onAcceptAll?: () => void
}) {
  const safe = (t: string) => (guest ? safeSharedText(t) : t)
  const has = (c: Fix, a: CorrectionAction) => hasAction(actionsOf(c), a)
  // 기록당 확인 요청 하나(REP-7h): 기다리는 칸을 모두 한 요청으로
  const one = pendingRequest(list)
  const pending = one ? [one] : []
  if (!pending.length) {
    // 상대가 동의하지 않은 고친 내용: 고친 쪽은 [사진 더해서 다시 요청] / (이대로 둘게요)(08 3장 장면 4)
    const declined = list.filter((c) => has(c, "resubmit"))
    if (declined.length)
      return (
        <div className="dispute-callout space-y-2">
          <strong>상대가 고친 내용에 동의하지 않았어요</strong>
          {declined.map((c) => (
            <CorrectionRow key={c.id} c={c} safe={safe} compact showReason>
              <SupplementRequest
                label="사진 더해서 다시 요청"
                waitDays={reaskWaitDays(c.reaskedAt)}
                onSupplement={(data) => onResubmit(c, data)}
              />
            </CorrectionRow>
          ))}
        </div>
      )
    // 가장 최근 요청이 동의받지 못했거나 거둠으로 끝났으면 한 줄로 알려요(자세한 내용은 경과).
    const last = groupCorrections(list.filter((c) => c.status !== "대체됨")).at(
      -1,
    )
    const st = last && groupStatus(last)
    return st && /동의하지 않음|보충 요청됨|이견|거둠/.test(st) ? (
      <p className="fix-note">
        최근 고치기 요청 · <strong>{st}</strong>
      </p>
    ) : null
  }
  return (
    <div className="space-y-2">
      {pending.map((g) => {
        const answerable = g.items.filter((c) => has(c, "respond"))
        return (
          <Fold
            key={g.key}
            tone="amber"
            icon={<PencilLine size={16} />}
            title="고치기 요청"
            sub={`${g.items.map((c) => c.field).join(" · ")} 변경`}
            meta={
              <span className="inline-flex items-center gap-1 rounded-full bg-[#fff6e4] px-[9px] py-[3px] text-[13px] leading-[1.6] font-[650] whitespace-nowrap text-[#bc9146] max-sm:px-[7px] max-sm:py-0.5 max-sm:text-xs">
                {answerable.length ? "내 확인 필요" : groupStatus(g)}
              </span>
            }
          >
            <div className="flex flex-col gap-2 text-sm">
              {g.reason && (
                <LongText text={`고친 이유: ${safe(g.reason)}`} more="펼치기" />
              )}
              {g.items.map((c) => (
                <CorrectionRow
                  key={c.id}
                  c={c}
                  stage={stageOf(c)}
                  safe={safe}
                  compact
                  showReason={!g.reason}
                >
                  {has(c, "respond") && (
                    <div className="mt-2">
                      <RespondButtons
                        size="sm"
                        onRespond={(answer, reason) =>
                          onRespond(c, answer, reason)
                        }
                      />
                    </div>
                  )}
                  {has(c, "withdraw") && (
                    <div className="actions mt-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onWithdraw(c)}
                      >
                        이 항목 거두기
                      </Button>
                    </div>
                  )}
                </CorrectionRow>
              ))}
              {onAcceptAll && answerable.length > 1 && (
                <Button size="sm" onClick={onAcceptAll}>
                  변경 항목 모두 확인 ({answerable.length})
                </Button>
              )}
              <p className="muted text-sm">
                {g.by === role
                  ? "상대가 확인하면 바뀌어요."
                  : "확인한 칸만 바뀌어요."}
              </p>
            </div>
          </Fold>
        )
      })}
    </div>
  )
}

// 경과에 넣는 고친 이력: 끝난 고치기 요청(반영·거절·거둠·대체)과 이전 방식 글 수정. 최신순.
export function CorrectionHistory({
  list,
  edits,
  stageOf,
  guest,
  inline,
}: {
  list: FactCorrection<string | number>[]
  edits?: TextEdit[]
  stageOf: (c: FactCorrection<string | number>) => string | undefined
  guest?: boolean
  inline?: boolean
}) {
  const safe = (t: string) => (guest ? safeSharedText(t) : t)
  const done = groupCorrections(
    list.filter((c) => c.status !== "확인 대기"),
  ).reverse()
  if (!done.length && !edits?.length) return null
  const body = (
    <div className="space-y-2">
      {done.map((g) => (
        <div className="history-item" key={g.key}>
          <div className="history-head">
            <strong>고치기 · {g.items.map((c) => c.field).join(" · ")}</strong>
            <Status>{groupStatus(g)}</Status>
          </div>
          <small className="muted">
            {sideName(g.by)} · {g.at}
          </small>
          {g.reason && <p className="text-sm">고친 이유: {safe(g.reason)}</p>}
          {g.items.map((c) => (
            <CorrectionRow
              key={c.id}
              c={c}
              stage={stageOf(c)}
              safe={safe}
              compact
            />
          ))}
        </div>
      ))}
      {[...(edits || [])].reverse().map((e, n) => (
        <TextEditRow key={"e" + n} e={e} safe={safe} />
      ))}
    </div>
  )
  return inline ? (
    body
  ) : (
    <Fold
      title="고친 이력"
      count={done.length + (edits?.length || 0)}
      icon={<PencilLine size={16} />}
    >
      {body}
    </Fold>
  )
}

// 수리 건의 고치기(제목·설명·부위)와 사진 추가 창. 버튼은 상세 화면의 행동 영역에 하나씩만 두고 여기서는 창과 확인 대기 요약만 그려요.
export interface RepairCorrectionsHandle {
  openEdit: () => void
  openPhotos: () => void
}
export function RepairCorrections({
  r,
  role,
  readOnly,
  guest,
  onAction,
  correctionActionsOf,
  ref,
}: {
  r: Repair
  room?: unknown
  role: Role
  readOnly: boolean
  guest?: boolean
  onAction: OnAction
  // 고친 내용마다 서버가 준 지금 누를 수 있는 행동(availableActions)
  correctionActionsOf: (
    c: FactCorrection<string | number>,
    list: readonly FactCorrection<string | number>[],
  ) => readonly CorrectionAction[]
  ref?: Ref<RepairCorrectionsHandle>
}) {
  const [mode, setMode] = useState<"edit" | "photos" | null>(null)
  const [zone, setZone] = useState("욕실")
  const [part, setPart] = useState("천장")
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  const run = (text: string, update: (x: Repair) => void) => {
    const e = tryOn(r, update)
    if (e) {
      setError(e)
      return e
    }
    setError("")
    onAction(text, update)
  }
  const list = r.corrections || []
  const openEdit = () => {
    const [z, p] = String(pendingValue(list, "부위") ?? r.zone).split(" · ")
    setZone(partGroups[z] ? z : "기타")
    setPart(p || "기타")
    setError("")
    setMode("edit")
  }
  const openPhotos = () => {
    setPhotos([])
    setError("")
    setMode("photos")
  }
  useImperativeHandle(ref, () => ({ openEdit, openPhotos }))
  const them = other(role)
  // 도장 전이면 바로 고쳐지고, 도장 뒤면 상대 확인을 받아요(상태와 규칙 G).
  const stamped = repairStamped(r)
  const afterConfirm =
    r.ack === "확인됨" || (r.status === "처리 완료" && !r.soloClosed)
  const summary = (
    <CorrectionPanel
      list={list}
      stageOf={(c) => r.waits?.[`fix:${c.id}`]}
      role={role}
      guest={guest}
      readOnly={readOnly}
      actionsOf={(c) => correctionActionsOf(c, list)}
      onRespond={(c, answer, reason) =>
        answer === "ACK"
          ? run(`고친 내용 확인(${c.field}) · 본 모습 ${versionOf(r)}번`, (x) =>
              answerCorrection(x, role, c.id, true),
            )
          : run(
              `고친 내용 ${answer === "NEED_MORE" ? "보충 요청" : "이견"}(${c.field}) · ${reason}`,
              (x) =>
                answerCorrection(
                  x,
                  role,
                  c.id,
                  false,
                  reason,
                  answer === "NEED_MORE" ? "보류" : "이견",
                ),
            )
      }
      onResubmit={(c, data) =>
        run(
          `고친 내용 사진 더해서 다시 요청(${c.field})${data.text ? ` · ${data.text}` : ""}${data.photos.length ? ` · 사진 ${data.photos.length}장` : ""}`,
          (x) => resubmitRepairCorrection(x, role, c.id, data),
        )
      }
      onWithdraw={(c) =>
        run(`고친 내용 거둠(${c.field})`, (x) =>
          withdrawCorrection(x, role, c.id),
        )
      }
      onAcceptAll={() =>
        run("고친 내용 모두 확인", (x) => acceptAllCorrections(x, role))
      }
    />
  )
  return (
    <>
      {summary}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={mode === "edit"}
        title="고치기"
        description="바꿀 칸만 고쳐 주세요. 사진은 [사진 추가]로 더하고, 수리비 부담은 수리비 카드의 새 제안으로 바꿔요."
        submitLabel={stamped ? "확인 요청" : "고치기"}
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const z =
            zone === "기타" ? String(f.get("customZone") || "").trim() : zone
          const p =
            part === "기타" ? String(f.get("customPart") || "").trim() : part
          if (!z || !p) return "구역과 부위를 입력해 주세요."
          const next = {
            title: String(f.get("title") || ""),
            description: String(f.get("description") || ""),
            zone: `${z} · ${p}`,
          }
          return run(
            stamped ? "고친 내용 남김 · 상대 확인 전" : "고쳤어요",
            (x) => {
              requestRepairEdits(x, role, next, String(f.get("reason") || ""))
            },
          )
        }}
      >
        <div className="field">
          <label htmlFor="fix-title">제목 · 필수</label>
          <Input
            id="fix-title"
            name="title"
            maxLength={80}
            defaultValue={String(pendingValue(list, "제목") ?? r.title)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="fix-desc">설명</label>
          <Textarea
            id="fix-desc"
            name="description"
            defaultValue={String(pendingValue(list, "설명") ?? r.description)}
            maxLength={2000}
          />
        </div>
        <div className="form-grid">
          <div className="field">
            <label>구역</label>
            <Choice
              label="구역"
              value={zone}
              values={Object.keys(partGroups)}
              onChange={(v) => {
                setZone(v)
                setPart(partGroups[v][0])
              }}
            />
          </div>
          <div className="field">
            <label>부위</label>
            <Choice
              label="부위"
              value={part}
              values={Array.from(
                new Set([...(partGroups[zone] || []), "기타"]),
              )}
              onChange={setPart}
            />
          </div>
        </div>
        {zone === "기타" && (
          <div className="field">
            <label htmlFor="fix-zone">구역 이름 · 필수</label>
            <Input id="fix-zone" name="customZone" required />
          </div>
        )}
        {part === "기타" && (
          <div className="field">
            <label htmlFor="fix-part">부위 이름 · 필수</label>
            <Input id="fix-part" name="customPart" required />
          </div>
        )}
        <div className="field">
          <label htmlFor="fix-reason">
            왜 바꾸나요{stamped ? " · 필수" : " · 선택"}
          </label>
          <Textarea
            id="fix-reason"
            name="reason"
            required={stamped}
            placeholder="예: 다시 보니 천장이 아니라 벽 위쪽에서 새고 있었어요."
          />
        </div>
        {list.some((c) => c.status === "확인 대기") && (
          <p className="fix-note">
            확인을 기다리는 고친 내용이 채워져 있어요. 다시 고친 칸은 이전 고친
            내용이 ‘새로 고쳐서 바뀜’으로 닫히고, 확인 요청은 하나로 묶여요.
          </p>
        )}
        <p className="fix-notice">
          {stamped
            ? `고친 내용을 남겨요. ${sideText(them)}이 확인하면 바뀌어요.`
            : `${sideText(them)}이 아직 확인하기 전이라 바로 고쳐져요.`}
        </p>
      </ActionDialog>
      <ActionDialog
        open={mode === "photos"}
        title="사진 추가"
        description="사진은 바꾸지 않고 더하기만 해요. 기존 사진과 하자 내용·확인 상태는 그대로예요. 엉뚱한 사진은 지우지 않고 ‘잘못 올린 사진’ 표시를 붙여 주세요."
        submitLabel="사진 추가"
        onClose={() => setMode(null)}
        onSubmit={() =>
          run(
            `사진 추가 · ${photos.length}장${afterConfirm ? " · 확인 뒤 추가" : ""}`,
            (x) => addRepairPhotos(x, role, photos),
          )
        }
      >
        <div className="field">
          <label htmlFor="add-photos">
            사진 · 계약서·신분증·통장 사진은 올리지 마세요
          </label>
          <Input
            id="add-photos"
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
        {afterConfirm && (
          <p className="fix-notice">
            상대가 확인한 뒤라 ‘확인 뒤 추가’로 표시돼요.
          </p>
        )}
      </ActionDialog>
    </>
  )
}

// 잘못 올린 사진(PHO-9): 지우지 않고 흐리게 맨 뒤에 보여요.
export const splitWrong = (photos: string[], wrong?: number[]) => ({
  main: photos.filter((_, n) => !wrong?.includes(n)),
  wrong: photos.filter((_, n) => wrong?.includes(n)),
})
export function WrongPhotos({
  photos,
  masks,
}: {
  photos: string[]
  masks?: (PhotoMask | undefined)[]
}) {
  if (!photos.length) return null
  return (
    <div className="mt-2">
      <small className="muted block">
        잘못 올린 사진 표시 · {photos.length}장 (지우지 않고 흐리게 맨 뒤에
        둬요)
      </small>
      <div className="flex flex-wrap gap-2 mt-1">
        {photos.map((src, n) =>
          masks?.[n] ? (
            <span
              key={n}
              className="photo-masked masked-note-thumb"
              title="개인정보 이유로 가림"
            >
              <img src={src} alt="" />
              <span className="sr-only">
                잘못 올린 사진 {n + 1} · 개인정보 이유로 가림
              </span>
            </span>
          ) : (
            <img
              key={n}
              src={src}
              alt={`잘못 올린 사진 ${n + 1}`}
              className="h-12 w-12 rounded object-cover opacity-40"
            />
          ),
        )}
      </div>
    </div>
  )
}

// 링크 화면에 보일 사진만 고릅니다. 올린 사람만 자기 사진을 숨길 수 있어요.
export const visibleBefore = (r: Repair) =>
  r.photos.filter((_, i) => !isHidden(r, `b:${i}`))
// 수리 전 사진: 잘못 올린 사진은 흐리게 맨 뒤로(링크 화면에서는 숨긴 사진 제외)
export function beforePhotos(r: Repair, guest?: boolean) {
  const list = r.photos
    .map((src, n) => ({ src, n }))
    .filter((x) => !guest || !isHidden(r, `b:${x.n}`))
  return {
    main: list.filter((x) => !r.wrongPhotos?.includes(x.n)).map((x) => x.src),
    wrong: list.filter((x) => r.wrongPhotos?.includes(x.n)).map((x) => x.src),
    wrongMasks: list
      .filter((x) => r.wrongPhotos?.includes(x.n))
      .map((x) => maskOf(r, `b:${x.n}`)),
  }
}
export const visibleAfter = (r: Repair, recordId: string, photos: string[]) =>
  photos.filter((_, i) => !isHidden(r, `a:${recordId}:${i}`))

/* ── 개인정보 이유로 가린 사진(팀 회의 결정: 삭제 없음, 흐리게 가리고 상대는 이견) ── */
// 가린 사진 목록(누가·언제·이견)
export function RepairMaskedNotes({
  r,
  role,
  readOnly,
  onAction,
}: {
  r: Repair
  role: Role
  readOnly?: boolean
  onAction: OnAction
}) {
  const items = (r.privacyMasks || []).map((m) => {
    if (m.key.startsWith("b:")) {
      const n = Number(m.key.slice(2))
      return { mask: m, src: r.photos[n], label: `수리 전 사진 ${n + 1}` }
    }
    const [, id, i] = m.key.split(":")
    const a = r.afterRecords?.find((x) => x.id === id)
    return {
      mask: m,
      src: a?.photos[Number(i)],
      label: `${a?.title ?? "수리 후"} 사진 ${Number(i) + 1}`,
    }
  })
  return (
    <MaskedPhotoNotes
      items={items}
      role={role}
      readOnly={readOnly}
      onObject={(key, reason) => {
        const label = items.find((it) => it.mask.key === key)?.label ?? "사진"
        const update = (x: Repair) => objectToMask(x, key, role, reason)
        const e = tryOn(r, update)
        if (e) return e
        onAction(`가린 사진에 이견 · ${label}`, update)
      }}
    />
  )
}
export function PhotoVisibility({
  r,
  role,
  onAction,
}: {
  r: Repair
  role: Role
  onAction: OnAction
}) {
  const items: { key: string; src: string; label: string }[] = []
  if (r.author === role)
    r.photos.forEach((src, i) =>
      items.push({ key: `b:${i}`, src, label: `수리 전 ${i + 1}` }),
    )
  r.afterRecords
    ?.filter((a) => a.by === role)
    .forEach((a) =>
      a.photos.forEach((src, i) =>
        items.push({
          key: `a:${a.id}:${i}`,
          src,
          label: `${a.title} ${i + 1}`,
        }),
      ),
    )
  const labelOf = (keys: string[]) =>
    keys.map((k) => items.find((it) => it.key === k)?.label).join(", ")
  return (
    <Fold title="내 사진 관리" icon={<ImageIcon size={16} />}>
      {r.hidden && (
        <div className="info-box mt-2">
          <p>이전 방식으로 이 건의 사진 전체가 링크에서 숨겨져 있어요.</p>
          {r.author === role && (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onAction("링크 사진 전체 숨김 해제", (x) => {
                  x.hidden = false
                })
              }
            >
              전체 숨김 해제
            </Button>
          )}
        </div>
      )}
      {items.length ? (
        <div className="mt-2">
          <PhotoManageList
            items={items.map((it) => {
              const b = it.key.startsWith("b:") ? Number(it.key.slice(2)) : -1
              return {
                ...it,
                canWrong: b >= 0,
                wrong: b >= 0 && Boolean(r.wrongPhotos?.includes(b)),
                masked: Boolean(maskOf(r, it.key)),
                note: isHidden(r, it.key) ? "링크에서 숨김" : undefined,
                extra: maskOf(r, it.key) ? undefined : (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() =>
                      onAction(
                        `사진 ${isHidden(r, it.key) ? "링크 공개" : "링크에서 숨김"} · ${it.label}`,
                        (x) => togglePhoto(x, it.key),
                      )
                    }
                  >
                    {r.hiddenPhotos?.includes(it.key)
                      ? "다시 공개"
                      : "링크에서 숨김"}
                  </Button>
                ),
              }
            })}
            onWrong={(keys, mark) => {
              const e = tryOn(r, (x) =>
                keys.forEach((k) =>
                  toggleWrongPhoto(x, role, Number(k.slice(2))),
                ),
              )
              if (e) return e
              onAction(
                `${mark ? "잘못 올린 사진 표시" : "잘못 올린 사진 표시 떼기"} · ${labelOf(keys)}`,
                (x) =>
                  keys.forEach((k) =>
                    toggleWrongPhoto(x, role, Number(k.slice(2))),
                  ),
              )
            }}
            onMask={(keys) => {
              const update = (x: Repair) =>
                keys.forEach((k) => maskPhoto(x, k, role, uploaderOf(x, k)))
              const e = tryOn(r, update)
              if (e) return e
              onAction(`개인정보 이유로 사진 가림 · ${labelOf(keys)}`, update)
            }}
          />
        </div>
      ) : (
        <p className="muted mt-2">내가 올린 사진이 없어요.</p>
      )}
    </Fold>
  )
}
