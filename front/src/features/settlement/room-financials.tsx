import {
  post,
  proposalIdOf,
  settlementIdIn,
  transferDirection as transferDirectionOf,
  type RecordRequest,
} from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { useState, type FormEvent, type ReactNode } from "react"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog"
import { MoneyInput } from "@/components/common/money-input"
import { Panel, Status, Choice } from "@/components/common/blocks"
import { CountChip } from "@/components/common/fold"
import { MoreMenu } from "@/components/common/more-menu"
import {
  CurrentProposal,
  PastProposal,
  ProposalDispute,
} from "@/components/common/money-proposal"
import { proposalHead } from "@/lib/words"
import { cn } from "cn"
import { parseMoney } from "@/lib/money"
import {
  newAmountOffer,
  respondToOffer,
  roomTransfers,
  todayKey,
} from "@/lib/workflows"
import { RespondButtons } from "@/components/common/respond-buttons"
import { Input } from "@/components/ui/input"
import {
  confirmedTransferTotal,
  confirmFinish,
  FINISH_CHANGED_MESSAGE,
  finishAlone,
  finishState,
  pendingTransferTotal,
  receiverOf,
  rejectFinish,
  requestFinish,
} from "@/lib/settlement-rules"
import {
  hasResidentId,
  RESIDENT_ID_MESSAGE,
  settlementTotal,
} from "@/lib/service-rules"
import {
  uid,
  won,
  other,
  type Room,
  type Role,
  type Settlement,
  type DemoState,
  type AmountOffer,
  type Transfer,
  type SettlementServerState,
} from "@/data/demo"
import { sideName, sideText } from "@/lib/words"
import {
  hasAction,
  type SettlementAction,
  type SettlementItemAction,
  type TransferAction,
} from "@/api/actions"

interface Props {
  room: Room
  role: Role
  items: Settlement[]
  // request는 06 4장의 주소(api/records), text·update는 목업이 서버 대신 쓰는 값이에요
  onAction: (
    request: RecordRequest,
    text: string,
    update: (draft: DemoState) => void,
  ) => void
  onAdd: () => void
  candidates?: ReactNode
  // 서버가 준 지금 누를 수 있는 행동(availableActions): 정산 전체 · 항목마다 · 송금마다
  actions: readonly SettlementAction[]
  itemActionsOf: (s: Settlement) => readonly SettlementItemAction[]
  transferActionsOf: (t: Transfer) => readonly TransferAction[]
  // HTTP: 서버가 준 정산 머리(room.settlement). 있으면 보증금 상태·끝내기는 서버 값으로 보이고,
  // 끝내기 행동은 화면이 규칙을 미리 검사하지 않아요(서버가 검사). 목업에는 없어요
  server?: SettlementServerState
}
// 보증금 확인 상태의 화면 말(08 10장 "보증금 카드": "제안·합의" 대신 "적기·확인", SET-1b)
const DEPOSIT_TEXT: Record<SettlementServerState["depositStatus"], string> = {
  NOT_ENTERED: "보증금 금액을 아직 적지 않았어요",
  PENDING: "보증금 확인 전",
  AGREED: "보증금 · 둘 다 확인 ✓",
  DISPUTE: "보증금 금액이 서로 달라요",
}
type Mode =
  | "deposit"
  | "deposit-dispute"
  | "item"
  | "item-counter"
  | "exclude"
  | "exclude-reject"
  | "transfer"
  | "finish-not-yet"
  | null
