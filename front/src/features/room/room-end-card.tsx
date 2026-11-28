import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { ActionDialog } from "@/components/common/action-dialog"
import { Panel } from "@/components/common/blocks"
import type { Role } from "@/data/demo"
import {
  dayCount,
  endSoloText,
  monthDay,
  timeLeft,
  type RoomEndView,
} from "@/lib/room-end"

export const REJECT_END_FAILED =
  "답을 보내지 못했어요. 쓴 사유는 그대로 있으니 다시 시도해 주세요."

// S-06 거주 종료 카드(06 S-06 표, ROOM-9, #445). 버튼은 서버 availableActions에 있을 때만 보여요(06 4-0).
// 서버는 거주 종료 요청 문자·기다림 시작·미확인 뒤 적용·양쪽 안내가 연결되기 전에는 버튼을 주지 않아요(#421 D 리뷰).
// 그래서 안내 문구에도 "알림·문자를 보냈어요", "답이 없으면 자동으로 끝나요"를 쓰지 않아요.
export function RoomEndCard({
  view,
  role,
  canMoveOut,
  canConfirm,
  canReject,
  now,
  onMoveOut,
  onConfirm,
  onReject,
}: {
  view: RoomEndView
  role: Role
  canMoveOut: boolean
  canConfirm: boolean
  canReject: boolean
  now?: Date
  onMoveOut: () => void
  onConfirm: () => void
  // 저장 결과(성공 true, 실패 false)를 돌려주면 성공일 때만 창을 닫아요
  onReject: (reason: string) => void | Promise<boolean>
}) {
  const [rejecting, setRejecting] = useState(false)
  const hasButton = canMoveOut || canConfirm || canReject
  if (view.kind === "none" && !hasButton) return null

  const line = (() => {
    switch (view.kind) {
      case "asked": {
        const left =
          view.dueAt && timeLeft(view.dueAt, now)
            ? ` · ${timeLeft(view.dueAt, now)}`
            : ""
        return `집주인이 ${view.openedAt ? `${monthDay(view.openedAt)}에 ` : ""}이사했다고 남겼어요${left}`
      }
      case "waiting":
        return view.unconfirmed
          ? "세입자 확인 없음"
          : `세입자의 답을 기다려요${view.openedAt ? ` · ${dayCount(view.openedAt, now)}일째` : ""}`
      case "still-living":
        return `세입자가 아직 살고 있다고 답했어요${view.reason ? ` · ${view.reason}` : ""}`
      case "ended": {
        const solo = endSoloText(view.soloReason)
        // 정산 안내는 [정산 열기]가 생길 때 함께 둬요(공유를 그만둔 동안에는 정산을 끝낼 수 없어서, 03 B)
        return `${view.endedAt ? `${monthDay(view.endedAt)} ` : ""}거주 종료${solo ? ` · ${solo}` : ""}`
      }
      default:
        return undefined
    }
  })()

  return (
    <Panel className="mb-5" title="거주 종료">
      {line && <p className="mb-3">{line}</p>}
      {hasButton && (
        <div className="actions">
          {canConfirm && <Button onClick={onConfirm}>맞아요</Button>}
          {canReject && (
            <Button variant="outline" onClick={() => setRejecting(true)}>
              아직 살고 있어요
            </Button>
          )}
          {canMoveOut && (
            <Button
              variant={canConfirm ? "outline" : "default"}
              onClick={onMoveOut}
            >
              {role === "임차인" ? "이사했어요" : "이사함"}
            </Button>
          )}
        </div>
      )}
      {/* [아직 살고 있어요]는 사유와 함께 답해요(06 S-06, move-out/reject REASON_REQUIRED) */}
      <ActionDialog
        open={rejecting}
        title="아직 살고 있어요"
        description="집주인의 거주 종료 요청에 답해요. 사유가 함께 남아요."
        submitLabel="보내기"
        onClose={() => setRejecting(false)}
        onSubmit={async (f) => {
          const reason = String(f.get("reason") || "").trim()
          if (!reason) return "사유를 입력해 주세요."
          // 저장을 기다려요(ActionDialog가 그동안 버튼을 막아요). 성공이면 창이 닫히고, 실패면 문구를 돌려줘
          // 창과 쓴 사유를 그대로 둬요(#463 리뷰, #405 빠뜨린 하자 창과 같은 흐름)
          const saved = await onReject(reason)
          if (saved === false) return REJECT_END_FAILED
        }}
      >
        <div className="field">
          <label htmlFor="still-living-reason">사유 · 필수</label>
          <Textarea
            id="still-living-reason"
            name="reason"
            required
            placeholder="예: 이사는 다음 달 15일이에요"
          />
        </div>
      </ActionDialog>
    </Panel>
  )
}
