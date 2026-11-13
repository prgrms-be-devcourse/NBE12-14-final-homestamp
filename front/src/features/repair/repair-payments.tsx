import { useEffect, useState } from "react"
import { ArrowRight, Plus, Wallet } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice, Panel, Status } from "@/components/prototype"
import { ActionDialog } from "@/components/action-dialog"
import { tryOn } from "@/lib/try-on"
import { CorrectionRow } from "@/features/repair/repair-records"
import { MoneyInput } from "@/components/money-input"
import { RespondButtons } from "@/components/common/respond-buttons"
import { SupplementRequest } from "@/components/common/supplement-request"
import { PhotoGallery } from "@/components/photo-gallery"
import { MoneyLeftNotice } from "@/features/repair/money-left"
import { hasAction, type PaymentAction } from "@/api/actions"
import {
  uid,
  won,
  type Payment,
  type Repair,
  type Role,
  type Room,
} from "@/data/demo"
import { parseMoney } from "@/lib/money"
import { readPhotos } from "@/lib/photos"
import { assertSeenVersion, versionOf } from "@/lib/versions"
import {
  answerCorrection,
  clearWait,
  duplicateCheck,
  openPaymentDispute,
  paymentStamped,
  reaskWaitDays,
  respondPayment,
  supplementPayment,
  recheckAgreement,
  recordDateText,
  requestCorrection,
  sideLabel,
  withdrawCorrection,
  workTotal,
} from "@/lib/repair-rules"
import { sideText } from "@/lib/words"
import { todayKey } from "@/lib/workflows"

type OnAction = (text: string, update: (r: Repair) => void) => void
type Payer = NonNullable<Payment["payer"]>
type Payee = NonNullable<Payment["payee"]>
// 확인 강도(상태와 규칙 F): 한쪽 기록 → 증빙 있음 → 양측 확인
const strength = (p: Payment) =>
  p.confirmed
    ? "둘 다 확인"
    : p.photos?.length || (p.evidence && p.evidence !== "없음")
      ? "증빙 있음"
      : "한쪽 기록"
// 증빙 종류(영수증 등)는 선택이에요. 고르지 않으면 저장하지 않아요. 증빙 사진은 1장 이상 필수예요.
const NO_KIND = "선택 안 함"
const EVIDENCE_KINDS = [NO_KIND, "영수증", "이체 확인증", "견적서", "기타"]
// 화면에 보이는 이름: 세입자·집주인(5-0 사용자 말). 저장 값은 임차인·임대인 그대로.
const NAME: Record<string, string> = { 임차인: "세입자", 임대인: "집주인" }
const who = (side: Payer | Payee, label?: string) =>
  NAME[side] ?? sideLabel(side, label)
const fromName = (v: string) =>
  Object.keys(NAME).find((k) => NAME[k] === v) ?? v
// 누가 → 누구에게의 이름 꼬리표
const flowName = "rounded-full bg-tag px-2.5 py-0.5 [overflow-wrap:anywhere]"

