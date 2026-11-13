import { useState, type ReactNode } from "react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Panel, Status } from "@/components/prototype"
import { ActionDialog, tryOn } from "@/components/action-dialog"
import { MoneyInput } from "@/components/money-input"
import { PhotoGallery } from "@/components/photo-gallery"
import { RecordDescription } from "@/components/record-description"
import {
  uid,
  won,
  type BurdenLine,
  type CauseClaim,
  type Proposal,
  type Repair,
  type Role,
  type Room,
} from "@/data/demo"
import { parseMoney } from "@/lib/money"
import { readPhotos } from "@/lib/photos"
import {
  agreeProposal,
  answerChangeProposal,
  disputeProposal,
  lastAgreement,
  makeProposal,
  nowText,
  proposalText,
  proposalTotalMismatch,
  roomSoloReason,
  withdrawChangeProposal,
  withdrawProposal,
  workTotal,
} from "@/lib/repair-rules"
import { sideText } from "@/lib/words"
import { Fold } from "@/components/fold"
import { History } from "lucide-react"
import { Notice } from "@/components/notice"

type OnAction = (text: string, update: (r: Repair) => void) => void
const PLANS = [
  "임대인 전부",
  "임차인 전부",
  "나눠서",
  "제3자 포함",
  "비용 없음",
]

function ProposalCard({ p, title }: { p: Proposal; title: string }) {
  return (
    <div className="note">
      <strong>
        {title} {p.round || 1}회 · {sideText(p.by)}
        {p.at ? ` · ${p.at}` : ""} · {p.withdrawn ? "철회" : p.status}
      </strong>
      <p>
        전체 {won(p.amount)} · {proposalText(p)}
      </p>
      {p.reason && <p className="muted">사유: {p.reason}</p>}
      {p.disputeReason && (
        <p>
          {p.disputeBy} 이견: {p.disputeReason}
        </p>
      )}
      {p.closedReason && (
        <small className="muted block">바꾸자는 안 · {p.closedReason}</small>
      )}
    </div>
  )
}

