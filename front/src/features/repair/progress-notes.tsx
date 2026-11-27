import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Panel } from "@/components/common/blocks"
import { ActionDialog } from "@/components/common/action-dialog"
import type { ProgressNote, Repair, Role } from "@/data/demo"
import { recordDate } from "@/api/http-repair-view"
import { sideName } from "@/lib/words"
import type { RepairCommand } from "@/features/repair/use-repair-case"

// 진행 상황(REP-6, 08 5-2). 원래 하자 기록은 그대로 두고 지금 상황을 더해요(고칠 수 없어요).
// [상황이 바뀌었어요]를 켜면 상대에게 확인을 요청하고, 상대는 맞아요 / 아니에요 · 더 필요해요로 답해요.
// 버튼은 서버가 준 것만 그려요: 남기기·묻기는 availableActions, 답하기는 진행 상황마다 canRespond(08 0장 원칙 6)
type NotYetAnswer = "보류" | "이견"

function dateOf(at: string) {
  return /^\d{4}-\d{2}-\d{2}T/.test(at) ? recordDate(at) : at
}

function NoteView({
  n,
  children,
}: {
  n: ProgressNote
  children?: React.ReactNode
}) {
  return (
    <li className="space-y-1 border-b border-line py-3 last:border-0">
      <p className="text-sm">
        <span className="muted mr-2">{dateOf(n.at)}</span>
        <strong className="mr-2">{sideName(n.by)}</strong>
        {n.text || <span className="muted">사진만 남김</span>}
        {n.photoCount > 0 && (
          <span className="muted ml-2">[사진 {n.photoCount}]</span>
        )}
      </p>
      {n.asksCheck && !n.response && (
        <p className="muted text-sm">
          {sideName(n.by === "임차인" ? "임대인" : "임차인")} 확인 기다림 ·
          상황이 바뀌었어요
        </p>
      )}
      {n.response && (
        <p className="text-sm">
          {sideName(n.response.by)} · {n.response.answer}
          {n.response.reason ? ` · ${n.response.reason}` : ""}
        </p>
      )}
      {children}
    </li>
  )
}

export function ProgressNotes({
  repair,
  role,
  canNote,
  canAsk,
  onCommand,
}: {
  repair: Repair
  role: Role
  // availableActions의 progress-notes(남기기)·ask-progress(묻기)
  canNote: boolean
  canAsk: boolean
  // 수리 건 API로 보내요(POST /repair-cases/{id}/progress-notes·ask-progress, /progress-notes/{id}/respond)
  onCommand: (call: RepairCommand) => Promise<string | undefined>
}) {
  const [mode, setMode] = useState<"write" | "answer" | null>(null)
  const [noteId, setNoteId] = useState("")
  const [answer, setAnswer] = useState<NotYetAnswer>("보류")
  const [asksCheck, setAsksCheck] = useState(false)
  const [error, setError] = useState("")
  const notes = repair.progressNotes || []
  const counterpart = sideName(role === "임차인" ? "임대인" : "임차인")
  const exec = async (call: RepairCommand) => {
    const e = await onCommand(call)
    setError(e ?? "")
    return e
  }
  const respond = (id: string, ack: boolean, reason = "") =>
    exec((api, sess) =>
      api.respondProgressNote(sess, repair.id, id, {
        answer: ack ? "ACK" : answer === "보류" ? "NEED_MORE" : "DISPUTE",
        ...(ack ? {} : { reason }),
      }),
    )
  return (
    <Panel title="진행 상황">
      <p className="muted mb-3 text-sm">
        막힌 사정이나 바뀐 상황을 남겨요. 원래 하자 기록은 그대로예요.
      </p>
      {notes.length ? (
        <ul className="m-0 list-none p-0">
          {notes.map((n) => (
            <NoteView key={n.id} n={n}>
              {n.canRespond && (
                <div className="actions">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => respond(n.id, true)}
                  >
                    맞아요
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setNoteId(n.id)
                      setAnswer("보류")
                      setMode("answer")
                    }}
                  >
                    아니에요 · 더 필요해요
                  </Button>
                </div>
              )}
            </NoteView>
          ))}
        </ul>
      ) : (
        <p className="muted">아직 남긴 진행 상황이 없어요.</p>
      )}
      {(canNote || canAsk) && (
        <div className="actions mt-3">
          {canNote && (
            <Button
              variant="outline"
              onClick={() => {
                setAsksCheck(false)
                setError("")
                setMode("write")
              }}
            >
              + 진행 상황 남기기
            </Button>
          )}
          {canAsk && (
            <Button
              variant="outline"
              onClick={() =>
                exec((api, sess) => api.askProgress(sess, repair.id))
              }
            >
              진행 상황 묻기
            </Button>
          )}
        </div>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={mode === "write"}
        title="진행 상황 남기기"
        description="지금 상황을 더해요. 남긴 진행 상황은 고칠 수 없어요."
        submitLabel="남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const text = String(f.get("text") || "").trim()
          if (!text) return "진행 상황을 입력해 주세요."
          return exec((api, sess) =>
            api.addProgressNote(sess, repair.id, {
              text,
              photos: [],
              asksCheck,
            }),
          )
        }}
      >
        <div className="field">
          <label htmlFor="progress-text">지금 상황</label>
          <Textarea
            id="progress-text"
            name="text"
            maxLength={2000}
            placeholder="예: 업체가 수요일에 와요"
            required
          />
        </div>
        {/* 하자를 둘 다 확인한 건에서만 확인을 요청할 수 있어요(REP-6d). 한 건에 7일에 한 번 */}
        {repair.ack === "확인됨" && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={asksCheck}
              onChange={(e) => setAsksCheck(e.target.checked)}
            />
            상황이 바뀌었어요. {counterpart}에게 확인을 요청할게요
          </label>
        )}
      </ActionDialog>
      <ActionDialog
        open={mode === "answer"}
        title="바뀐 상황에 답하기"
        description="보류는 더 필요한 것을, 이견은 사실과 다른 점을 사유로 남겨요."
        submitLabel="답 남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const reason = String(f.get("reason") || "").trim()
          if (!reason) return "사유를 입력해 주세요."
          return respond(noteId, false, reason)
        }}
      >
        <div className="field">
          <label>답</label>
          <Choice
            label="답"
            value={answer}
            values={[
              { value: "보류", label: "보류 · 더 필요해요" },
              { value: "이견", label: "이견 · 사실과 달라요" },
            ]}
            onChange={(v) => setAnswer(v as NotYetAnswer)}
          />
        </div>
        <div className="field">
          <label htmlFor="progress-reason">사유 · 필수</label>
          <Textarea id="progress-reason" name="reason" required />
        </div>
      </ActionDialog>
    </Panel>
  )
}