function offerHistory(offers: AmountOffer[]) {
  return offers.map((o) => (
    <PastProposal
      key={o.id}
      head={proposalHead(
        "이전 제안",
        o.round,
        sideName(o.by),
        won(o.amount),
        o.status,
      )}
    >
      <p>{o.reason}</p>
      {o.disputeReason && (
        <p>
          {o.disputeBy} 이견: {o.disputeReason}
        </p>
      )}
    </PastProposal>
  ))
}
export function RoomFinancials({
  room,
  role,
  items: allItems,
  onAction,
  onAdd,
  candidates,
  actions,
  itemActionsOf,
  transferActionsOf,
  server,
}: Props) {
  const has = (a: SettlementAction) => hasAction(actions, a)
  // 빼기 제안에 상대가 동의한 항목은 정산 계산과 세 칸에서 빠지고, 아래 '뺀 항목'에 보존됩니다.
  const items = allItems.filter((s) => !s.excluded && !s.withdrawn)
  const excludedItems = allItems.filter((s) => s.excluded)
  const withdrawnItems = allItems.filter((s) => s.withdrawn)
  const [mode, setMode] = useState<Mode>(null)
  const [itemId, setItemId] = useState("")
  const [direction, setDirection] = useState("차감")
  const [transferDirection, setTransferDirection] = useState("반환")
  const [error, setError] = useState("")
  const offer = room.depositOffer
  const item = allItems.find((s) => s.id === itemId)
  const total = settlementTotal(room.deposit ?? 0, items)
  // 송금 합계는 상대가 확인한 송금만(SET-4b, 03 J, 08 10장). 확인 전 송금은 따로 보여요.
  // 서버 정산이면 합계는 서버가 센 값이고(같은 송금으로 묶인 건 한 번), 확인 중은 아직 답이 없는 기록만 더해요
  const paid = server
    ? server.confirmedRefund - server.confirmedExtra
    : confirmedTransferTotal(room)
  const pendingPaid = server
    ? server.transfers
        .filter((t) => t.server?.response === "PENDING")
        .reduce(
          (s, t) => s + (t.direction === "반환" ? t.amount : -t.amount),
          0,
        )
    : pendingTransferTotal(room)
  // 같은 송금으로 묶어 남긴 받은 금액(SET-4e)은 묶인 보낸 쪽 기록 아래에 함께 보여요
  const linkedTo = (t: Transfer) =>
    server?.transfers.find((v) => v.server?.sameAsTransferId === t.id)
  // 받은 금액을 묶어 적을 송금(받은 쪽에서 [받았어요(혼자)])
  const [sameAs, setSameAs] = useState<Transfer>()
  // 서버 송금 한 줄의 상태(03 J, 08 10장). 답하는 쪽은 기록하지 않은 측이에요
  const transferStatus = (t: Transfer) => {
    const answerer = sideName(other(t.by))
    switch (t.server?.response) {
      case "ACK":
        return t.server.byReceiver
          ? "둘 다 확인"
          : `${sideName(receiverOf(t.direction))} 받았어요`
      case "NEED_MORE":
        return `${answerer}: 더 필요해요`
      case "DISPUTE":
        return `${answerer}: 금액이 달라요`
      default:
        // 보낸 쪽 기록은 받은 쪽이, [받았어요(혼자)]는 보낸 쪽이 확인해요
        return t.server?.byReceiver
          ? `${answerer} 확인 기다림`
          : "받았는지 확인 중"
    }
  }
  // 송금 응답(06 4-8 respond): 본 판 번호를 함께 보내요. 보낸 쪽 기록에는 받은 쪽이 [받았어요]로 답해요(08 10장)
  const transferRespond = (t: Transfer) =>
    hasAction(transferActionsOf(t), "respond") &&
    t.server && (
      <RespondButtons
        size="sm"
        ackLabel={t.server.byReceiver ? "맞아요" : "받았어요"}
        answered={
          t.server.response === "NEED_MORE" || t.server.response === "DISPUTE"
            ? t.server.response
            : undefined
        }
        onRespond={(answer, reason) =>
          onAction(
            post(endpoints.transferRespond(t.id), {
              answer,
              ...(reason ? { reason } : {}),
              seenVersionNo: t.server!.versionNo,
            }),
            `${t.direction} ${won(t.amount)} · 송금 응답`,
            () => {},
          )
        }
      />
    )
  // 상대가 남긴 보낸 기록에 더 필요해요·금액이 달라요로 답한 받은 쪽은 실제 받은 금액을 같은 송금으로 묶어 적어요(SET-4e)
  const canWriteReceived = (t: Transfer) =>
    !t.server?.byReceiver &&
    receiverOf(t.direction) === role &&
    (t.server?.response === "NEED_MORE" || t.server?.response === "DISPUTE") &&
    !linkedTo(t)
  const finish = finishState(room, allItems)
  const [finishError, setFinishError] = useState("")
  // 정산 끝내기 행동: 먼저 복사본에 적용해 규칙 위반을 확인한 뒤 기록해요.
  // 서버 정산이면 미리 검사하지 않아요(화면 모델에 끝내기 요청 칸이 없고, 서버가 같은 규칙으로 거절해요)
  const sid = server?.id ?? settlementIdIn(room)
  const runFinish = (
    request: RecordRequest,
    text: string,
    update: (r: Room, list: Settlement[]) => void,
  ) => {
    if (!server)
      try {
        update(structuredClone(room), structuredClone(allItems))
      } catch (e) {
        setFinishError(e instanceof Error ? e.message : "처리할 수 없어요.")
        return
      }
    setFinishError("")
    onAction(request, text, (d) =>
      update(
        d.rooms.find((r) => r.id === room.id)!,
        d.settlements.filter((s) => s.roomId === room.id),
      ),
    )
  }
  const transfers = server
    ? server.transfers.filter((t) => !t.server?.sameAsTransferId)
    : roomTransfers(room)
  const depositAgreed = server
    ? server.depositStatus === "AGREED"
    : room.depositConfirmed || offer?.status === "합의됨"
  const myConfirmed =
    room.depositConfirmed || offer?.by === role || offer?.status === "합의됨"
  const otherConfirmed =
    room.depositConfirmed ||
    offer?.by === other(role) ||
    offer?.status === "합의됨"
  function open(next: Mode, s?: Settlement) {
    setMode(next)
    setError("")
    setItemId(s?.id || "")
    setDirection(s?.direction || "차감")
    setTransferDirection(total < 0 ? "추가 송금" : "반환")
    setSameAs(undefined)
  }
  // 상대 기록에 금액이 달라요를 단 뒤 실제 받은 금액을 같은 송금으로 묶어 적어요(SET-4e)
  function openReceived(t: Transfer) {
    open("transfer")
    setTransferDirection(t.direction)
    setSameAs(t)
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const f = new FormData(event.currentTarget)
    const reason = String(f.get("reason") || "").trim()
    if (hasResidentId(reason)) {
      setError(RESIDENT_ID_MESSAGE)
      return
    }
    const rawAmount = String(f.get("amount") || "").trim()
    const amount = parseMoney(rawAmount)
    // [아니에요 · 다른 안]·[아니에요 · 다른 금액]의 금액은 선택이에요(08 7장·10장)
    const optionalAmount = mode === "item-counter" || mode === "deposit-dispute"
    if (
      (["deposit", "item", "transfer"].includes(mode || "") ||
        (optionalAmount && rawAmount)) &&
      !Number.isFinite(amount)
    ) {
      setError("0 이상의 원화 정수를 입력해 주세요.")
      return
    }
    // 송금 기록은 1원 이상이고 보낸(받은) 날짜가 있어야 해요(06 4-8 transfers)
    const paidOn = String(f.get("paidOn") || "")
    if (mode === "transfer" && amount < 1) {
      setError("1원 이상 입력해 주세요.")
      return
    }
    if (mode === "transfer" && !paidOn) {
      setError("날짜를 골라 주세요.")
      return
    }
    if (mode !== "transfer" && !reason) {
      setError("제안 또는 이견 사유를 입력해 주세요.")
      return
    }
    if (mode === "deposit")
      onAction(
        post(endpoints.settlementAction(sid, "deposit-proposals"), {
          amount,
          reason,
        }),
        `보증금 금액 제안 · ${won(amount)} · ${reason}`,
        (d) => {
          const r = d.rooms.find((r) => r.id === room.id)!
          if (!r.depositOffer && r.depositConfirmed)
            r.depositOffer = {
              ...newAmountOffer(
                role,
                r.deposit ?? 0,
                "기존에 둘 다 확인한 금액",
              ),
              status: "합의됨",
            }
          if (r.depositOffer)
            r.depositHistory = [
              ...(r.depositHistory || []),
              structuredClone(r.depositOffer),
            ]
          r.depositOffer = newAmountOffer(role, amount, reason, r.depositOffer)
          r.depositConfirmed = false
          if (!r.depositHistory?.some((o) => o.status === "합의됨"))
            r.deposit = amount
        },
      )
    else if (mode === "deposit-dispute") {
      if (!offer || offer.by === role || offer.status !== "합의 대기") return
      onAction(
        rawAmount
          ? post(endpoints.proposalAction(offer.id, "counter"), {
              amount,
              reason,
            })
          : post(endpoints.proposalAction(offer.id, "dispute"), { reason }),
        `보증금 금액 이견 · ${reason}${rawAmount ? ` · 다른 금액 ${won(amount)}` : ""}`,
        (d) => {
          const r = d.rooms.find((r) => r.id === room.id)!
          respondToOffer(r.depositOffer!, role, false, reason)
          r.depositConfirmed = false
          // 다른 금액을 적었으면 그 금액이 내가 적은 보증금(새 안)이 돼요
          if (rawAmount) {
            r.depositHistory = [
              ...(r.depositHistory || []),
              structuredClone(r.depositOffer!),
            ]
            r.depositOffer = newAmountOffer(
              role,
              amount,
              reason,
              r.depositOffer,
            )
          }
        },
      )
    } else if (mode === "item")
      onAction(
        post(endpoints.settlementItemAction(itemId, "proposals"), {
          amount,
          direction,
          reason,
        }),
        `${item!.title} · 정산 다른 안 제안 ${won(amount)} · ${reason}`,
        (d) => {
          const s = d.settlements.find((s) => s.id === itemId)!
          const old = structuredClone(s)
          delete old.history
          s.history = [...(s.history || []), old]
          s.amount = amount
          s.direction = direction as Settlement["direction"]
          s.by = role
          s.status = "합의 대기"
          s.proposalReason = reason
          s.round = (s.round || 1) + 1
          s.disputeReason = undefined
          s.disputeBy = undefined
          s.autoChange = undefined
        },
      )
    // 이견 + 역제안(1-2 제안 기계): 이전 안은 '이견'으로 이력에 남고, 역제안이 새 현재 제안이 돼요.
    else if (mode === "item-counter" && !rawAmount) {
      // 다른 안 없이 사유만: 이견
      if (!item || item.by === role || item.status !== "합의 대기") return
      onAction(
        post(endpoints.proposalAction(proposalIdOf(itemId), "dispute"), {
          reason,
        }),
        `${item.title} · 정산 이견 · ${reason}`,
        (d) =>
          respondToOffer(
            d.settlements.find((s) => s.id === itemId)!,
            role,
            false,
            reason,
          ),
      )
    } else if (mode === "item-counter") {
      if (!item || item.by === role || item.status !== "합의 대기") return
      onAction(
        post(endpoints.proposalAction(proposalIdOf(itemId), "counter"), {
          amount,
          direction,
          reason,
        }),
        `${item.title} · 정산 이견 + 다른 안 제안 ${won(amount)} · ${reason}`,
        (d) => {
          const s = d.settlements.find((s) => s.id === itemId)!
          const old = structuredClone(s)
          delete old.history
          old.status = "이견"
          old.disputeReason = reason
          old.disputeBy = role
          s.history = [...(s.history || []), old]
          s.amount = amount
          s.direction = direction as Settlement["direction"]
          s.by = role
          s.status = "합의 대기"
          s.proposalReason = reason
          s.round = (s.round || 1) + 1
          s.disputeReason = undefined
          s.disputeBy = undefined
          s.autoChange = undefined
        },
      )
    }
    // 빼기 제안(기획서 305): 넣는 것도 빼는 것도 제안이라 상대 확인이 필요해요.
    else if (mode === "exclude") {
      if (!item || item.excludeProposal) return
      onAction(
        post(endpoints.settlementItemAction(itemId, "exclude"), { reason }),
        `${item.title} · 정산에서 빼기 제안 · ${reason}`,
        (d) => {
          d.settlements.find((s) => s.id === itemId)!.excludeProposal = {
            by: role,
            reason,
            at: new Date().toLocaleString("ko-KR"),
          }
        },
      )
    } else if (mode === "exclude-reject") {
      if (!item?.excludeProposal || item.excludeProposal.by === role) return
      onAction(
        post(
          endpoints.proposalAction(proposalIdOf(itemId, "exclude"), "dispute"),
          { reason },
        ),
        `${item.title} · 빼기 제안 거절 · ${reason}`,
        (d) => {
          const s = d.settlements.find((s) => s.id === itemId)!
          s.excludeHistory = [
            ...(s.excludeHistory || []),
            { ...s.excludeProposal!, result: `거절 · ${role}: ${reason}` },
          ]
          s.excludeProposal = undefined
        },
      )
    } else if (mode === "finish-not-yet") {
      runFinish(
        post(endpoints.settlementAction(sid, "finish/not-yet"), { reason }),
        `정산 끝내기 · 아직 안 끝났어요 · ${reason}`,
        (r) => rejectFinish(r, role, reason),
      )
    } else if (mode === "transfer") {
      // 보낸 기록인지 [받았어요(혼자)]인지는 서버가 보낸 사람의 측으로 정해요(반환을 세입자가 남기면 받았어요(혼자))
      const dir = transferDirection as Transfer["direction"]
      onAction(
        post(endpoints.settlementAction(sid, "transfers"), {
          direction: transferDirectionOf(dir),
          amount,
          paidOn,
          ...(sameAs ? { sameAsTransferId: sameAs.id } : {}),
        }),
        `${dir} 사실 기록 · ${won(amount)} · 한쪽 기록`,
        (d) => {
          const r = d.rooms.find((r) => r.id === room.id)!
          r.transfers = [
            ...roomTransfers(r),
            {
              id: uid(),
              by: role,
              amount,
              direction: dir,
              confirmed: false,
              at: paidOn,
            },
          ]
        },
      )
    }
    setMode(null)
  }
  // 정해지지 않은 금액(문서 10장): 합의되지 않은 항목의 금액을 따로 보여 줘요. 계산(total)에는 넣지 않아요.
  const unsettled = items.filter((s) => s.status !== "합의됨")
  const signed = (s: Settlement) =>
    s.autoChange?.pending
      ? "금액 미정"
      : `${s.direction === "가산" ? "+" : "−"}${won(s.amount)}`
  // 정산 항목 카드의 행동(노출 조건·순서 그대로). 크게 보이는 버튼은 앞의 두 개, 나머지는 카드 ⋯ 메뉴(화면 원칙 1)
  const itemActs = (s: Settlement) => {
    const acts: {
      key: string
      label: string
      variant: "default" | "outline" | "ghost"
      onClick: () => void
    }[] = []
    const can = (a: SettlementItemAction) => hasAction(itemActionsOf(s), a)
    {
      if (can("agree"))
        acts.push({
          key: "agree",
          label: "동의",
          variant: "default",
          onClick: () =>
            onAction(
              post(endpoints.proposalAction(proposalIdOf(s.id), "agree")),
              `${s.title} · 정산 금액 동의`,
              (d) =>
                respondToOffer(
                  d.settlements.find((v) => v.id === s.id)!,
                  role,
                  true,
                ),
            ),
        })
      if (can("dispute"))
        acts.push({
          key: "dispute",
          label: "아니에요 · 다른 안",
          variant: "outline",
          onClick: () => open("item-counter", s),
        })
    }
    if (can("withdraw"))
      acts.push({
        key: "withdraw",
        label: "제안 거두기",
        variant: "ghost",
        onClick: () =>
          onAction(
            post(endpoints.proposalAction(proposalIdOf(s.id), "withdraw")),
            `${s.title} · 정산 제안 철회`,
            (d) => {
              // 제안 철회(1-2): 제안한 쪽, 상대 응답 전 → 직전 상태. 철회한 제안은 이력에 남깁니다.
              const idx = d.settlements.findIndex((v) => v.id === s.id)
              const cur = d.settlements[idx]
              const snap = structuredClone(cur)
              delete snap.history
              snap.withdrawn = true
              const prev = cur.history?.at(-1)
              if (!prev) {
                cur.withdrawn = true
                cur.history = [...(cur.history || []), snap]
                return
              }
              const restored: Settlement = {
                ...structuredClone(prev),
                id: cur.id,
                history: [...cur.history!.slice(0, -1), snap],
              }
              if (restored.status === "이견" && restored.disputeBy === role) {
                restored.status = "합의 대기"
                restored.disputeReason = undefined
                restored.disputeBy = undefined
              }
              d.settlements[idx] = restored
            },
          ),
      })
    if (can("exclude"))
      acts.push({
        key: "exclude",
        label: "빼기 제안",
        variant: "ghost",
        onClick: () => open("exclude", s),
      })
    if (can("exclude-agree"))
      acts.push({
        key: "exclude-agree",
        label: "빼기 동의",
        variant: "outline",
        onClick: () =>
          onAction(
            post(
              endpoints.proposalAction(proposalIdOf(s.id, "exclude"), "agree"),
            ),
            `${s.title} · 빼기 제안 동의 · 정산에서 뺌`,
            (d) => {
              const v = d.settlements.find((x) => x.id === s.id)!
              v.excludeHistory = [
                ...(v.excludeHistory || []),
                { ...v.excludeProposal!, result: `동의 · ${role}` },
              ]
              v.excludeProposal = undefined
              v.excluded = true
            },
          ),
      })
    if (can("exclude-dispute"))
      acts.push({
        key: "exclude-reject",
        label: "빼기에 동의하지 않아요",
        variant: "outline",
        onClick: () => open("exclude-reject", s),
      })
    if (can("exclude-withdraw"))
      acts.push({
        key: "exclude-withdraw",
        label: "빼기 제안 거두기",
        variant: "ghost",
        onClick: () =>
          onAction(
            post(
              endpoints.proposalAction(
                proposalIdOf(s.id, "exclude"),
                "withdraw",
              ),
            ),
            `${s.title} · 빼기 제안 철회`,
            (d) => {
              const v = d.settlements.find((x) => x.id === s.id)!
              v.excludeHistory = [
                ...(v.excludeHistory || []),
                { ...v.excludeProposal!, result: "거둠" },
              ]
              v.excludeProposal = undefined
            },
          ),
      })
    if (can("proposals"))
      acts.push({
        key: "new",
        label: "바꾸기",
        variant: "outline",
        onClick: () => open("item", s),
      })
    return acts
  }
  const amount =
    "text-[30px] leading-[1.4] font-extrabold tracking-[-1px] [overflow-wrap:anywhere] max-md:text-[26px]"
  const subHead = "mt-[22px] mb-2.5 text-lg font-bold"
  const COLS = ["합의됨", "확인 대기", "이견"] as const
  const colOf = (s: Settlement) =>
    s.status === "합의 대기" ? "확인 대기" : s.status
  return (
    <>
      {/* 정산 · 보증금(둘 다 확인 상태) — 문서 10장 순서: 보증금 → 세 칸 → 계산 금액 → 정해지지 않은 금액 → 송금 → 정산 끝내기 */}
      <div className="my-[22px]">
        <Panel title="보증금 기준 금액">
          <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
            {/* 서버 정산은 아직 보증금 금액을 주지 않아 확인 상태만 보여요(#442) */}
            {server ? (
              <div className={cn(amount, "text-ink")}>
                {DEPOSIT_TEXT[server.depositStatus]}
              </div>
            ) : (
              <>
                <div className="min-w-0">
                  <div className={cn(amount, "text-ink")}>
                    {room.deposit != null ? won(room.deposit) : "미입력"}
                  </div>
                  {room.deposit == null && (
                    <p className="muted">
                      호실을 만들 때 보증금을 입력하지 않았어요. 보증금
                      확인·제안으로 금액을 남길 수 있어요.
                    </p>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-1.5">
                  <Status>내 확인 · {myConfirmed ? "확인" : "확인 전"}</Status>
                  <Status>
                    상대 확인 · {otherConfirmed ? "확인" : "확인 전"}
                  </Status>
                  <Status>
                    {room.depositConfirmed || offer?.status === "합의됨"
                      ? "보증금 · 둘 다 확인 ✓"
                      : offer?.status === "이견"
                        ? "이견"
                        : myConfirmed || otherConfirmed
                          ? "상대 확인 대기"
                          : "보증금 확인 전"}
                  </Status>
                </div>
              </>
            )}
          </div>
          {offer && (
            <CurrentProposal
              head={proposalHead("현재 제안", offer.round, sideText(offer.by))}
              amount={offer.amount}
              reason={offer.reason}
            >
              {offer.disputeReason && (
                <ProposalDispute
                  by={offer.disputeBy}
                  reason={offer.disputeReason}
                />
              )}
            </CurrentProposal>
          )}
          {offerHistory(room.depositHistory || [])}
          {/* 보증금은 계약 금액을 맞추는 것이라 "제안·합의" 대신 "적기·확인"을 써요(08 10장 [반드시]) */}
          {(has("deposit-agree") || has("deposit-proposals")) && (
            <div className="actions mt-4">
              {has("deposit-agree") && (
                <Button
                  onClick={() =>
                    onAction(
                      post(endpoints.proposalAction(offer!.id, "agree")),
                      "보증금 같은 금액 동의 · 양측 확인",
                      (d) => {
                        const r = d.rooms.find((r) => r.id === room.id)!
                        respondToOffer(r.depositOffer!, role, true)
                        r.deposit = r.depositOffer!.amount
                        r.depositConfirmed = true
                      },
                    )
                  }
                >
                  맞아요
                </Button>
              )}
              {has("deposit-dispute") && (
                <Button
                  variant="outline"
                  onClick={() => open("deposit-dispute")}
                >
                  아니에요 · 다른 금액
                </Button>
              )}
              {has("deposit-proposals") && !has("deposit-agree") && (
                <Button variant="outline" onClick={() => open("deposit")}>
                  {offer || room.depositConfirmed
                    ? "바꾸기"
                    : "보증금 금액 적기"}
                </Button>
              )}
            </div>
          )}
        </Panel>
      </div>
      {candidates}
      <Panel
        title="정산 항목"
        action={
          has("items") && (
            <Button variant="outline" onClick={onAdd}>
              + 항목 추가
            </Button>
          )
        }
      >
        {/* 세 칸: 합의됨 | 확인 대기 | 이견. 좁은 화면은 위아래로, 빈 칸은 한 줄로 */}
        <div className="grid grid-cols-3 items-start gap-3 max-[1000px]:grid-cols-1">
          {COLS.map((status) => {
            const list = items.filter((s) => colOf(s) === status)
            return (
              <div
                className="min-w-0 rounded-[14px] border border-[#e3e7eb] bg-[#f5f6f8] p-3"
                key={status}
              >
                <h3 className="mb-2 flex items-center justify-between gap-2">
                  <Status>{status}</Status>
                  <CountChip>{list.length}</CountChip>
                </h3>
                {!list.length && (
                  <p className="muted px-1 py-2">해당 항목이 없어요.</p>
                )}
                <div className="flex flex-col gap-2.5">
                  {list.map((s) => {
                    const acts = itemActs(s)
                    const big = acts.slice(0, 2)
                    const rest = acts.slice(2)
                    return (
                      <div
                        className="relative flex min-w-0 flex-col gap-2 rounded-[12px] border border-line bg-white p-4"
                        key={s.id}
                      >
                        <strong
                          className={cn(
                            "text-base leading-[1.5] text-ink",
                            rest.length && "pr-10",
                          )}
                        >
                          {s.title}
                        </strong>
                        <b className="text-[19px] leading-[1.4] font-extrabold tracking-[-.3px] text-ink tabular-nums">
                          {signed(s)}
                        </b>
                        <small className="text-sm leading-[1.7] text-[#91a3bc]">
                          현재 제안 {s.round || 1}회 · {sideText(s.by)} ·{" "}
                          {s.reason}
                        </small>
                        {s.proposalReason && (
                          <p className="text-sm">{s.proposalReason}</p>
                        )}
                        {s.disputeReason && (
                          <ProposalDispute
                            by={s.disputeBy}
                            reason={s.disputeReason}
                          />
                        )}
                        {s.repairId && (
                          <small className="block muted">출처: 수리 건</small>
                        )}
                        {s.autoChange && (
                          <div className="note">
                            {s.autoChange.pending ? (
                              <>
                                (자동) 수리 건 변경으로 다시 확인 대기 · 현재{" "}
                                {s.autoChange.moneyStatus} ·{" "}
                                {s.autoChange.fromStatus === "합의됨"
                                  ? "이전 합의"
                                  : "이전 제안"}{" "}
                                {s.direction === "가산" ? "+" : "−"}
                                {won(s.autoChange.from)} · {s.autoChange.at}
                                <p className="muted">
                                  수리 건 부담이 정해지면 새 금액으로 바뀌어요.
                                  그때까지 금액이 정해지지 않아 동의할 수 없고
                                  정산 계산에도 넣지 않아요.
                                </p>
                              </>
                            ) : (
                              <>
                                (자동) 수리 건 변경으로 다시 확인 대기 ·{" "}
                                {s.autoChange.fromStatus === "합의됨"
                                  ? "이전 합의 "
                                  : ""}
                                {won(s.autoChange.from)} →{" "}
                                {won(s.autoChange.to ?? s.amount)} ·{" "}
                                {s.autoChange.at}
                              </>
                            )}
                            <p className="muted">
                              수리 건을 바꾼 쪽: {s.autoChange.changedBy} · 정산
                              제안자는 그대로 {sideText(s.by)}이에요.
                            </p>
                          </div>
                        )}
                        {s.zone && (
                          <small className="block muted">
                            원상복구 부위 · {s.zone}
                          </small>
                        )}
                        {s.excludeProposal && (
                          <div className="dispute-callout">
                            {sideText(s.excludeProposal.by)} 빼기 제안 ·{" "}
                            {s.excludeProposal.reason}
                          </div>
                        )}
                        {s.excludeHistory?.map((h, n) => (
                          <p className="muted" key={"x" + n}>
                            빼기 제안({sideName(h.by)}) · {h.reason} ·{" "}
                            {h.result}
                          </p>
                        ))}
                        {s.history?.map((h, n) => (
                          <PastProposal
                            key={n}
                            head={proposalHead(
                              "이전 제안",
                              h.round,
                              sideName(h.by),
                              won(h.amount),
                              h.withdrawn ? "거둠" : h.status,
                            )}
                          >
                            <p>{h.proposalReason || h.reason}</p>
                            {h.disputeReason && <p>이견: {h.disputeReason}</p>}
                          </PastProposal>
                        ))}
                        {(big.length > 0 ||
                          hasAction(itemActionsOf(s), "withdraw")) && (
                          <div className="actions">
                            {big.map((a) => (
                              <Button
                                key={a.key}
                                variant={a.variant}
                                onClick={a.onClick}
                              >
                                {a.label}
                              </Button>
                            ))}
                            {s.by === role && s.status === "합의 대기" && (
                              <small className="muted">
                                상대가 같은 안에 동의해야 합의돼요.
                              </small>
                            )}
                          </div>
                        )}
                        <MoreMenu
                          className="absolute top-2 right-2"
                          items={rest.map((a) => ({
                            key: a.key,
                            label: a.label,
                            onSelect: a.onClick,
                          }))}
                        />
                      </div>
                    )
                  })}
                </div>
              </div>
            )
          })}
        </div>
        {withdrawnItems.length > 0 && (
          <div className="mt-4">
            <h3 className={subHead}>거둔 제안</h3>
            {withdrawnItems.map((s) => (
              <p className="muted" key={s.id}>
                {s.title} · {s.direction === "가산" ? "+" : "−"}
                {won(s.amount)} · {sideText(s.by)} 제안 거둠
              </p>
            ))}
          </div>
        )}
        {excludedItems.length > 0 && (
          <div className="mt-4">
            <h3 className={subHead}>둘 다 동의해 정산에서 뺀 항목</h3>
            {excludedItems.map((s) => (
              <p className="muted" key={s.id}>
                {s.title} · {s.direction === "가산" ? "+" : "−"}
                {won(s.amount)} · {s.excludeHistory?.at(-1)?.reason}
              </p>
            ))}
          </div>
        )}
      </Panel>
      <div className="mt-5">
        <Panel title="합의된 항목으로 계산한 금액">
          <div className={cn(amount, "text-primary")}>{won(total)}</div>
          <p className="muted">보증금 − 합의된 차감 + 합의된 가산</p>
          <p className="muted">
            확인 대기·이견 {unsettled.length}건 제외
            {!depositAgreed ? " · 보증금 확인 전 기준 계산" : ""}
          </p>
          {/* 정해지지 않은 금액: 항목마다 금액을 따로(계산에는 넣지 않아요) */}
          {unsettled.length > 0 && (
            <div className="mt-3 rounded-[12px] bg-soft px-4 py-3">
              <p className="text-sm font-semibold text-sub">
                정해지지 않은 금액
              </p>
              <ul className="mt-1.5 flex flex-col gap-1">
                {unsettled.map((s) => (
                  <li
                    key={s.id}
                    className="flex items-baseline justify-between gap-3 text-sm"
                  >
                    <span className="min-w-0 text-ink [overflow-wrap:anywhere]">
                      {s.title}{" "}
                      <span className="text-[13px] text-sub">
                        · <Status>{colOf(s)}</Status>
                      </span>
                    </span>
                    <span className="flex-none font-bold text-ink tabular-nums">
                      {signed(s)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Panel>
      </div>
      <Panel title="보증금 반환 · 수령 기록" className="mt-5">
        <p className="muted">
          실제 송금이 아닌 과거 사실 기록입니다. 정산 금액이 바뀌어도 기록
          금액은 바뀌지 않아요.
        </p>
        {/* 영수증처럼: 순액 · 차액 */}
        <dl className="my-3 divide-y divide-line rounded-[12px] border border-line">
          <div className="flex items-baseline justify-between gap-3 px-4 py-3">
            <dt className="text-[15px] text-sub">
              상대가 확인한 반환·송금 순액
            </dt>
            <dd className="text-xl font-extrabold text-ink tabular-nums">
              {won(paid)}
            </dd>
          </div>
          {/* 확인 전 송금은 합계에 넣지 않고 따로 보여요(03 J, 08 10장) */}
          {pendingPaid !== 0 && (
            <div className="flex items-baseline justify-between gap-3 px-4 py-3">
              <dt className="text-[15px] text-sub">받았는지 확인 중</dt>
              <dd className="text-base font-bold text-sub tabular-nums">
                {won(pendingPaid)}
              </dd>
            </div>
          )}
          <div
            className="flex items-baseline justify-between gap-3 px-4 py-3"
            aria-live="polite"
          >
            <dt className="text-[15px] font-semibold text-ink">차액</dt>
            <dd className="text-2xl font-extrabold text-primary tabular-nums">
              {won(total - paid)}
            </dd>
          </div>
        </dl>
        <div className="flex flex-col gap-2">
          {transfers.map((t) => (
            <div
              className="flex flex-wrap items-center gap-3 rounded-[12px] bg-[#f5f6f8] p-4"
              key={t.id}
            >
              <div className="min-w-0 flex-1">
                <strong className="text-lg font-[750] text-ink">
                  {t.direction} {won(t.amount)}
                </strong>
                <p className="text-sm leading-[1.7] text-[#8fa3be]">
                  {t.at} · 기록한 쪽 {sideName(t.by)}
                </p>
                <Status>
                  {server
                    ? transferStatus(t)
                    : t.confirmed
                      ? "둘 다 확인"
                      : "받았는지 확인 중"}
                </Status>
                {/* 같은 송금으로 묶어 적은 받은 금액(SET-4e, 08 10장). 합계에는 확인된 쪽만 한 번 들어가요 */}
                {(() => {
                  const l = linkedTo(t)
                  if (!l) return null
                  return (
                    <div className="mt-2 space-y-1.5 border-t border-line pt-2">
                      <p className="text-sm text-ink">
                        받은 금액 {won(l.amount)}({sideName(l.by)} 기록) ·{" "}
                        <Status>{transferStatus(l)}</Status>
                      </p>
                      {transferRespond(l)}
                    </div>
                  )
                })()}
              </div>
              {server && transferRespond(t)}
              {server && has("transfers") && canWriteReceived(t) && (
                <Button variant="outline" onClick={() => openReceived(t)}>
                  받았어요(혼자)
                </Button>
              )}
              {!server && hasAction(transferActionsOf(t), "respond") && (
                <Button
                  onClick={() =>
                    onAction(
                      post(endpoints.transferRespond(t.id), { answer: "ACK" }),
                      `${t.direction} ${won(t.amount)} · 상대 확인`,
                      (d) => {
                        const r = d.rooms.find((r) => r.id === room.id)!
                        r.transfers = roomTransfers(r)
                        r.transfers.find((v) => v.id === t.id)!.confirmed = true
                      },
                    )
                  }
                >
                  {role === "임차인" && t.direction === "반환"
                    ? "받았어요"
                    : "송금 사실 확인"}
                </Button>
              )}
            </div>
          ))}
        </div>
        {has("transfers") && (
          <Button
            variant="outline"
            className="mt-4"
            onClick={() => open("transfer")}
          >
            반환·수령 기록 추가
          </Button>
        )}
        {room.status === "거주 종료" && (
          <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t pt-5 max-md:flex-col max-md:items-stretch">
            <p className="text-sm text-[#8c9fb9]">
              이견과 확인 없음 항목은 정산이 끝난 뒤에도 그대로 남아요.
            </p>
            {/* 정산 끝내기(06 SET-5a~e). 버튼은 availableActions로만 그려요. */}
            <div className="flex flex-col items-end gap-2 max-md:items-stretch">
              {finish.kind === "changed" && (
                <p className="text-sm text-warn-ink">
                  {FINISH_CHANGED_MESSAGE} · 다시 요청할 수 있어요.
                </p>
              )}
              {finish.kind === "not-yet" && (
                <p className="text-sm text-warn-ink">
                  {sideName(finish.by)}이 아직 안 끝났다고 답했어요
                  {finish.text ? ` · ${finish.text}` : ""}
                </p>
              )}
              {finish.kind === "waiting" && finish.by === role && (
                <p className="text-sm text-sub">
                  상대의 정산 끝내기 확인을 기다려요
                  {has("finish-alone")
                    ? " · 72시간 동안 답이 오지 않아 혼자 끝낼 수 있어요"
                    : ""}
                </p>
              )}
              {/* 끝내기 확인 화면에 내가 아직 답하지 않은 확인 수(SET-5g). 확인은 막지 않아요 */}
              {has("finish-confirm") &&
                server &&
                server.unansweredForMe > 0 && (
                  <p className="text-sm text-warn-ink">
                    아직 답하지 않은 확인 {server.unansweredForMe}건
                  </p>
                )}
              <div className="actions">
                {has("finish-confirm") && (
                  <Button
                    onClick={() =>
                      runFinish(
                        // 본 정산 모습 번호를 함께 보내요. 그사이 바뀌었으면 서버가 VERSION_CONFLICT로 거절해요(SET-5e)
                        post(
                          endpoints.settlementAction(sid, "finish/confirm"),
                          server
                            ? { seenVersionNo: server.snapshotNo }
                            : undefined,
                        ),
                        "정산 끝내기 · 양측 확인",
                        (r, list) => confirmFinish(r, list, role),
                      )
                    }
                  >
                    정산 끝내기 확인
                  </Button>
                )}
                {has("finish-not-yet") && (
                  <Button
                    variant="outline"
                    onClick={() => open("finish-not-yet")}
                  >
                    아직 안 끝났어요
                  </Button>
                )}
                {has("finish-alone") && (
                  <Button
                    onClick={() =>
                      runFinish(
                        post(endpoints.settlementAction(sid, "finish/alone")),
                        "정산 혼자 끝내기 · 답이 오지 않음",
                        (r, list) => finishAlone(r, list, role),
                      )
                    }
                  >
                    혼자 끝내기
                  </Button>
                )}
                {has("finish") &&
                  (finish.kind === "not-yet" && finish.waitDays > 0 ? (
                    <small className="muted">
                      {finish.waitDays}일 뒤 다시 요청할 수 있어요
                    </small>
                  ) : (
                    <Button
                      onClick={() =>
                        runFinish(
                          post(endpoints.settlementAction(sid, "finish")),
                          "정산 끝내기 제안",
                          (r, list) => requestFinish(r, list, role),
                        )
                      }
                    >
                      {finish.kind === "changed"
                        ? "다시 요청"
                        : "정산 끝내기 제안"}
                    </Button>
                  ))}
              </div>
              {finishError && (
                <p role="alert" className="form-error">
                  {finishError}
                </p>
              )}
            </div>
          </div>
        )}
      </Panel>
      <Dialog
        open={mode !== null}
        onOpenChange={(v) => {
          if (!v) setMode(null)
        }}
      >
        <DialogContent className="app-dialog">
          <DialogHeader>
            <DialogTitle>
              {mode === "transfer"
                ? "반환·수령 사실 기록"
                : mode === "item-counter"
                  ? "아니에요 · 다른 안"
                  : mode === "finish-not-yet"
                    ? "아직 안 끝났어요"
                    : mode === "deposit-dispute"
                      ? "아니에요 · 다른 금액"
                      : mode === "exclude"
                        ? "정산에서 빼기 제안"
                        : mode === "exclude-reject"
                          ? "빼기에 동의하지 않아요"
                          : mode === "deposit"
                            ? offer || room.depositConfirmed
                              ? "보증금 바꾸기"
                              : "보증금 금액 적기"
                            : "바꾸기"}
            </DialogTitle>
            <DialogDescription>
              {mode === "deposit" || mode === "deposit-dispute"
                ? "보증금은 계약서 금액을 맞추는 거예요. 둘이 같은 금액이면 둘 다 확인으로 남아요."
                : mode === "transfer"
                  ? "돈이 오간 사실을 남겨요. 상대가 확인한 기록만 송금 합계에 들어가요."
                  : "한쪽의 제안은 상대가 같은 안에 동의해야 합의됩니다."}
            </DialogDescription>
          </DialogHeader>
          <form className="modal-form" onSubmit={submit}>
            {[
              "deposit",
              "deposit-dispute",
              "item",
              "item-counter",
              "transfer",
            ].includes(mode || "") && (
              <div className="field">
                <label htmlFor="financial-amount">
                  {mode === "item-counter"
                    ? "다른 안 금액 (원) · 선택"
                    : mode === "deposit-dispute"
                      ? "다른 금액 (원) · 선택"
                      : "금액 (원)"}
                </label>
                <MoneyInput
                  id="financial-amount"
                  name="amount"
                  defaultValue={
                    mode === "deposit"
                      ? (offer?.amount ?? room.deposit)
                      : mode === "item"
                        ? item?.amount
                        : mode === "transfer"
                          ? Math.abs(total - paid)
                          : undefined
                  }
                  required={
                    mode !== "item-counter" && mode !== "deposit-dispute"
                  }
                />
              </div>
            )}
            {(mode === "item" || mode === "item-counter") && (
              <Choice
                label="계산 방향"
                value={direction}
                values={["차감", "가산"]}
                onChange={setDirection}
              />
            )}
            {mode === "transfer" ? (
              <>
                {/* 같은 송금으로 묶어 적을 때는 방향이 그 기록과 같아요(SET-4e) */}
                {sameAs ? (
                  <p className="muted">
                    {sideName(sameAs.by)} 기록 {sameAs.direction}{" "}
                    {won(sameAs.amount)}과 같은 송금으로 묶어 실제 받은 금액을
                    남겨요.
                  </p>
                ) : (
                  <Choice
                    label="송금 방향"
                    value={transferDirection}
                    values={["반환", "추가 송금"]}
                    onChange={setTransferDirection}
                  />
                )}
                <p className="muted">
                  {transferDirection === "반환"
                    ? "집주인 → 세입자"
                    : "세입자 → 집주인"}{" "}
                  ·{" "}
                  {receiverOf(transferDirection as Transfer["direction"]) ===
                  role
                    ? "받은 쪽 기록([받았어요(혼자)])으로 남겨 보낸 쪽에게 확인을 요청해요."
                    : `${sideText(role)}의 한쪽 기록으로 남겨 상대에게 확인을 요청해요.`}
                </p>
                <div className="field">
                  <label htmlFor="financial-paid-on">
                    {receiverOf(transferDirection as Transfer["direction"]) ===
                    role
                      ? "받은 날짜"
                      : "보낸 날짜"}
                  </label>
                  <Input
                    id="financial-paid-on"
                    name="paidOn"
                    type="date"
                    defaultValue={todayKey()}
                    max={todayKey()}
                    required
                  />
                </div>
              </>
            ) : (
              <div className="field">
                <label htmlFor="financial-reason">
                  {mode === "item-counter" || mode === "deposit-dispute"
                    ? "아닌 이유 · 필수"
                    : "사유 · 필수"}
                </label>
                <Textarea id="financial-reason" name="reason" required />
              </div>
            )}
            {error && (
              <p role="alert" className="form-error">
                {error}
              </p>
            )}
            <div className="modal-footer">
              <Button
                type="button"
                variant="outline"
                onClick={() => setMode(null)}
              >
                취소
              </Button>
              <Button type="submit">
                {mode === "transfer" ? "사실 기록 남기기" : "제안·응답 남기기"}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