// 수리비 부담 합의(상태와 규칙 1-2·E). 같은 안에 양측 행동(제안 + 상대 동의)이 있을 때만 합의됨이 됩니다.
export function CostAgreement({
  repair: r,
  room,
  role,
  readOnly,
  onAction,
}: {
  repair: Repair
  room: Room
  role: Role
  readOnly: boolean
  onAction: OnAction
}) {
  const [mode, setMode] = useState<
    "propose" | "counter" | "dispute" | "change-dispute" | "disagree" | null
  >(null)
  const [withCounter, setWithCounter] = useState(false)
  const [plan, setPlan] = useState("임대인 전부")
  const [error, setError] = useState("")
  const p = r.proposal
  const prevAgreement = lastAgreement(r)
  const stage = r.waits?.proposal
  const run = (text: string, update: (x: Repair) => void) => {
    const e = tryOn(r, update)
    if (e) {
      setError(e)
      return e
    }
    setError("")
    onAction(text, update)
  }
  function lines(f: FormData): BurdenLine[] | string {
    if (plan === "비용 없음") return []
    const total = parseMoney(String(f.get("total") || ""))
    if (!Number.isSafeInteger(total) || total <= 0)
      return "전체 금액을 1원 이상으로 입력해 주세요. 돈이 들지 않으면 ‘비용 없음’을 고르세요."
    if (plan === "임대인 전부") return [{ side: "임대인", amount: total }]
    if (plan === "임차인 전부") return [{ side: "임차인", amount: total }]
    const tenant = parseMoney(String(f.get("tenantAmount") || "0"))
    const third =
      plan === "제3자 포함" ? parseMoney(String(f.get("thirdAmount") || "")) : 0
    const label = String(f.get("thirdLabel") || "").trim()
    if (
      plan === "제3자 포함" &&
      (!label || !Number.isSafeInteger(third) || third <= 0)
    )
      return "제3자 설명(예: 윗집)과 제3자 금액을 입력해 주세요. 연락처는 받지 않아요."
    if (!Number.isSafeInteger(tenant) || tenant + third > total)
      return "나눈 금액의 합이 전체 금액을 넘지 않게 입력해 주세요. 나머지는 임대인 부담이에요."
    return [
      ...(plan === "제3자 포함"
        ? [{ side: "제3자" as const, label, amount: third }]
        : []),
      { side: "임차인", amount: tenant },
      { side: "임대인", amount: total - tenant - third },
    ]
  }
  function submitProposal(f: FormData) {
    const l = lines(f)
    if (typeof l === "string") return l
    const reason = String(f.get("reason") || "")
    const counter =
      mode === "counter"
        ? { disputeReason: String(f.get("disputeReason") || "") }
        : undefined
    const preview = {
      lines: l,
      amount: l.reduce((s, x) => s + x.amount, 0),
      payer: "나눠서",
      status: "합의 대기",
      by: role,
    } as Proposal
    return run(
      `${counter ? `부담 이견 + 다른 안 제안 ·${counter.disputeReason} · ` : "부담 제안 · "}${proposalText(preview)}`,
      (x) => makeProposal(x, role, l, reason, counter),
    )
  }
  return (
    <Panel title="수리비 · 부담 합의">
      <p className="muted">
        하자 사실·원인 설명·수리 진행과 독립적인 비용 합의입니다. 금액만 적고
        비율은 쓰지 않아요.
      </p>
      <div className="badges">
        <Status>{p?.status || "미정"}</Status>
        {stage && (
          <Status>{stage === "미확인" ? "상대 미확인" : "재통지됨"}</Status>
        )}
      </div>
      {p ? (
        <div className="proposal">
          <small>
            현재 제안 {p.round || 1}회 · 제안자 {p.by}
            {p.at ? ` · ${p.at}` : ""}
          </small>
          <strong>{won(p.amount)}</strong>
          <span>{proposalText(p)}</span>
          {p.reason && <p>{p.reason}</p>}
          {p.totalChanged && (
            <div className="dispute-callout">
              전체 금액이 바뀌었어요 · 지불 합계 {won(workTotal(r))}.{" "}
              {proposalTotalMismatch(r)
                ? "이 안의 합과 달라 동의할 수 없어요. 새 부담 제안을 내 주세요."
                : "지금 지불 합계와 같아 이 안에 다시 동의할 수 있어요."}
            </div>
          )}
          {!p.totalChanged &&
            p.status === "합의 대기" &&
            proposalTotalMismatch(r) && (
              <div className="dispute-callout">
                부담 금액의 합 {won(proposalTotalMismatch(r)!.sum)}이 전체 금액{" "}
                {won(proposalTotalMismatch(r)!.total)}과 달라요. 합이 맞는
                안이어야 동의할 수 있어요.
              </div>
            )}
          {p.disputeReason && (
            <div className="dispute-callout">
              <strong>{p.disputeBy}의 비용 이견</strong>
              <p>{p.disputeReason}</p>
            </div>
          )}
        </div>
      ) : (
        <p>아직 제안이 없어요.</p>
      )}
      {r.changeProposal && (
        <div className="dispute-callout">
          <strong>
            바꾸자는 안 있음 · {r.changeProposal.by} 제안
            {r.changeProposal.at ? ` · ${r.changeProposal.at}` : ""}
          </strong>
          <p>{proposalText(r.changeProposal)}</p>
          {r.changeProposal.reason && (
            <p className="muted">{r.changeProposal.reason}</p>
          )}
          <small className="block muted">
            상대가 동의할 때까지 지금 합의가 그대로 쓰여요. 이견·응답 없음이면
            지금 합의가 유지돼요.
          </small>
          {!readOnly && (
            <div className="actions mt-2">
              {r.changeProposal.by !== role ? (
                <>
                  <Button
                    size="sm"
                    onClick={() =>
                      run(
                        `바꾸자는 안 동의 · ${proposalText(r.changeProposal!)}`,
                        (x) => answerChangeProposal(x, role, true),
                      )
                    }
                  >
                    동의
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setMode("change-dispute")}
                  >
                    이견
                  </Button>
                </>
              ) : (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    run("바꾸자는 안 거둠", (x) =>
                      withdrawChangeProposal(x, role),
                    )
                  }
                >
                  거두기
                </Button>
              )}
            </div>
          )}
        </div>
      )}
      {prevAgreement && (
        <Notice>
          이전 합의: {proposalText(prevAgreement)} ({prevAgreement.round || 1}회
          · {prevAgreement.by} 제안) · 새 안이 합의될 때까지 함께 보여요.
        </Notice>
      )}
      {(p?.round || 0) >= 3 && (
        <p className="muted">
          제안 {p!.round}회째예요. 직접 대화로 정한 뒤 제안·동의하면 합의됨으로
          남아요.
        </p>
      )}
      {(roomSoloReason(room) || stage === "미확인") && (
        <p className="muted">
          상대 응답이 없어도 돈 상태는 혼자 합의됨이 되지 않아요.
        </p>
      )}
      {Boolean(r.proposalHistory?.length) && (
        <Fold
          title="이전 제안"
          count={r.proposalHistory!.length}
          icon={<History size={16} />}
          className="mb-3"
        >
          {[...r.proposalHistory!].reverse().map((h, i) => (
            <ProposalCard key={i} p={h} title="제안" />
          ))}
        </Fold>
      )}
      {!readOnly && (
        <div className="actions">
          {p?.status === "합의 대기" && p.by !== role && (
            <>
              {!proposalTotalMismatch(r) && (
                <Button
                  className="flex-1"
                  onClick={() =>
                    run(`부담 제안 동의 · ${proposalText(p)}`, (x) =>
                      agreeProposal(x, role),
                    )
                  }
                >
                  동의
                </Button>
              )}
              <Button
                variant="outline"
                className="flex-1"
                onClick={() => {
                  setWithCounter(false)
                  setPlan("나눠서")
                  setMode("disagree")
                }}
              >
                동의 안 해요
              </Button>
            </>
          )}
          {p?.status === "합의 대기" && p.by === role && !p.totalChanged && (
            <>
              <Button
                variant="outline"
                onClick={() =>
                  run("부담 제안 철회", (x) => withdrawProposal(x, role))
                }
              >
                제안 철회
              </Button>
              <p className="muted">
                내 제안입니다. 상대가 같은 안에 동의해야 합의돼요. 응답 전에는
                철회할 수 있어요.
              </p>
            </>
          )}
          {(p?.status !== "합의 대기" || p.totalChanged) &&
            !(
              (p?.status === "합의됨" || p?.status === "비용 없음") &&
              r.changeProposal
            ) && (
              <Button
                variant="outline"
                onClick={() => {
                  setPlan("임대인 전부")
                  setMode("propose")
                }}
              >
                {!p
                  ? "부담 제안하기"
                  : p.status === "합의됨" || p.status === "비용 없음"
                    ? "바꾸자는 새 제안"
                    : "새 부담 제안"}
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
        open={mode === "propose" || mode === "counter"}
        title={mode === "counter" ? "이견 + 다른 안 제안" : "비용 부담 제안"}
        description="상대가 같은 안에 동의해야 합의됩니다. 다른 안은 새 현재 제안이 되고 차례가 바뀌어요."
        submitLabel="제안 보내기"
        onClose={() => setMode(null)}
        onSubmit={submitProposal}
      >
        {mode === "counter" && (
          <div className="field">
            <label htmlFor="counter-dispute">이견 사유 · 필수</label>
            <Textarea id="counter-dispute" name="disputeReason" required />
          </div>
        )}
        <div className="field">
          <label>부담 방식</label>
          <Choice
            label="부담 방식"
            value={plan}
            values={PLANS}
            onChange={setPlan}
          />
        </div>
        {plan !== "비용 없음" && (
          <div className="field">
            <label htmlFor="burden-total">전체 금액 (원)</label>
            <MoneyInput
              id="burden-total"
              name="total"
              defaultValue={p?.amount || workTotal(r) || ""}
              required
            />
          </div>
        )}
        {plan === "제3자 포함" && (
          <div className="form-grid">
            <div className="field">
              <label htmlFor="third-label">제3자 설명</label>
              <Input
                id="third-label"
                name="thirdLabel"
                placeholder="예: 윗집 · 연락처는 적지 않아요"
                required
              />
            </div>
            <div className="field">
              <label htmlFor="third-amount">제3자 부담 (원)</label>
              <MoneyInput id="third-amount" name="thirdAmount" required />
            </div>
          </div>
        )}
        {(plan === "나눠서" || plan === "제3자 포함") && (
          <div className="field">
            <label htmlFor="tenant-amount">
              임차인 부담 (원) · 나머지는 임대인 부담
            </label>
            <MoneyInput
              id="tenant-amount"
              name="tenantAmount"
              defaultValue="0"
              required
            />
          </div>
        )}
        <div className="field">
          <label htmlFor="burden-reason">제안 사유 · 선택</label>
          <Textarea id="burden-reason" name="reason" />
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "change-dispute"}
        title="바꾸자는 안에 이견"
        description="이견을 남기면 바꾸자는 안은 이력으로 닫히고 지금 합의가 그대로 유지돼요."
        submitLabel="이견 남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) =>
          run(`바꾸자는 안 이견 · ${String(f.get("reason") || "")}`, (x) =>
            answerChangeProposal(x, role, false, String(f.get("reason") || "")),
          )
        }
      >
        <div className="field">
          <label htmlFor="change-dispute">이견 사유 · 필수</label>
          <Textarea id="change-dispute" name="reason" required />
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "disagree"}
        title="동의 안 해요"
        description={p ? `${p.by} 제안: ${proposalText(p)}` : undefined}
        submitLabel="보내기"
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const reason = String(f.get("disagreeReason") || "")
          if (!reason.trim()) return "이유를 입력해 주세요."
          if (!withCounter)
            return run(`부담 제안 이견 · ${reason}`, (x) =>
              disputeProposal(x, role, reason),
            )
          const l = lines(f)
          if (typeof l === "string") return l
          const preview = {
            lines: l,
            amount: l.reduce((s, x) => s + x.amount, 0),
            payer: "나눠서",
            status: "합의 대기",
            by: role,
          } as Proposal
          return run(
            `부담 이견 + 다른 안 · ${reason} · ${proposalText(preview)}`,
            (x) => makeProposal(x, role, l, "", { disputeReason: reason }),
          )
        }}
      >
        <div className="field">
          <label htmlFor="disagree-reason">이유 (필수)</label>
          <Textarea
            id="disagree-reason"
            name="disagreeReason"
            required
            placeholder="예: 출장비 영수증이 빠져 있어요."
          />
        </div>
        {!p?.totalChanged && (
          <label className="consent">
            <Checkbox
              checked={withCounter}
              onCheckedChange={(v) => setWithCounter(Boolean(v))}
            />
            다른 안 제안하기 (선택)
          </label>
        )}
        {withCounter && (
          <div className="svc-summary space-y-3">
            <div className="field">
              <label>부담 방식</label>
              <Choice
                label="부담 방식"
                value={plan}
                values={PLANS}
                onChange={setPlan}
              />
            </div>
            {plan !== "비용 없음" && (
              <div className="field">
                <label htmlFor="d-total">전체 금액 (원)</label>
                <MoneyInput
                  id="d-total"
                  name="total"
                  defaultValue={p?.amount || workTotal(r) || ""}
                  required
                />
              </div>
            )}
            {plan === "제3자 포함" && (
              <div className="form-grid">
                <div className="field">
                  <label htmlFor="d-third-label">제3자 설명</label>
                  <Input
                    id="d-third-label"
                    name="thirdLabel"
                    placeholder="예: 윗집 · 연락처는 적지 않아요"
                    required
                  />
                </div>
                <div className="field">
                  <label htmlFor="d-third-amount">제3자 부담 (원)</label>
                  <MoneyInput id="d-third-amount" name="thirdAmount" required />
                </div>
              </div>
            )}
            {(plan === "나눠서" || plan === "제3자 포함") && (
              <div className="field">
                <label htmlFor="d-tenant-amount">
                  임차인 부담 (원) · 나머지는 임대인 부담
                </label>
                <MoneyInput
                  id="d-tenant-amount"
                  name="tenantAmount"
                  defaultValue="0"
                  required
                />
              </div>
            )}
          </div>
        )}
        <p className="muted">
          {withCounter
            ? "이견과 함께 다른 안을 보내요. 상대가 그 안에 동의해야 합의돼요."
            : "체크를 끄면 이유만 보내요."}
        </p>
      </ActionDialog>
      <ActionDialog
        open={mode === "dispute"}
        title="부담 제안에 이견"
        description="사유는 필수예요. 다른 안 없이 이견만 남기면 돈 상태는 ‘이견’이 되고, 누구든 새 제안을 낼 수 있어요."
        submitLabel="이견 남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) =>
          run(`부담 제안 이견 · ${String(f.get("reason") || "")}`, (x) =>
            disputeProposal(x, role, String(f.get("reason") || "")),
          )
        }
      >
        <div className="field">
          <label htmlFor="money-dispute">이견 사유 · 필수</label>
          <Textarea id="money-dispute" name="reason" required />
        </div>
      </ActionDialog>
    </Panel>
  )
}

