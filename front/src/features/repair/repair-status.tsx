import {
  useContext,
  useEffect,
  useRef,
  useState,
  type ComponentType,
} from "react"
import { DetailMoreContext } from "@/features/repair/detail-more"
import { Camera, Clock, RotateCcw, Wrench } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Status } from "@/components/common/blocks"
import { ActionDialog } from "@/components/common/action-dialog"
import type { Repair, Role, Room } from "@/data/demo"
import { hasAction, type RepairAction } from "@/api/actions"
import {
  RespondButtons,
  type RespondAnswer,
} from "@/components/common/respond-buttons"
import { SupplementRequest } from "@/components/common/supplement-request"
// 화면은 표시용 값만 계산해요. 상태를 바꾸는 판단은 수리 건 API(목업·서버)가 해요.
import {
  reaskWaitDays,
  hasAfterPhoto,
  roomSoloReason,
  soloReasonText,
} from "@/lib/repair-rules"
import { versionOf } from "@/lib/versions"
import { ackText, sideName } from "@/lib/words"
import type { RepairCommand } from "@/features/repair/use-repair-case"

const waitText = (stage?: string) =>
  stage === "재통지" ? "다시 알렸어요" : stage === "미확인" ? "확인 없음" : ""

// 수리 상태·하자 인정 표시(상태와 규칙 D). 비회원 링크 화면에서도 같은 표시를 씁니다.
// compact: 상세 화면 제목 옆에 수리 상태·하자 인정·수리 시작 표시가 이미 있어 겹치는 배지를 뺍니다.
export function RepairBadges({
  r,
  room,
  repairs,
  onOpenRepair,
  compact,
}: {
  r: Repair
  room: Room
  repairs: Repair[]
  onOpenRepair?: (id: string) => void
  compact?: boolean
}) {
  const solo = roomSoloReason(room)
  const prev = r.recurrenceOf
    ? repairs.find((x) => x.id === r.recurrenceOf)
    : undefined
  const later = repairs.filter((x) => x.recurrenceOf === r.id)
  return (
    <div className={compact ? "space-y-3" : "mb-5 space-y-3"}>
      <div className="badges">
        {r.status === "처리 완료" && (
          <Status>
            {r.soloClosed
              ? `처리 완료(${sideName(r.soloClosed)} 확인)`
              : "처리 완료(둘 다 확인)"}
          </Status>
        )}
        {(!compact || r.ackDispute) && (
          <Status>{`하자 · ${ackText(r.ack)}`}</Status>
        )}
        {r.waits?.ack === "재통지" && <Status>하자 확인 재통지됨</Status>}
        {!compact && r.startFlag && <Status>{r.startFlag}</Status>}
        {r.closeBy && (
          <Status>
            종료 확인 대기 · {r.closeBy} 요청
            {r.waits?.close ? ` · ${waitText(r.waits.close)}` : ""}
          </Status>
        )}
        {r.soloReason && (
          <Status>
            {r.soloReason === "상대 없음" ? "혼자 남긴 기록" : r.soloReason}
          </Status>
        )}
        {r.confirmRequested && (
          <Status>{`종료 확인 요청됨 · 상대 연결 뒤${r.waits?.soloclose ? ` · ${waitText(r.waits.soloclose)}` : ""}`}</Status>
        )}
        {/* 상대가 아직 없으면 줄마다 붙이지 않고 호실 맨 위에 한 번만 보여요(08-화면-기본-틀 8장). */}
        {!r.soloReason &&
          solo &&
          solo !== "상대 없음" &&
          r.status !== "처리 완료" && <Status>{soloReasonText[solo]}</Status>}
      </div>
      {r.ackDispute && (
        <div className="dispute-callout">
          <strong>
            {sideName(r.ackDispute.by)} ·{" "}
            {r.ackDispute.type === "보류"
              ? "보충 요청됨 · 더 필요한 게 있어요"
              : "이견 · 사실과 달라요"}
          </strong>
          <small className="block">{r.ackDispute.at}</small>
          <p>{r.ackDispute.reason}</p>
        </div>
      )}
      {prev && (
        <p className="muted">
          이전 수리 건 이후 재발 ·{" "}
          {onOpenRepair ? (
            <Button
              variant="link"
              className="h-auto p-0"
              onClick={() => onOpenRepair(prev.id)}
            >
              {prev.title} ({prev.date})
            </Button>
          ) : (
            `${prev.title} (${prev.date})`
          )}
        </p>
      )}
      {later.length > 0 && (
        <p className="muted">
          이 건 이후 재발 ·{" "}
          {later.map((x) =>
            onOpenRepair ? (
              <Button
                key={x.id}
                variant="link"
                className="h-auto p-0 mr-2"
                onClick={() => onOpenRepair(x.id)}
              >
                {x.title} ({x.date})
              </Button>
            ) : (
              <span key={x.id}>{x.title} </span>
            ),
          )}
        </p>
      )}
    </div>
  )
}