// 실제 지불 기록(요구사항명세 S-13, 상태와 규칙 F). 지불 사실은 부담 합의와 별개이며, 실제 송금은 하지 않아요.
export function RepairPayments({
  r,
  room,
  role,
  readOnly,
  onAction,
  actionsOf,
  prefill,
  onPrefillUsed,
}: {
  r: Repair
  room: Room
  role: Role
  readOnly: boolean
  onAction: OnAction
  // 지불마다 서버가 준 지금 누를 수 있는 행동(availableActions)
  actionsOf: (p: Payment) => readonly PaymentAction[]
  prefill?: { from: Role; to: Role; amount: number }
  onPrefillUsed?: () => void
}) {
  const [mode, setMode] = useState<"add" | "fix" | "reject" | null>(null)
  const [target, setTarget] = useState("")
  const [payer, setPayer] = useState<Payer>(role)
  const [payee, setPayee] = useState<Payee>("업체")
  const [amountDefault, setAmountDefault] = useState("")
  const [evidence, setEvidence] = useState(NO_KIND)
  const [photos, setPhotos] = useState<string[]>([])
  const [dupAsked, setDupAsked] = useState("")
  // 같은 돈(상대가 남긴 확인 전 기록)이 있으면 새로 만들지 않고 그 기록을 보여 줘요(S-13 '같은 돈 같아요').
  const [dupId, setDupId] = useState("")
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
  const pay = (x: Repair, id: string) => x.payments!.find((v) => v.id === id)!
  const list = r.payments || []
  // 고치려는 지불이 이미 상대 도장(확인·이견)을 받았는지(상태와 규칙 G)
  const fixTarget = list.find((p) => p.id === target)
  const fixStamped = fixTarget ? paymentStamped(fixTarget) : true
  const confirmAmount = (p: Payment) =>
    run(
      `지불 금액 확인 · ${won(p.amount)} · 본 모습 ${versionOf(p)}번`,
      (x) => {
        assertSeenVersion(pay(x, p.id), versionOf(p))
        pay(x, p.id).confirmed = true
        clearWait(x, `pay:${p.id}`)
        recheckAgreement(x)
      },
    )
  function openAdd(pr: Payer = role, pe: Payee = "업체", amount = "") {
    setPayer(pr)
    setPayee(pe)
    setAmountDefault(amount)
    setEvidence(NO_KIND)
    setPhotos([])
    setDupAsked("")
    setDupId("")
    setError("")
    setMode("add")
  }
  // 홈의 '돌려줬다면 기록하기'에서 넘어오면 같은 지불 기록 창을 미리 채워 엽니다.
  // 새 미리 채움 값이 오면 그리는 중에 창을 열고(effect 안 setState 대신), 다 쓴 뒤 부모에게 알려요.
  const [usedPrefill, setUsedPrefill] = useState<typeof prefill>()
  if (prefill && !readOnly && prefill !== usedPrefill) {
    setUsedPrefill(prefill)
    openAdd(prefill.from, prefill.to, String(prefill.amount))
  }
  useEffect(() => {
    if (prefill && prefill === usedPrefill) onPrefillUsed?.()
  }, [prefill, usedPrefill, onPrefillUsed])
  function add(f: FormData) {
    const amount = parseMoney(String(f.get("amount") || ""))
    if (!Number.isSafeInteger(amount) || amount <= 0)
      return "금액을 1원 이상으로 입력해 주세요."
    if (payer === payee) return "낸 사람과 받는 사람이 같아요."
    const paidOn = String(f.get("paidOn") || "")
    if (!paidOn) return "낸 날짜를 골라 주세요."
    if (paidOn > todayKey()) return "낸 날짜는 오늘이나 그 전이어야 해요."
    if (!photos.length)
      return "증빙 사진을 1장 이상 올려 주세요. 영수증이 없으면 이체 확인증·견적서 등 낸 사실을 보여 주는 사진이면 돼요."
    const { waiting, recent } = duplicateCheck(r, role, payer, payee, amount)
    if (waiting) {
      setDupId(waiting.id)
      return "같은 돈이 이미 기록돼 있어요."
    }
    const key = `${payer}|${payee}|${amount}`
    if (recent && dupAsked !== key) {
      setDupAsked(key)
      return `비슷한 기록이 30일 안에 있어요(${recordDateText(recent.paidOn ?? recent.at)} · ${won(amount)}). 다른 돈이 맞다면 [기록 남기기]를 한 번 더 눌러 주세요.`
    }
    const record: Payment = {
      id: uid(),
      by: role,
      payer,
      payee,
      amount,
      confirmed: false,
      at: new Date().toISOString(),
      paidOn,
      evidence: evidence === NO_KIND ? undefined : evidence,
      photos: [...photos],
      payerLabel:
        payer === "제3자"
          ? String(f.get("payerLabel") || "").trim() || undefined
          : undefined,
      payeeLabel:
        payee === "제3자" || payee === "업체"
          ? String(f.get("payeeLabel") || "").trim() || undefined
          : undefined,
    }
    const probe = structuredClone(r)
    probe.payments = [...(probe.payments || []), record]
    const changed = recheckAgreement(probe)
    return run(
      `지불 기록 · ${who(payer, record.payerLabel)} → ${who(payee, record.payeeLabel)} ${won(amount)} · ${strength(record)}${changed ? " · 지불 합계가 바뀌어 부담 합의가 합의 대기로" : ""}`,
      (x) => {
        x.payments = [...(x.payments || []), record]
        recheckAgreement(x)
      },
    )
  }
  const dup = list.find((p) => p.id === dupId)

  return (
    <Panel title="지불 기록">
      <div className="mb-4">
        <MoneyLeftNotice
          r={r}
          role={role}
          readOnly={readOnly}
          onRecord={(from, to, amount) => openAdd(from, to, String(amount))}
        />
      </div>
      {!list.length ? (
        <div className="flex flex-col items-center gap-2.5 rounded-[14px] border border-dashed border-line px-4 py-6 text-center">
          <span
            className="grid size-11 place-items-center rounded-[12px] bg-brand-soft text-brand"
            aria-hidden
          >
            <Wallet size={22} />
          </span>
          <p className="text-[15px] font-semibold text-ink">
            누가 얼마를 냈는지 남겨 두면 정산이 쉬워져요
          </p>
          {!readOnly && (
            <Button onClick={() => openAdd()}>
              <Plus />
              지불 기록
            </Button>
          )}
        </div>
      ) : (
        <>
          <div className="flex flex-col gap-2.5">
            {list.map((p) => {
              const stage = r.waits?.[`pay:${p.id}`]
              const pending = p.corrections?.find(
                (c) => c.status === "확인 대기",
              )
              const theirs = p.by !== role && !p.confirmed
              // 지금 열려 있는 보류·이견(보충하고 다시 요청하면 지난 것이 돼요, MONEY-2)
              const open = openPaymentDispute(p)
              const acts = actionsOf(p)
              // 지불 카드 한 장. 상대가 남겨서 내가 확인할 기록이면 주황 테두리와 왼쪽 띠로 강조해요.
              return (
                <div
                  className={`flex min-w-0 flex-col gap-1.5 rounded-[14px] border p-3.5 ${theirs ? "border-turn-line bg-turn-bg shadow-[inset_3px_0_0_var(--color-turn)]" : "border-line bg-white"}`}
                  key={p.id}
                >
                  <div className="flex items-center justify-between gap-2">
                    <strong className="text-xl font-extrabold tracking-[-.3px] text-ink tabular-nums">
                      {won(p.amount)}
                    </strong>
                    <Status>{strength(p)}</Status>
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5 text-[15px] font-[650] text-ink">
                    <span className={flowName}>
                      {who(p.payer ?? p.by, p.payerLabel)}
                    </span>
                    <ArrowRight
                      size={16}
                      className="flex-none text-[#8e9bb0]"
                      aria-label="에게"
                    />
                    <span className={flowName}>
                      {who(p.payee ?? "업체", p.payeeLabel)}
                    </span>
                  </div>
                  <small className="text-[13px] text-sub [overflow-wrap:anywhere]">
                    {p.paidOn
                      ? `${p.paidOn} 지불`
                      : p.at
                        ? `${recordDateText(p.at)} 기록`
                        : ""}{" "}
                    · {sideText(p.by)}이 기록
                    {p.evidence && p.evidence !== "없음"
                      ? ` · 증빙: ${p.evidence}`
                      : ""}
                  </small>
                  {stage || open ? (
                    <div className="badges">
                      {stage && (
                        <Status>
                          {stage === "미확인" ? "확인 없음" : "다시 알렸어요"}
                        </Status>
                      )}
                      {open ? (
                        <Status>
                          {open.kind === "보류" ? "보충 요청됨" : "이견"}
                        </Status>
                      ) : null}
                    </div>
                  ) : null}
                  {p.photos?.length ? (
                    <PhotoGallery
                      title={`${won(p.amount)} 지불 증빙`}
                      photos={p.photos}
                      layout="compact"
                    />
                  ) : null}
                  {p.disputes?.map((d, i) => (
                    <div
                      className={`dispute-callout${d.closed ? " opacity-60" : ""}`}
                      key={i}
                    >
                      {sideText(d.by)} ·{" "}
                      {d.kind === "보류"
                        ? "보충 요청됨 · 더 필요한 게 있어요"
                        : "이견 · 금액이 달라요"}{" "}
                      · {d.at}
                      {d.closed ? " · 보충하고 다시 요청함" : ""}
                      <p>{d.reason}</p>
                    </div>
                  ))}
                  {p.corrections?.map((c) => (
                    <CorrectionRow
                      key={c.id}
                      c={c}
                      stage={r.waits?.[`fix:${c.id}`]}
                      format={(v) => won(Number(v))}
                    />
                  ))}
                  {theirs && !readOnly && (
                    <p className="text-sm text-warn-ink">
                      상대가 남긴 기록이에요 · <strong>확인 전</strong>
                    </p>
                  )}
                  {hasAction(acts, "respond") && (
                    <RespondButtons
                      size="sm"
                      answered={
                        open?.kind === "보류"
                          ? "NEED_MORE"
                          : open
                            ? "DISPUTE"
                            : undefined
                      }
                      note="확인해야 수리비 나누기에 들어가요."
                      onRespond={(answer, reason) =>
                        answer === "ACK"
                          ? confirmAmount(p)
                          : run(
                              `지불 금액 ${answer === "NEED_MORE" ? "보충 요청" : "이견"} · ${reason}`,
                              (x) =>
                                respondPayment(
                                  pay(x, p.id),
                                  role,
                                  answer === "NEED_MORE" ? "보류" : "이견",
                                  reason,
                                ),
                            )
                      }
                    />
                  )}
                  {hasAction(acts, "supplement") && (
                    <SupplementRequest
                      waitDays={reaskWaitDays(p.reaskedAt)}
                      onSupplement={(data) =>
                        run(
                          `지불 보충하고 다시 확인 요청${data.text ? ` · ${data.text}` : ""}${data.photos.length ? ` · 사진 ${data.photos.length}장` : ""}`,
                          (x) => supplementPayment(pay(x, p.id), role, data),
                        )
                      }
                    />
                  )}
                  {!readOnly && (
                    <div className="actions">
                      {hasAction(acts, "corrections") && !pending && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setTarget(p.id)
                            setMode("fix")
                          }}
                        >
                          금액 고치기
                        </Button>
                      )}
                      {pending && pending.by !== role && (
                        <>
                          <Button
                            size="sm"
                            onClick={() =>
                              run(
                                `지불 금액 고친 내용 확인 · ${won(Number(pending.to))}`,
                                (x) => {
                                  answerCorrection(x, role, pending.id, true)
                                },
                              )
                            }
                          >
                            고친 금액 확인
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => {
                              setTarget(pending.id)
                              setMode("reject")
                            }}
                          >
                            거절
                          </Button>
                        </>
                      )}
                      {pending && pending.by === role && (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            run("지불 금액 고친 내용 거둠", (x) =>
                              withdrawCorrection(x, role, pending.id),
                            )
                          }
                        >
                          거두기
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
          {/* S-13 '부담 미정: 전체 금액(지불 합계)' */}
          <p className="my-3 flex items-center justify-between gap-2 rounded-[12px] bg-soft px-3.5 py-3 text-sm text-sub">
            <span>지불 합계 · 업체·제3자에게 낸 돈</span>
            <strong className="text-[17px] text-ink tabular-nums">
              {won(workTotal(r))}
            </strong>
          </p>
          {!readOnly && (
            <Button variant="outline" onClick={() => openAdd()}>
              <Plus />
              지불 기록
            </Button>
          )}
        </>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}

      <ActionDialog
        open={mode === "add"}
        title="지불 기록"
        description="누가 누구에게 얼마를 냈는지 남겨요. 실제 송금은 하지 않아요."
        onClose={() => {
          setDupId("")
          setMode(null)
        }}
        onSubmit={add}
      >
        <div className="form-grid">
          <div className="field">
            <label>낸 사람</label>
            <Choice
              label="낸 사람"
              value={NAME[payer] ?? payer}
              values={["세입자", "집주인", "제3자"]}
              onChange={(v) => {
                setPayer(fromName(v) as Payer)
                setDupAsked("")
                setDupId("")
              }}
            />
          </div>
          <div className="field">
            <label>받는 사람</label>
            <Choice
              label="받는 사람"
              value={NAME[payee] ?? payee}
              values={["업체", "세입자", "집주인", "제3자"]}
              onChange={(v) => {
                setPayee(fromName(v) as Payee)
                setDupAsked("")
                setDupId("")
              }}
            />
          </div>
        </div>
        {payer === "제3자" && (
          <div className="field">
            <label htmlFor="payer-label">누구인가요 · 관계만</label>
            <Input id="payer-label" name="payerLabel" placeholder="예: 윗집" />
            <small className="muted">연락처는 적지 않아요.</small>
          </div>
        )}
        {(payee === "제3자" || payee === "업체") && (
          <div className="field">
            <label htmlFor="payee-label">
              {payee === "업체" ? "업체 이름 · 선택" : "누구인가요 · 관계만"}
            </label>
            <Input
              id="payee-label"
              name="payeeLabel"
              placeholder={payee === "업체" ? "예: ○○설비" : "예: 관리사무소"}
            />
          </div>
        )}
        <div className="form-grid">
          <div className="field">
            <label htmlFor="pay-amount">금액 (원)</label>
            <MoneyInput
              id="pay-amount"
              name="amount"
              defaultValue={amountDefault}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="pay-date">낸 날짜</label>
            <Input
              id="pay-date"
              name="paidOn"
              type="date"
              defaultValue={todayKey()}
              max={todayKey()}
              required
            />
          </div>
        </div>
        <div className="field">
          <label>증빙 종류 · 선택 (영수증 없어도 돼요)</label>
          <Choice
            label="증빙 종류"
            value={evidence}
            values={EVIDENCE_KINDS}
            onChange={setEvidence}
          />
        </div>
        <div className="field">
          <label htmlFor="pay-photos">증빙 사진 · 필수 (1장 이상)</label>
          <Input
            id="pay-photos"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={(e) =>
              readPhotos(e.target.files, photos.length)
                .then((p) => {
                  setPhotos((old) => [...old, ...p])
                  setError("")
                })
                .catch((err) => setError(err.message))
            }
          />
          {photos.length > 0 ? (
            <small className="muted">{photos.length}장 선택됨</small>
          ) : (
            <small className="muted">
              영수증, 이체 확인증, 견적서 등 낸 사실을 보여 주는 사진을 올려
              주세요.
            </small>
          )}
          <small className="muted">
            계약서·신분증·통장 사진은 올리지 마세요.
          </small>
        </div>
        <div className="flex flex-wrap items-center gap-1.5 rounded-[12px] bg-brand-soft px-3 py-2.5 text-sm font-[650] text-brand-ink">
          <span>{who(payer)}</span>
          <ArrowRight size={16} className="flex-none" aria-hidden />
          <span>{who(payee)}</span>
          <small className="basis-full font-normal text-sub">
            상대가 확인하면 ‘둘 다 확인’이 돼요
          </small>
        </div>
        {dup && (
          <div className="flex flex-col items-start gap-1.5 rounded-[12px] border border-warn-line bg-warn-soft p-3 text-sm">
            <strong className="text-warn-ink">
              같은 돈이 이미 기록돼 있어요
            </strong>
            <p>
              {won(dup.amount)} · {who(dup.payer ?? dup.by, dup.payerLabel)} →{" "}
              {who(dup.payee ?? "업체", dup.payeeLabel)} · {sideText(dup.by)}이
              기록
            </p>
            <Button
              type="button"
              size="sm"
              onClick={() => {
                const e = confirmAmount(dup)
                if (!e) {
                  setDupId("")
                  setMode(null)
                }
              }}
            >
              그 기록 확인하기
            </Button>
          </div>
        )}
      </ActionDialog>
      <ActionDialog
        open={mode === "fix"}
        title="금액 고치기"
        description={
          fixStamped
            ? "고친 금액은 상대가 확인해야 바뀌어요. 그전까지는 지금 금액이 그대로 쓰여요."
            : "상대가 아직 확인하기 전이라 바로 고쳐져요. 고친 이력은 남아요."
        }
        submitLabel={fixStamped ? "확인 요청" : "고치기"}
        onClose={() => setMode(null)}
        onSubmit={(f) => {
          const to = parseMoney(String(f.get("amount") || ""))
          if (!Number.isSafeInteger(to) || to <= 0)
            return "고친 금액을 입력해 주세요."
          const from = list.find((p) => p.id === target)?.amount ?? 0
          return run(
            fixStamped
              ? `지불 금액 고친 내용 남김 · ${won(from)} → ${won(to)}`
              : `지불 금액 고쳤어요 · ${won(from)} → ${won(to)}`,
            (x) => {
              requestCorrection(
                x,
                room,
                role,
                "금액",
                from,
                to,
                String(f.get("reason") || ""),
                pay(x, target),
              )
            },
          )
        }}
      >
        <div className="field">
          <label htmlFor="fix-amount">고친 금액 (원)</label>
          <MoneyInput id="fix-amount" name="amount" required />
        </div>
        <div className="field">
          <label htmlFor="fix-pay-reason">
            왜 바꾸나요{fixStamped ? " · 필수" : " · 선택"}
          </label>
          <Textarea id="fix-pay-reason" name="reason" required={fixStamped} />
        </div>
      </ActionDialog>
      <ActionDialog
        open={mode === "reject"}
        title="고친 금액 거절"
        description="거절하면 지금 금액은 그대로 두고, 고친 내용과 거절 사유가 나란히 남아요."
        submitLabel="거절 남기기"
        onClose={() => setMode(null)}
        onSubmit={(f) =>
          run("지불 금액 고친 내용 거절", (x) =>
            answerCorrection(
              x,
              role,
              target,
              false,
              String(f.get("reason") || ""),
            ),
          )
        }
      >
        <div className="field">
          <label htmlFor="pay-reject">거절 사유 · 필수</label>
          <Textarea id="pay-reject" name="reason" required />
        </div>
      </ActionDialog>
    </Panel>
  )
}