const BASES = [
  "내 추측",
  "직접 봤어요",
  "업체·전문가 소견",
  "다른 사람에게 들었어요",
]
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
        <strong>{c.by}의 설명</strong>
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
          title={`${c.by} 원인 설명 근거`}
          photos={c.photos}
          layout="compact"
        />
      ) : null}
      {answers
        .filter((x) => !x.agree)
        .map((x) => (
          <p key={x.by} className="fix-note">
            {x.by} 이견 사유: {x.reason}
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
                {x.by} · {x.agree ? "동의" : `이견 · ${x.reason}`} · {x.at}
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
  onAction,
}: {
  repair: Repair
  role: Role
  readOnly: boolean
  onAction: OnAction
}) {
  const [mode, setMode] = useState<"write" | "dispute" | null>(null)
  const [claimId, setClaimId] = useState("")
  const [basis, setBasis] = useState("내 추측")
  const [photos, setPhotos] = useState<string[]>([])
  const [error, setError] = useState("")
  const active = (repair.causes || []).filter((c) => !c.supersededBy)
  const past = (repair.causes || []).filter((c) => c.supersededBy)
  const mine = active.find((c) => c.by === role)
  const run = (text: string, update: (x: Repair) => void) => {
    const e = tryOn(repair, update)
    if (e) {
      setError(e)
      return e
    }
    setError("")
    onAction(text, update)
  }
  function respond(c: CauseClaim, agree: boolean, reason = "") {
    return run(
      `원인 설명 ${agree ? "동의" : `이견 · ${reason}`} · ${c.by}의 설명`,
      (x) => {
        const claim = x.causes!.find((v) => v.id === c.id)!
        if (claim.by === role) throw new Error("내 설명에는 응답할 수 없어요.")
        if (!agree && !reason.trim())
          throw new Error("이견 사유를 입력해 주세요.")
        const last = latestResponses(claim).find((v) => v.by === role)
        if (last && last.agree === agree && agree)
          throw new Error("이미 동의했어요.")
        claim.responses.push({
          by: role,
          agree,
          reason: reason.trim(),
          at: nowText(),
        })
      },
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
      basis === "다른 사람에게 들었어요"
        ? String(f.get("source") || "").trim() || undefined
        : undefined
    if (mine && mine.basis === basis)
      return run(`원인 설명 표현 정정 · 근거 ${basis}`, (x) => {
        const c = x.causes!.find((v) => v.id === mine.id)!
        c.edits = [...(c.edits || []), { text: c.text, at: c.at }]
        c.text = text
        c.company = company
        c.source = source
        c.photos = [...(c.photos || []), ...photos]
        c.at = nowText()
      })
    const id = uid()
    return run(
      `원인 설명${mine ? ` · 근거 변경(${mine.basis} → ${basis}) · 새 설명` : ""} · 근거 ${basis}`,
      (x) => {
        if (mine) x.causes!.find((v) => v.id === mine.id)!.supersededBy = id
        x.causes = [
          ...(x.causes || []),
          {
            id,
            by: role,
            basis,
            company,
            source,
            photos: [...photos],
            text,
            at: nowText(),
            responses: [],
            supersedes: mine?.id,
          },
        ]
      },
    )
  }
  return (
    <Panel title="각 쪽의 원인 설명">
      <p className="muted text-sm mb-3">
        각자의 설명과 근거만 남겨요. HomeStamp는 원인을 판정하지 않고, 수리
        상태·부담 합의·하자 인정도 바꾸지 않아요.
      </p>
      {active.map((c) => (
        <ClaimView key={c.id} c={c}>
          {!readOnly && c.by !== role && (
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
            ? "같은 근거로 문장만 고치면 표현 정정이에요. 근거 종류를 바꾸면 새 설명으로 남고, 상대 동의는 새 설명에 다시 받아요."
            : "측마다 하나씩 남겨요. 근거 종류는 필수예요."
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
        {basis === "다른 사람에게 들었어요" && (
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