interface Act {
  key: string
  label: string
  icon?: ComponentType<{ size?: number; "aria-hidden"?: boolean }>
  outline?: boolean
  onClick: () => void
}
interface ActionsProps {
  r: Repair
  room: Room
  role: Role
  // 서버가 준 지금 누를 수 있는 행동(GET /api/repair-cases/{id}의 availableActions)
  actions: readonly RepairAction[]
  // 수리 건 API로 행동을 보내요(06 4장 /api/repair-cases/{id}/…). 오류면 문구를 돌려줘요.
  onCommand: (call: RepairCommand) => Promise<string | undefined>
  onOpen: (m: "after") => void
  onRecur: (r: Repair) => void
}
export function RepairActions({
  r,
  room,
  role,
  actions,
  onCommand,
  onOpen,
  onRecur,
}: ActionsProps) {
  const [mode, setMode] = useState<"notyet" | "reopen" | "close-ack" | null>(
    null,
  )
  const [disputeType, setDisputeType] = useState<"보류" | "이견">("보류")
  const [closeAck, setCloseAck] = useState("네, 하자도 확인해요")
  const [error, setError] = useState("")
  // 판단은 서버(지금은 목업 API)가 해요. 화면은 보내고, 돌아온 오류 문구만 보여 줘요.
  const exec = async (call: RepairCommand) => {
    const e = await onCommand(call)
    setError(e ?? "")
    return e
  }
  const solo = roomSoloReason(room)
  const ackPending = ["확인 대기", "상대 미확인"].includes(r.ack)
  const reasons = [
    solo,
    r.waits?.ack === "미확인" ? "상대 미확인" : undefined,
    r.waits?.close === "미확인" ? "상대 미확인" : undefined,
  ].filter(Boolean)
  const has = (a: RepairAction) => hasAction(actions, a)
  // 버튼은 서버가 준 availableActions에 있는 것만 그려요(08 0장 원칙 6). 순서는 그대로이고,
  // 수리 상세에서는 앞의 두 개만 크게, 나머지는 '더 보기' 메뉴로 보내요.
  // 하자 응답은 공통 부품(RespondButtons)으로 따로 그려요. 여기에는 그 밖의 행동만 모아요.
  // 본 모습 번호를 함께 보내요(REP-7d·7j). 그사이 바뀌었으면 서버가 VERSION_CONFLICT로 거절해요.
  const respond = (answer: RespondAnswer, reason: string) =>
    exec((api, s) =>
      api.respond(s, r.id, { answer, reason, seenVersionNo: versionOf(r) }),
    )
  const acts: Act[] = []
  if (has("start"))
    acts.push({
      key: "start",
      label: "수리 시작",
      icon: Wrench,
      onClick: () => exec((api, s) => api.start(s, r.id)),
    })
  if (has("close-alone") && !r.closeBy && solo)
    acts.push({
      key: "solo-close",
      label: "혼자 종료",
      onClick: () => exec((api, s) => api.closeAlone(s, r.id)),
    })
  if (has("request-close"))
    acts.push({
      key: "request-close",
      label: "종료 확인 요청",
      onClick: () => exec((api, s) => api.requestClose(s, r.id)),
    })
  if (has("confirm-close") && r.closeBy)
    acts.push({
      key: "confirm-close",
      label: "수리 종료 확인",
      onClick: () => {
        if (r.author !== role && ackPending) {
          setCloseAck("네, 하자도 확인해요")
          setMode("close-ack")
        } else exec((api, s) => api.confirmClose(s, r.id))
      },
    })
  if (has("not-yet"))
    acts.push({
      key: "notyet",
      label: "아직 안 됐어요",
      outline: true,
      onClick: () => setMode("notyet"),
    })
  if (has("close-alone") && r.closeBy === role)
    acts.push({
      key: "unconfirmed-close",
      label: "혼자 종료",
      onClick: () => exec((api, s) => api.closeAlone(s, r.id)),
    })
  // 혼자 종료된 건을 늦게 확인(08 8장: "처리 완료(○○ 확인)"에 [종료 확인])
  if (has("confirm-close") && r.soloClosed)
    acts.push({
      key: "confirm-solo",
      label: "종료 확인",
      onClick: () => exec((api, s) => api.confirmClose(s, r.id)),
    })
  if (has("reopen"))
    acts.push({
      key: "reopen",
      label: "다시 열기",
      icon: RotateCcw,
      outline: true,
      onClick: () => setMode("reopen"),
    })
  if (has("recur"))
    acts.push({
      key: "recur",
      label: "같은 곳 재발 · 새 수리 건 등록",
      outline: true,
      onClick: () => onRecur(r),
    })
  if (has("photos") && r.status === "처리 중")
    acts.push({
      key: "after",
      label: "수리 후 사진 남기기",
      icon: Camera,
      outline: true,
      onClick: () => onOpen("after"),
    })
  const register = useContext(DetailMoreContext)
  const big = register ? acts.slice(0, 2) : acts
  const rest = register ? acts.slice(2) : []
  // 메뉴에서 누를 때는 그때의 최신 행동을 불러요(오래된 화면 값으로 실행되지 않게)
  const latest = useRef(acts)
  const restKey = rest.map((a) => `${a.key}:${a.label}`).join("|")
  useEffect(() => {
    latest.current = acts
  })
  useEffect(() => {
    register?.(
      rest.map((a) => ({
        key: a.key,
        label: a.label,
        icon: a.icon,
        onSelect: () => latest.current.find((x) => x.key === a.key)?.onClick(),
      })),
    )
  }, [register, restKey]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => register?.([]), [register])
  return (
    <div className="repair-actions space-y-3 mt-5">
      {reasons.length > 0 && (
        <div className="info-box">
          <div>
            <strong>
              혼자 진행할 수 있어요 · {[...new Set(reasons)].join(", ")}
            </strong>
            {[...new Set(reasons)].map((x) => (
              <p key={x}>{soloReasonText[x as keyof typeof soloReasonText]}</p>
            ))}
            <p>
              사실(수리 진행, 종료, 지불 기록)은 혼자 남길 수 있지만, 돈 상태는
              혼자 합의됨이 되지 않아요.
            </p>
          </div>
        </div>
      )}
      {has("respond") && (
        <RespondButtons
          onRespond={respond}
          answered={
            r.ack === "보충 요청"
              ? "NEED_MORE"
              : r.ack === "이견"
                ? "DISPUTE"
                : undefined
          }
          note="하자가 있다는 확인이에요. 수리비는 따로 정해요."
        />
      )}
      {has("supplement") && (
        <SupplementRequest
          waitDays={reaskWaitDays(r.reaskedAt)}
          onSupplement={(data) =>
            exec((api, s) => api.supplement(s, r.id, data))
          }
        />
      )}
      {big.length > 0 && (
        <div className="actions">
          {big.map((a) => (
            <Button
              key={a.key}
              variant={a.outline ? "outline" : "default"}
              onClick={a.onClick}
            >
              {a.icon && <a.icon />}
              {a.label}
            </Button>
          ))}
        </div>
      )}
      {r.status === "처리 중" && !r.closeBy && !hasAfterPhoto(r) && (
        <p className="muted">
          <Camera size={14} className="inline mr-1" />
          종료하려면 수리 후 사진이 1장 이상 필요해요. [수리 후 사진 남기기]로
          먼저 올려 주세요.
        </p>
      )}
      {r.closeBy === role && (
        <p className="muted">
          <Clock size={14} className="inline mr-1" />
          상대 쪽의 종료 확인을 기다리고 있어요
          {r.waits?.close ? ` · ${waitText(r.waits.close)}` : ""}.
        </p>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}

      <ActionDialog
        open={mode === "notyet"}
        title="아직 안 됐어요"
        onClose={() => setMode(null)}
        onSubmit={(f) =>
          exec((api, s) =>
            api.notYet(s, r.id, { reason: String(f.get("reason") || "") }),
          )
        }
      >
        <div className="field">
          <label htmlFor="notyet-reason">사유 · 필수</label>
          <Textarea id="notyet-reason" name="reason" required />
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "reopen"}
        title="다시 열기"
        description="상대가 혼자 종료한 건을 사유와 함께 다시 엽니다. 처리 중으로 돌아가고 종료 확인을 다시 받아야 해요."
        onClose={() => setMode(null)}
        onSubmit={(f) =>
          exec((api, s) =>
            api.reopen(s, r.id, { reason: String(f.get("reason") || "") }),
          )
        }
      >
        <div className="field">
          <label htmlFor="reopen-reason">사유 · 필수</label>
          <Textarea id="reopen-reason" name="reason" required />
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "close-ack"}
        title="수리 종료 확인"
        description="끝난 뒤에는 수리 건이 잠기므로, 아직 비어 있는 하자 인정도 함께 물어요."
        submitLabel="종료 확인"
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const agree = closeAck.startsWith("네")
          const reason = String(f.get("reason") || "")
          // 종료 확인과 비어 있던 하자 응답을 함께 보내요(06 S-12)
          return exec((api, s) =>
            api.confirmClose(s, r.id, {
              defect: agree
                ? { answer: "ACK" }
                : {
                    answer: disputeType === "보류" ? "NEED_MORE" : "DISPUTE",
                    reason,
                  },
            }),
          )
        }}
      >
        <div className="field">
          <label>이 하자가 있었다는 것도 확인할까요?</label>
          <Choice
            label="하자 인정"
            value={closeAck}
            values={["네, 하자도 확인해요", "아니요, 더 필요하거나 달라요"]}
            onChange={setCloseAck}
          />
        </div>
        {closeAck.startsWith("아니요") && (
          <>
            <div className="field">
              <label>어떤 건가요?</label>
              <Choice
                label="어떤 건가요"
                value={
                  disputeType === "보류"
                    ? "더 필요한 게 있어요"
                    : "사실과 달라요"
                }
                values={["더 필요한 게 있어요", "사실과 달라요"]}
                onChange={(v) =>
                  setDisputeType(v === "사실과 달라요" ? "이견" : "보류")
                }
              />
            </div>
            <div className="field">
              <label htmlFor="close-ack-reason">사유 · 필수</label>
              <Textarea id="close-ack-reason" name="reason" required />
            </div>
          </>
        )}
      </ActionDialog>
    </div>
  )
}
