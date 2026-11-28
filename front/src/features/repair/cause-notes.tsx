import { useState, type ReactNode } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Panel, Status } from "@/components/common/blocks"
import { ActionDialog } from "@/components/common/action-dialog"
import { PhotoGallery } from "@/features/photo/photo-gallery"
import { RecordDescription } from "@/components/common/record-description"
import type { CauseClaim, Repair, Role } from "@/data/demo"
import { readPhotos } from "@/lib/photos"
import { sideName } from "@/lib/words"
import { Fold } from "@/components/common/fold"
import { History } from "lucide-react"
import type { RepairCommand } from "@/features/repair/use-repair-case"
import { API_MODE } from "@/config"

// 원인 설명(REP-8, 08 5-3). 고치면 언제나 새 설명으로 남아요(decisions 2026-11-13)
const BASES = [
  "내 추측",
  "직접 봤어요",
  "업체·전문가 소견",
  "다른 사람에게 들었어요",
]
// 누구에게 들었는지는 06 4-6 원인 설명 본문에 칸이 없어 서버에 남지 않아요. 저장된 것처럼 보였다 사라지지 않게
// HTTP 모드에서는 입력칸을 숨겨요(#270 리뷰). 06에 칸이 생기면 이 줄을 지우고 http-repair-cases에서 보내요
const ASK_SOURCE = API_MODE !== "http"
function basisText(c: CauseClaim) {
  return `${c.basis}${c.company ? `(${c.company})` : ""}${c.source ? `(${c.source})` : ""}${c.photos?.length ? ` · 근거 사진 ${c.photos.length}` : ""}`
}
function latestResponses(c: CauseClaim) {
  const m = new Map<Role, CauseClaim["responses"][number]>()
  c.responses.forEach((x) => m.set(x.by, x))
  return [...m.values()]
}
function ClaimView({ c, children }: { c: CauseClaim; children?: ReactNode }) {
  const answers = latestResponses(c)
  return (
    <div className="cause-card">
      <div className="history-head">
        <strong>{sideName(c.by)}의 설명</strong>
        <span className="badges">
          <Status>{`근거 · ${basisText(c)}`}</Status>
          {answers.map((x) => (
            <Status key={x.by}>
              {x.agree ? `${x.by} 동의` : `${x.by} 이견`}
            </Status>
          ))}
        </span>
      </div>
      <small className="muted">
        {c.at}
        {c.edits?.length ? " · 문장 고침" : ""}
      </small>
      <RecordDescription text={c.text} />
      {c.photos?.length ? (
        <PhotoGallery
          title={`${sideName(c.by)} 원인 설명 근거`}
          photos={c.photos}
          layout="compact"
        />
      ) : null}
      {answers
        .filter((x) => !x.agree)
        .map((x) => (
          <p key={x.by} className="fix-note">
            {sideName(x.by)} 이견 사유: {x.reason}
          </p>
        ))}
      {c.edits?.length || c.responses.length > answers.length ? (
        <Fold
          title="경과"
          count={c.responses.length + (c.edits?.length || 0)}
          icon={<History size={16} />}
        >
          <div className="space-y-1">
            {[...c.responses].reverse().map((x, i) => (
              <p key={"r" + i} className="muted text-sm">
                {sideName(x.by)} · {x.agree ? "동의" : `이견 · ${x.reason}`} ·{" "}
                {x.at}
              </p>
            ))}
            {[...(c.edits || [])].reverse().map((e, i) => (
              <p key={"e" + i} className="muted text-sm">
                이전 문장({e.at}): {e.text}
              </p>
            ))}
          </div>
        </Fold>
      ) : null}
      {children}
    </div>
  )
}
// 각 쪽의 원인 설명(상태와 규칙 D 원인에 대한 설명). 서비스는 원인을 판정하지 않고, 부담 합의·하자 인정을 바꾸지 않습니다.
export function CauseNotes({
  repair,
  role,
  readOnly,
  onCommand,
}: {
  repair: Repair
  role: Role
  // 원인 설명 행동(availableActions의 cause-notes)이 없으면 읽기만 해요
  readOnly: boolean
  // 수리 건 API로 보내요(POST /repair-cases/{id}/cause-notes, /cause-notes/{id}/respond)
  onCommand: (call: RepairCommand) => Promise<string | undefined>
}) {
  const [mode, setMode] = useState<"write" | "dispute" | null>(null)
  const [claimId, setClaimId] = useState("")
  const [basis, setBasis] = useState("내 추측")
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  const active = (repair.causes || []).filter((c) => !c.supersededBy)
  const past = (repair.causes || []).filter((c) => c.supersededBy)
  const mine = active.find((c) => c.by === role)
  const exec = async (call: RepairCommand) => {
    const e = await onCommand(call)
    setError(e ?? "")
    return e
  }
  function respond(c: CauseClaim, agree: boolean, reason = "") {
    return exec((api, sess) =>
      api.respondCauseNote(sess, repair.id, c.id, {
        answer: agree ? "ACK" : "DISPUTE",
        reason,
      }),
    )
  }
  function write(f: FormData) {
    const text = String(f.get("reason") || "").trim()
    if (!text) return "설명을 입력해 주세요."
    const company =
      basis === "업체·전문가 소견"
        ? String(f.get("company") || "").trim() || undefined
        : undefined
    const source =
      ASK_SOURCE && basis === "다른 사람에게 들었어요"
        ? String(f.get("source") || "").trim() || undefined
        : undefined
    // 원인 설명은 덧붙이기예요(decisions 2026-11-13): 고치면 언제나 새 설명으로 남고, 이전 설명은 '이전 설명'에 남아요.
    return exec((api, sess) =>
      api.addCauseNote(sess, repair.id, {
        basis,
        text,
        company,
        source,
        photos: [...photos],
        // 고치면 내 지금 설명을 가리켜요(서버는 지금 설명과 다르면 거절)
        previousId: mine?.id,
      }),
    )
  }
  return (
    <Panel title="각 쪽의 원인 설명">
      <p className="muted text-sm mb-3">
        각자의 설명과 근거만 남겨요. HomeStamp는 원인을 판정하지 않고, 수리
        상태·수리비 나누기·하자 확인도 바꾸지 않아요.
      </p>
      {active.map((c) => (
        <ClaimView key={c.id} c={c}>
          {/* 응답 버튼은 서버가 이 설명에 응답할 수 있다고 할 때만(canRespond, 08 0장 원칙 6) */}
          {!readOnly && c.by !== role && c.canRespond !== false && (
            <div className="actions">
              <Button
                variant="outline"
                size="sm"
                onClick={() => respond(c, true)}
              >
                동의
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setClaimId(c.id)
                  setMode("dispute")
                }}
              >
                이견
              </Button>
            </div>
          )}
        </ClaimView>
      ))}
      {!active.length && <p className="muted">아직 남긴 원인 설명이 없어요.</p>}
      {past.length > 0 && (
        <Fold
          title="이전 설명"
          count={past.length}
          icon={<History size={16} />}
        >
          {past.map((c) => (
            <ClaimView key={c.id} c={c} />
          ))}
        </Fold>
      )}
      {!readOnly && (
        <Button
          variant="outline"
          onClick={() => {
            setBasis(mine?.basis || "내 추측")
            setPhotos([])
            setError("")
            setMode("write")
          }}
        >
          {mine ? "내 설명 고치기" : "원인 설명 남기기"}
        </Button>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <ActionDialog
        open={mode === "write"}
        title={mine ? "내 원인 설명 고치기" : "원인 설명 남기기"}
        description={
          mine
            ? "고친 설명은 새 설명으로 남고, 지금 설명은 ‘이전 설명’에 그대로 남아요. 상대의 동의·이견은 새 설명에 다시 받아요."
            : "쪽마다 하나씩 남겨요. 근거 종류는 필수예요."
        }
        submitLabel="원인 설명 남기기"
        onClose={() => setMode(null)}
        onSubmit={write}
      >
        <div className="field">
          <label>근거 종류 · 필수</label>
          <Choice
            label="근거 종류"
            value={basis}
            values={BASES}
            onChange={setBasis}
          />
        </div>
        {basis === "업체·전문가 소견" && (
          <div className="field">
            <label htmlFor="cause-company">업체·전문가 이름 · 선택</label>
            <Input
              id="cause-company"
              name="company"
              defaultValue={mine?.company}
              placeholder="예: ○○설비"
            />
          </div>
        )}
        {ASK_SOURCE && basis === "다른 사람에게 들었어요" && (
          <div className="field">
            <label htmlFor="cause-source">누구에게 들었나요 · 선택</label>
            <Input
              id="cause-source"
              name="source"
              defaultValue={mine?.source}
              placeholder="예: 관리인, 윗집"
            />
          </div>
        )}
        <div className="field">
          <label htmlFor="cause-reason">설명</label>
          <Textarea
            id="cause-reason"
            name="reason"
            defaultValue={mine?.text}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="cause-photos">
            근거 자료 사진 · 선택 (소견서·견적서·현장 사진)
          </label>
          <Input
            id="cause-photos"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={(e) =>
              readPhotos(e.target.files, photos.length)
                .then((p) => setPhotos((old) => [...old, ...p]))
                .catch((err) => setError(err.message))
            }
          />
          {photos.length > 0 && (
            <small className="muted">새 사진 {photos.length}장 선택됨</small>
          )}
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "dispute"}
        title="원인 설명에 이견"
        description="서비스 안 알림만 가요(문자·기다림 타이머 없음)."
        submitLabel="이견 남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const c = active.find((v) => v.id === claimId)
          return c
            ? respond(c, false, String(f.get("reason") || ""))
            : "설명을 찾을 수 없어요."
        }}
      >
        <div className="field">
          <label htmlFor="cause-dispute">이견 사유 · 필수</label>
          <Textarea id="cause-dispute" name="reason" required />
        </div>
      </ActionDialog>
    </Panel>
  )
}
