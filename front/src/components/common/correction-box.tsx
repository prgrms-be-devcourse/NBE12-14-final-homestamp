import type { ReactNode } from "react"
import { PencilLine } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Status } from "@/components/prototype"
import { Fold } from "@/components/fold"
import { LongText } from "@/components/common/long-text"
import {
  RespondButtons,
  type RespondAnswer,
} from "@/components/common/respond-buttons"
import { SupplementRequest } from "@/components/common/supplement-request"
import type { FactCorrection, Role, TextEdit } from "@/data/demo"
import { hasAction, type CorrectionAction } from "@/api/actions"
import { correctionLabel } from "@/lib/correction-label"
import {
  groupCorrections,
  groupStatus,
  pendingRequest,
} from "@/lib/corrections"
import { reaskWaitDays } from "@/lib/reask"
import { safeSharedText } from "@/lib/service-rules"
import { sideName, sideText } from "@/lib/words"

// 고친 내용 상자(09 5장 '같은 뜻은 한 부품'): 수리 건·지불·점검 항목이 함께 써요.
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
