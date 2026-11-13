import {
  other,
  uid,
  type BurdenLine,
  type FactCorrection,
  type Payment,
  type Proposal,
  type Repair,
  type Role,
  type Room,
  type SoloReason,
} from "@/data/demo"
import {
  assertSeenVersion,
  bumpVersion,
  versionOf,
  VERSION_CONFLICT_MESSAGE,
} from "@/lib/versions"
import { sideText } from "@/lib/words"

// 고치기(상태와 규칙 G, decisions 2026-11-11): 도장(상대의 맞아요·보류·이견) 전에는 바로 고쳐지고,
// 도장 뒤에는 무엇을 고치든 '고친 내용'으로 남아 상대가 확인해야 앞모습이 바뀌어요. 상대 미확인은 도장이 아니에요.
export const factRequestName = (field: string) =>
  `고친 내용 확인 요청(${field})`

// 하자·수리 상세의 상태 규칙(상태와 규칙 1-1, 1-2, D, E, F, G). 화면 컴포넌트는 이 함수들로만 상태를 바꿉니다.
export const nowText = () =>
  new Date().toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })
export const sideLabel = (side: Role | "제3자" | "업체", label?: string) =>
  side === "제3자"
    ? `제3자${label ? `(${label})` : ""}`
    : side === "업체"
      ? `업체${label ? `(${label})` : ""}`
      : side

/* ── 1-1 기다림 타이머와 혼자 진행 가능한 이유 ── */
// 호실 단위 이유: ② 수신 거부 ③ 탈퇴 ④ 상대 없음(번호가 없거나 초대 전). ① 미확인은 요청별 타이머로 판단합니다.
export function roomSoloReason(room: Room): SoloReason | undefined {
  if (room.counterpartLeft) return "상대 탈퇴"
  if (room.optedOut && room.connection !== "연결됨") return "상대 수신 거부"
  if (room.connection === "연결 전" || room.connection === "거절됨")
    return "상대 없음"
  return undefined
}
export function soloReasonFor(
  room: Room,
  repair: Repair,
  key: string,
): SoloReason | undefined {
  return (
    roomSoloReason(room) ??
    (repair.waits?.[key] === "미확인" ? "상대 미확인" : undefined)
  )
}
// 혼자 진행할 수 있는 이유의 화면 문장(08-화면-기본-틀 8장: "○○ 님 확인 없음 · 이유", 판정하지 않는 말)
export const soloReasonText: Record<SoloReason, string> = {
  "상대 미확인": "확인 없음 · 72시간 동안 답이 오지 않았어요",
  "상대 수신 거부": "확인 없음 · 문자를 그만 받기로 했어요",
  "상대 탈퇴": "확인 없음 · 탈퇴했어요",
  "상대 없음": "혼자 기록하고 있어요 · 아직 상대를 초대하기 전이에요",
}
export function clearWait(r: Repair, key: string) {
  if (r.waits) delete r.waits[key]
}
// 기록 시각 해석: ISO(2026-11-09T…)와 한국어 표기(2026. 11. 9. 오전 2:41:08, 2026.10.03) 모두 밀리초로 바꿉니다. 읽을 수 없으면 undefined.
export function recordTimeMs(text?: string): number | undefined {
  if (!text) return undefined
  const parsed = Date.parse(text)
  if (/^\d{4}-\d{2}-\d{2}/.test(text))
    return Number.isNaN(parsed) ? undefined : parsed
  const m = text.match(
    /(\d{4})\D+(\d{1,2})\D+(\d{1,2})(?:\D*?(오전|오후|AM|PM)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?)?/,
  )
  if (!m) return Number.isNaN(parsed) ? undefined : parsed
  let hour = Number(m[5] || 0)
  if ((m[4] === "오후" || m[4] === "PM") && hour < 12) hour += 12
  if ((m[4] === "오전" || m[4] === "AM") && hour === 12) hour = 0
  const ms = new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    hour,
    Number(m[6] || 0),
    Number(m[7] || 0),
  ).getTime()
  return Number.isNaN(ms) ? undefined : ms
}
export const recordDateText = (text?: string) => {
  const ms = recordTimeMs(text)
  return ms === undefined
    ? text || "날짜 모름"
    : new Date(ms).toLocaleDateString("ko-KR")
}

export interface PendingRequest {
  key: string
  label: string
  by: Role
}
// 지금 상대 응답을 기다리는 요청 목록(시연용 시간 경과 대상)
export function pendingRequests(r: Repair): PendingRequest[] {
  const list: PendingRequest[] = []
  const bothClosed = r.status === "처리 완료" && !r.soloClosed
  if (!bothClosed && ["확인 대기", "상대 미확인"].includes(r.ack))
    list.push({ key: "ack", label: "하자 확인 요청", by: r.author })
  if (r.closeBy)
    list.push({ key: "close", label: "종료 확인 요청", by: r.closeBy })
  if (r.confirmRequested && r.soloClosed)
    list.push({
      key: "soloclose",
      label: "혼자 종료한 건 종료 확인 요청",
      by: r.soloClosed,
    })
  if (r.proposal?.status === "합의 대기")
    list.push({
      key: "proposal",
      label: `부담 제안 ${r.proposal.round || 1}회`,
      by: r.proposal.by,
    })
  if (r.changeProposal)
    list.push({
      key: "change",
      label: "바꾸자는 새 제안",
      by: r.changeProposal.by,
    })
  r.payments?.forEach((p) => {
    if (!p.confirmed && !p.disputes?.length)
      list.push({
        key: `pay:${p.id}`,
        label: `지불 금액 확인 · ${p.amount.toLocaleString("ko-KR")}원`,
        by: p.by,
      })
  })
  r.payments?.forEach((p) =>
    p.corrections?.forEach((c) => {
      if (c.status === "확인 대기")
        list.push({
          key: `fix:${c.id}`,
          label: `지불 금액 고친 내용 확인 요청`,
          by: c.by,
        })
    }),
  )
  r.corrections?.forEach((c) => {
    if (c.status === "확인 대기")
      list.push({
        key: `fix:${c.id}`,
        label: factRequestName(c.field),
        by: c.by,
      })
  })
  return list
}
// 시연 전용 시간 경과: 요청 → (48시간) 재통지 → (24시간) 상대 미확인
export function advanceWait(r: Repair, key: string) {
  const stage = r.waits?.[key]
  r.waits = { ...(r.waits || {}), [key]: stage ? "미확인" : "재통지" }
  if (stage !== "재통지") return
  if (key === "ack") r.ack = "상대 미확인"
  if (key === "soloclose") {
    r.soloReason = "상대 미확인"
    r.confirmRequested = false
  }
  // 고친 내용은 미응답이어도 반영하지 않아요(상대 미확인 표시만).
}

/* ── D 수리 상태 ── */
export function confirmDefect(r: Repair, role: Role, seenVersion?: number) {
  if (r.author === role) throw new Error("하자 확인은 상대 쪽이 해요.")
  assertSeenVersion(r, seenVersion)
  if (r.status === "처리 완료" && !r.soloClosed)
    throw new Error(CLOSED_BY_BOTH_MESSAGE)
  // 보류·이견은 정산 완료 전까지 언제든 맞아요로 바꿀 수 있고, 이전 응답은 이력에 남아요(03 G '나중에 답 바꾸기').
  if (r.ackDispute)
    r.notes = [
      ...(r.notes || []),
      {
        kind: "맞아요로 답 바꿈",
        by: role,
        text: `이전 답(${r.ackDispute.type}): ${r.ackDispute.reason}`,
        at: nowText(),
      },
    ]
  r.ack = "확인됨"
  r.ackDispute = undefined
  clearWait(r, "ack")
  if (r.status === "확인 전") r.status = "확인 완료"
}
// 하자 응답 [아니에요 · 더 필요해요]: 보류(더 필요한 게 있어요) 또는 이견(사실과 달라요). 둘 다 도장이고 기다림을 닫아요.
export function respondDefect(
  r: Repair,
  role: Role,
  type: "보류" | "이견",
  reason: string,
) {
  if (r.author === role) throw new Error("하자 응답은 상대 쪽이 해요.")
  if (r.status === "처리 완료" && !r.soloClosed)
    throw new Error(CLOSED_BY_BOTH_MESSAGE)
  if (r.ack === "확인됨")
    throw new Error("맞아요로 답한 기록은 되돌리지 않아요.")
  if (!reason.trim()) throw new Error("사유를 입력해 주세요.")
  r.ack = type === "보류" ? "보충 요청" : "이견"
  r.ackDispute = { by: role, type, reason: reason.trim(), at: nowText() }
  clearWait(r, "ack")
  r.notes = [
    ...(r.notes || []),
    {
      kind: type === "보류" ? "하자 보충 요청" : "하자 이견",
      by: role,
      text: reason.trim(),
      at: nowText(),
    },
  ]
}
// 다시 확인 요청(보충하고 다시 확인 요청·상황이 바뀌었어요)은 한 기록에 7일에 한 번(03 G '다시 확인 요청의 한도').
export const REASK_DAYS = 7
export function reaskWaitDays(reaskedAt?: string, now = Date.now()) {
  if (!reaskedAt) return 0
  const left = Date.parse(reaskedAt) + REASK_DAYS * 86_400_000 - now
  return left > 0 ? Math.ceil(left / 86_400_000) : 0
}
function assertCanReask(reaskedAt: string | undefined, now: number) {
  const days = reaskWaitDays(reaskedAt, now)
  if (days > 0) throw new Error(`${days}일 뒤 다시 요청할 수 있어요.`)
}
// [보충하고 다시 확인 요청]: 기록한 쪽이 사진이나 설명을 하나 이상 더해 새 확인 요청을 보내요.
export function supplementDefect(
  r: Repair,
  role: Role,
  data: { text: string; photos: string[] },
  now = Date.now(),
) {
  if (r.author !== role) throw new Error("보충은 처음 기록한 쪽이 해요.")
  if (r.ack !== "보충 요청" && r.ack !== "이견")
    throw new Error("보충 요청이나 이견을 받은 기록에만 다시 요청할 수 있어요.")
  const text = data.text.trim()
  if (!text && !data.photos.length)
    throw new Error("사진이나 설명을 하나 이상 더해 주세요.")
  assertCanReask(r.reaskedAt, now)
  const prev = r.ackDispute
  r.notes = [
    ...(r.notes || []),
    {
      kind: "보충하고 다시 확인 요청",
      by: role,
      text: [
        text,
        data.photos.length ? `사진 ${data.photos.length}장 추가` : "",
        prev ? `(이전 답: ${prev.type} · ${prev.reason})` : "",
      ]
        .filter(Boolean)
        .join(" · "),
      at: nowText(),
    },
  ]
  r.photos = [...r.photos, ...data.photos]
  r.ack = "확인 대기"
  r.ackDispute = undefined
  r.reaskedAt = new Date(now).toISOString()
  clearWait(r, "ack")
}
export function startRepair(r: Repair) {
  if (!["확인 전", "확인 완료"].includes(r.status))
    throw new Error("확인 전·확인 완료 상태에서만 수리를 시작해요.")
  r.startFlag =
    r.ack === "이견"
      ? "이견 유지 중 수리 시작"
      : r.ack === "보충 요청"
        ? "보충 요청 중 수리 시작"
        : r.ack !== "확인됨"
          ? "상대 확인 전 수리 시작"
          : undefined
  r.status = "처리 중"
}

/* ── F 지불 응답 (MONEY-2): 맞아요 / 보류 / 금액 이견, 보충하고 다시 요청 ── */
// 지금 열려 있는 보류·이견(보충하고 다시 요청하면 지난 것이 돼요)
export const openPaymentDispute = (p: Payment) =>
  p.disputes?.filter((d) => !d.closed).at(-1)
export function respondPayment(
  p: Payment,
  role: Role,
  type: "보류" | "이견",
  reason: string,
) {
  if (p.by === role) throw new Error("내가 남긴 지불에는 답할 수 없어요.")
  if (p.confirmed) throw new Error("맞아요로 답한 기록은 되돌리지 않아요.")
  if (!reason.trim()) throw new Error("사유를 입력해 주세요.")
  p.disputes = [
    ...(p.disputes || []),
    { by: role, kind: type, reason: reason.trim(), at: nowText() },
  ]
}
export function supplementPayment(
  p: Payment,
  role: Role,
  data: { text: string; photos: string[] },
  now = Date.now(),
) {
  if (p.by !== role) throw new Error("보충은 지불을 기록한 쪽이 해요.")
  if (p.confirmed || !openPaymentDispute(p))
    throw new Error("보충 요청이나 이견을 받은 지불에만 다시 요청할 수 있어요.")
  if (!data.text.trim() && !data.photos.length)
    throw new Error("사진이나 설명을 하나 이상 더해 주세요.")
  assertCanReask(p.reaskedAt, now)
  p.disputes = p.disputes?.map((d) => ({ ...d, closed: true }))
  // 보충 설명은 행동 기록(경과)에 남고, 사진은 증빙에 더해져요.
  p.photos = [...(p.photos || []), ...data.photos]
  p.reaskedAt = new Date(now).toISOString()
}
// 수리 후 사진은 종료(요청·혼자 종료) 전에 1장 이상 있어야 해요.
export const hasAfterPhoto = (r: Repair) => (r.afterPhotos?.length ?? 0) > 0
export const AFTER_PHOTO_REQUIRED =
  "수리 후 사진을 1장 이상 남겨야 종료할 수 있어요. [수리 후 사진 남기기]로 먼저 올려 주세요."
export function requestClose(r: Repair, role: Role) {
  if (r.status !== "처리 중")
    throw new Error("처리 중인 건만 종료를 요청할 수 있어요.")
  if (!hasAfterPhoto(r)) throw new Error(AFTER_PHOTO_REQUIRED)
  r.closeBy = role
  clearWait(r, "close")
}
export function confirmClose(r: Repair, role: Role) {
  if (!r.closeBy || r.closeBy === role)
    throw new Error("상대가 요청한 종료만 확인할 수 있어요.")
  r.status = "처리 완료"
  r.closeBy = undefined
  r.soloClosed = undefined
  r.soloReason = undefined
  clearWait(r, "close")
  closeContentCorrections(r)
}
export function notYet(r: Repair, role: Role, reason: string) {
  if (!r.closeBy || r.closeBy === role)
    throw new Error("상대가 요청한 종료에만 답할 수 있어요.")
  if (!reason.trim()) throw new Error("사유를 입력해 주세요.")
  r.closeBy = undefined
  clearWait(r, "close")
  r.notes = [
    ...(r.notes || []),
    { kind: "아직 안 됐어요", by: role, text: reason.trim(), at: nowText() },
  ]
}
// 혼자 종료: 상대 없음·수신 거부·탈퇴면 바로, 상대 미확인이면 종료 요청한 쪽이
export function soloClose(r: Repair, role: Role, reason: SoloReason) {
  if (r.status !== "처리 중")
    throw new Error("처리 중인 건만 종료할 수 있어요.")
  if (reason === "상대 미확인" && r.closeBy !== role)
    throw new Error("종료를 요청한 쪽만 확인 없음 뒤에 혼자 종료할 수 있어요.")
  if (!hasAfterPhoto(r)) throw new Error(AFTER_PHOTO_REQUIRED)
  r.status = "처리 완료"
  r.soloClosed = role
  r.soloReason = reason
  r.closeBy = undefined
  clearWait(r, "close")
}
// 한쪽 확인으로 끝난 건: 확인하지 않은 쪽이 확인하거나(→ 양측 확인), 사유를 달아 다시 엽니다.
export function confirmSoloClosed(r: Repair, role: Role) {
  if (r.status !== "처리 완료" || !r.soloClosed || r.soloClosed === role)
    throw new Error("상대가 혼자 종료한 건만 확인할 수 있어요.")
  r.soloClosed = undefined
  r.soloReason = undefined
  r.confirmRequested = false
  clearWait(r, "soloclose")
  closeContentCorrections(r)
}
export function reopen(r: Repair, role: Role, reason: string) {
  if (r.status !== "처리 완료" || !r.soloClosed || r.soloClosed === role)
    throw new Error(
      "한쪽 확인으로 끝난 건을 확인하지 않은 쪽만 다시 열 수 있어요.",
    )
  if (!reason.trim()) throw new Error("다시 여는 사유를 입력해 주세요.")
  r.status = "처리 중"
  r.soloClosed = undefined
  r.soloReason = undefined
  r.closeBy = undefined
  r.confirmRequested = false
  clearWait(r, "soloclose")
  r.notes = [
    ...(r.notes || []),
    { kind: "다시 열기", by: role, text: reason.trim(), at: nowText() },
  ]
}
export const canRecur = (r: Repair) => r.status === "처리 완료" && !r.soloClosed

/* ── E 돈 상태 (1-2 제안 기계) ── */
export function burden(
  p: Pick<Proposal, "lines" | "payer" | "amount" | "tenantAmount">,
) {
  if (p.lines) {
    const sum = (side: BurdenLine["side"]) =>
      p.lines!.filter((l) => l.side === side).reduce((s, l) => s + l.amount, 0)
    return {
      tenant: sum("임차인"),
      landlord: sum("임대인"),
      third: sum("제3자"),
    }
  }
  const tenant =
    p.payer === "나눠서"
      ? p.tenantAmount || 0
      : p.payer === "임차인"
        ? p.amount
        : 0
  return { tenant, landlord: p.amount - tenant, third: 0 }
}
export function proposalText(p: Proposal) {
  if (
    p.status === "비용 없음" ||
    (p.amount === 0 && !p.lines?.some((l) => l.amount))
  )
    return "비용 없음(0원)"
  const lines = p.lines || [
    { side: "임차인" as const, amount: burden(p).tenant },
    { side: "임대인" as const, amount: burden(p).landlord },
  ]
  return lines
    .filter((l) => l.amount > 0)
    .map(
      (l) =>
        `${sideLabel(l.side, l.label)} ${l.amount.toLocaleString("ko-KR")}원`,
    )
    .join(" · ")
}
const payerOf = (lines: BurdenLine[]): Proposal["payer"] => {
  const used = lines.filter((l) => l.amount > 0)
  return used.length === 1 && used[0].side !== "제3자" ? used[0].side : "나눠서"
}
export function makeProposal(
  r: Repair,
  role: Role,
  lines: BurdenLine[],
  reason: string,
  counter?: { disputeReason: string },
) {
  const total = lines.reduce((s, l) => s + l.amount, 0)
  if (lines.some((l) => !Number.isSafeInteger(l.amount) || l.amount < 0))
    throw new Error("금액은 0 이상의 정수로 입력해 주세요.")
  const prev = r.proposal
  if (counter) {
    if (!prev || prev.status !== "합의 대기" || prev.by === role)
      throw new Error("상대의 현재 제안에만 다른 안을 낼 수 있어요.")
    if (!counter.disputeReason.trim())
      throw new Error("이견 사유를 입력해 주세요.")
  } else if (prev?.status === "합의 대기" && !prev.totalChanged)
    throw new Error(
      "응답을 기다리는 제안이 있어요. 동의·이견하거나, 내 제안이면 거둬 주세요.",
    )
  // 합의 뒤 새 제안(최신 E): 지금 합의는 그대로 두고 '바꾸자는 안'으로만 남겨요. 상대가 동의해야 새 합의가 돼요.
  if (
    !counter &&
    prev &&
    (prev.status === "합의됨" || prev.status === "비용 없음") &&
    !prev.totalChanged
  ) {
    if (r.changeProposal)
      throw new Error(
        "응답을 기다리는 바꾸자는 안이 있어요. 거두거나 상대 응답을 기다려 주세요.",
      )
    if (lines.some((l) => !Number.isSafeInteger(l.amount) || l.amount < 0))
      throw new Error("금액은 0 이상의 정수로 입력해 주세요.")
    r.changeProposal = {
      by: role,
      lines,
      amount: total,
      payer: payerOf(lines),
      tenantAmount: burden({ lines, amount: total, payer: "나눠서" }).tenant,
      status: "합의 대기",
      reason: reason.trim(),
      round: (prev.round || 0) + 1,
      at: nowText(),
    }
    clearWait(r, "change")
    return
  }
  const history = r.proposalHistory || []
  const restore = prev ? structuredClone({ ...prev, restore: undefined }) : null
  if (prev) {
    const old = structuredClone({ ...prev, restore: undefined })
    if (counter) {
      old.status = "이견"
      old.disputeReason = counter.disputeReason.trim()
      old.disputeBy = role
    }
    r.proposalHistory = [...history, old]
  }
  r.proposal = {
    by: role,
    lines,
    amount: total,
    payer: payerOf(lines),
    tenantAmount: burden({ lines, amount: total, payer: "나눠서" }).tenant,
    status: "합의 대기",
    reason: reason.trim(),
    round: (prev?.round || 0) + 1,
    at: nowText(),
    restore,
    restoreHistoryLength: history.length,
  }
  clearWait(r, "proposal")
}
// 부담 안의 합과 전체 금액이 맞는지(상태와 규칙 1-2·E: 나눠서는 두 금액의 합 = 전체 금액, 합의 뒤 지불 합계가 바뀌면 합의 대기).
// 전체 금액 = 업체·제3자에게 낸 지불 합계. 지불 기록이 아직 없으면 제안 금액을 전체로 봅니다.
// 전체 금액 = 같이 확인한 지불(업체·제3자에게 낸 돈)의 합(최신 E). 확인된 지불이 없으면 제안 금액을 전체로 봅니다. 나눠서든 한쪽 전부든 같아요.
export function proposalTotalMismatch(
  r: Repair,
  p: Proposal | undefined = r.proposal,
) {
  if (!p) return undefined
  const sum = p.lines ? p.lines.reduce((s, l) => s + l.amount, 0) : p.amount
  if (sum !== p.amount) return { sum, total: p.amount }
  const total = confirmedWorkTotal(r)
  if (total > 0 && total !== sum && sum !== 0) return { sum, total }
  return undefined
}
export function agreeProposal(r: Repair, role: Role) {
  const p = r.proposal
  if (!p || p.status !== "합의 대기" || p.by === role)
    throw new Error("상대 쪽의 현재 제안에만 동의할 수 있어요.")
  const mismatch = proposalTotalMismatch(r)
  if (mismatch)
    throw new Error(
      `부담 금액의 합(${mismatch.sum.toLocaleString("ko-KR")}원)이 전체 금액(${mismatch.total.toLocaleString("ko-KR")}원)과 달라 동의할 수 없어요. 새 부담 제안을 내 주세요.`,
    )
  p.status = p.amount === 0 ? "비용 없음" : "합의됨"
  p.totalChanged = false
  clearWait(r, "proposal")
}
export function disputeProposal(r: Repair, role: Role, reason: string) {
  const p = r.proposal
  if (!p || p.status !== "합의 대기" || p.by === role)
    throw new Error("상대 쪽의 현재 제안에만 이견을 남길 수 있어요.")
  if (!reason.trim()) throw new Error("이견 사유를 입력해 주세요.")
  p.status = "이견"
  p.disputeReason = reason.trim()
  p.disputeBy = role
  clearWait(r, "proposal")
}
// 제안 철회: 제안한 쪽, 상대 응답 전 → 직전 상태. 철회한 제안은 이력에 남깁니다.
export function withdrawProposal(r: Repair, role: Role) {
  const p = r.proposal
  if (!p || p.status !== "합의 대기" || p.by !== role || p.totalChanged)
    throw new Error("상대가 답하기 전인 내 제안만 거둘 수 있어요.")
  const kept = (r.proposalHistory || []).slice(
    0,
    p.restoreHistoryLength ?? (r.proposalHistory || []).length,
  )
  r.proposalHistory = [
    ...kept,
    { ...structuredClone({ ...p, restore: undefined }), withdrawn: true },
  ]
  r.proposal = p.restore ?? undefined
  clearWait(r, "proposal")
}
// 바꾸자는 안에 응답: 동의하면 새 합의, 이견·거둠이면 지금 합의 유지(안은 이력으로)
export function answerChangeProposal(
  r: Repair,
  role: Role,
  agree: boolean,
  reason = "",
) {
  const cp = r.changeProposal
  if (!cp || cp.by === role)
    throw new Error("상대가 낸 바꾸자는 안에만 답할 수 있어요.")
  if (!agree && !reason.trim()) throw new Error("이견 사유를 입력해 주세요.")
  if (agree) {
    const mismatch = proposalTotalMismatch(r, cp)
    if (mismatch)
      throw new Error(
        `부담 금액의 합(${mismatch.sum.toLocaleString("ko-KR")}원)이 전체 금액(${mismatch.total.toLocaleString("ko-KR")}원)과 달라 동의할 수 없어요.`,
      )
    if (r.proposal)
      r.proposalHistory = [
        ...(r.proposalHistory || []),
        structuredClone({ ...r.proposal, restore: undefined }),
      ]
    r.proposal = { ...cp, status: cp.amount === 0 ? "비용 없음" : "합의됨" }
  } else
    r.proposalHistory = [
      ...(r.proposalHistory || []),
      {
        ...cp,
        status: "이견",
        disputeReason: reason.trim(),
        disputeBy: role,
        closedReason: "이견 · 지금 합의 유지",
      },
    ]
  r.changeProposal = undefined
  clearWait(r, "change")
}
export function withdrawChangeProposal(r: Repair, role: Role) {
  const cp = r.changeProposal
  if (!cp || cp.by !== role)
    throw new Error("응답 전인 내 바꾸자는 안만 거둘 수 있어요.")
  r.proposalHistory = [
    ...(r.proposalHistory || []),
    { ...cp, withdrawn: true, closedReason: "거둠 · 지금 합의 유지" },
  ]
  r.changeProposal = undefined
  clearWait(r, "change")
}
export const lastAgreement = (r: Repair) =>
  r.proposal?.status === "합의됨" || r.proposal?.status === "비용 없음"
    ? undefined
    : [...(r.proposalHistory || [])]
        .reverse()
        .find(
          (h) =>
            !h.withdrawn && (h.status === "합의됨" || h.status === "비용 없음"),
        )
// 지불 합계(업체·제3자에게 낸 돈) — 당사자끼리 주고받은 돈은 제외
export const workTotal = (r: Repair) =>
  (r.payments || [])
    .filter((p) => p.payee !== "임차인" && p.payee !== "임대인")
    .reduce((s, p) => s + p.amount, 0)
export const confirmedWorkTotal = (r: Repair) =>
  (r.payments || [])
    .filter((p) => p.confirmed && p.payee !== "임차인" && p.payee !== "임대인")
    .reduce((s, p) => s + p.amount, 0)
// (자동) 합의됨에서 같이 확인한 지불의 합이 바뀌어 합의 금액과 달라지면(나눠서든 한쪽 전부든) 합의 대기로 돌아가고, 기다리던 바꾸자는 안은 닫혀요(최신 E).
export function recheckAgreement(r: Repair) {
  const p = r.proposal
  if (!p || p.status !== "합의됨") return false
  const total = confirmedWorkTotal(r)
  if (!total || total === p.amount) return false
  if (r.changeProposal) {
    r.proposalHistory = [
      ...(r.proposalHistory || []),
      { ...r.changeProposal, closedReason: "금액이 바뀌어 닫힘" },
    ]
    r.changeProposal = undefined
    clearWait(r, "change")
  }
  r.proposalHistory = [
    ...(r.proposalHistory || []),
    structuredClone({ ...p, restore: undefined }),
  ]
  p.status = "합의 대기"
  p.totalChanged = true
  p.restore = undefined
  p.restoreHistoryLength = undefined
  return true
}

/* ── F 지불 기록 ── */
export const net = (payments: Payment[], side: Role) =>
  payments.reduce(
    (s, p) =>
      s +
      ((p.payer ?? p.by) === side ? p.amount : 0) -
      (p.payee === side ? p.amount : 0),
    0,
  )
export function duplicateCheck(
  r: Repair,
  role: Role,
  payer: Payment["payer"],
  payee: Payment["payee"],
  amount: number,
) {
  const same = (p: Payment) =>
    (p.payer ?? p.by) === payer &&
    (p.payee ?? "업체") === payee &&
    p.amount === amount
  const waiting = r.payments?.find(
    (p) => same(p) && p.by !== role && !p.confirmed,
  )
  // 30일 안 같은 수리 건·방향·금액(상태와 규칙 F). 시각을 읽을 수 없는 기록은 놓치지 않도록 비슷한 기록으로 보고 한 번 묻습니다.
  const recent = r.payments?.find((p) => {
    if (!same(p)) return false
    const ms = recordTimeMs(p.at)
    return ms === undefined || Date.now() - ms < 30 * 864e5
  })
  return { waiting, recent }
}
// 고쳤지만 돈이 남은 건(상태와 규칙 D): 돈 상태가 미정·이견(합의 대기 포함)이거나, 합의됐는데 부담과 순지출(낸 돈 − 상대에게서 받은 돈)이 다른 건.
// 한쪽이 상대 몫까지 냈으면(차이가 정확히 반대) 그 금액을 '돌려줬다면 기록하기'로 안내합니다.
export function moneyLeftDetail(r: Repair) {
  const p = r.proposal
  if (!p || !["합의됨", "비용 없음"].includes(p.status))
    return { kind: "status" as const, status: p?.status || "미정" }
  const b = p.status === "비용 없음" ? { tenant: 0, landlord: 0 } : burden(p)
  const paid = {
    tenant: net(r.payments || [], "임차인"),
    landlord: net(r.payments || [], "임대인"),
  }
  const diffT = paid.tenant - b.tenant,
    diffL = paid.landlord - b.landlord
  if (!diffT && !diffL) return undefined
  const transfer =
    diffT !== 0 && diffT === -diffL
      ? diffT > 0
        ? { from: "임대인" as Role, owedTo: "임차인" as Role, amount: diffT }
        : { from: "임차인" as Role, owedTo: "임대인" as Role, amount: -diffT }
      : undefined
  return { kind: "gap" as const, burden: b, paid, transfer }
}
export function moneyGap(r: Repair) {
  const d = moneyLeftDetail(r)
  return d?.kind === "gap" && d.transfer
    ? { ...d.transfer, burden: d.burden }
    : undefined
}

/* ── G 고치기 (상태와 규칙 G, 요구사항명세 REP-7) ── */
// 도장 전(상대가 아직 답하지 않았거나 상대 미확인): 바로 고쳐지고 고친 이력에 '확인 전에 고침'으로 남아요(REP-7a).
// 도장 뒤(상대가 맞아요·보류·이견으로 답함): 칸마다 '고친 내용'으로 남고 상대가 확인해야 앞모습이 바뀌어요(REP-7b·7c).
// 거절·거둠·미응답이면 지금 기록 그대로. 같은 칸을 다시 고치면 이전 고친 내용은 '대체됨'으로 닫히고 이력에 남아요.
export const repairStamped = (r: Repair) =>
  r.ack === "확인됨" || r.ack === "보충 요청" || r.ack === "이견"
export const paymentStamped = (p: Payment) =>
  p.confirmed || Boolean(p.disputes?.length)
export const CLOSED_BY_BOTH_MESSAGE =
  "둘 다 확인해 끝난 건이에요. 다시 생기면 새 수리 건으로 남겨 주세요."
const closedByBoth = (r: Repair) => r.status === "처리 완료" && !r.soloClosed
// REP-7l: 둘 다 종료를 확인하면 내용(부위·제목·설명)의 확인 전 고친 내용은 닫혀요. 지불을 고친 내용은 그대로예요.
function closeContentCorrections(r: Repair) {
  const at = nowText()
  r.corrections
    ?.filter((c) => c.status === "확인 대기")
    .forEach((c) => {
      c.status = "처리 완료로 닫힘"
      c.decidedAt = at
      clearWait(r, `fix:${c.id}`)
    })
}
function findCorrection(
  r: Repair,
  id: string,
): { c: FactCorrection<string | number>; pay?: Payment } | undefined {
  const c = r.corrections?.find((c) => c.id === id)
  if (c) return { c }
  for (const pay of r.payments || []) {
    const pc = pay.corrections?.find((c) => c.id === id)
    if (pc) return { c: pc, pay }
  }
}
function applyCorrection(
  r: Repair,
  c: FactCorrection<string | number>,
  pay?: Payment,
) {
  bumpVersion(pay ?? r)
  // 이견이던 내용이 바뀌면: 이전 이견은 이 고친 내용의 이력으로 보존하고, 새 내용은 다시 하자 확인을 받아요.
  if (
    !pay &&
    ["제목", "설명", "부위"].includes(c.field) &&
    (r.ack === "이견" || r.ack === "보충 요청")
  ) {
    c.priorDispute = r.ackDispute
      ? `${sideText(r.ackDispute.by)} ${r.ackDispute.type} · ${r.ackDispute.reason} · ${r.ackDispute.at}`
      : "이견"
    r.notes = [
      ...(r.notes || []),
      {
        kind: "이전 내용에 대한 이견(고치기 반영 전)",
        by: r.ackDispute?.by ?? c.by,
        text: c.priorDispute,
        at: nowText(),
      },
    ]
    r.ack = "확인 대기"
    r.ackDispute = undefined
    clearWait(r, "ack")
  }
  if (pay) {
    pay.amount = Number(c.to)
    recheckAgreement(r)
  } else if (c.field === "부위") r.zone = String(c.to)
  else if (c.field === "제목") r.title = String(c.to)
  else if (c.field === "설명") r.description = String(c.to)
  c.status = "반영"
  c.decidedAt = nowText()
}
export function requestCorrection(
  r: Repair,
  _room: Room | undefined,
  role: Role,
  field: string,
  from: string | number,
  to: string | number,
  reason: string,
  pay?: Payment,
) {
  if ((pay ? pay.by : r.author) !== role)
    throw new Error("고치기는 처음 기록한 쪽만 할 수 있어요.")
  if (!pay && closedByBoth(r)) throw new Error(CLOSED_BY_BOTH_MESSAGE)
  if (from === to) throw new Error("지금 기록과 같아요.")
  const stamped = pay ? paymentStamped(pay) : repairStamped(r)
  if (stamped && !reason.trim()) throw new Error("왜 바꾸는지 적어 주세요.")
  const list = (pay ? pay.corrections : r.corrections) || []
  if (
    list.some(
      (c) => c.status === "확인 대기" && c.field === field && c.to === to,
    )
  )
    throw new Error("같은 내용으로 고친 내용이 이미 확인을 기다리고 있어요.")
  const at = nowText()
  list
    .filter((c) => c.status === "확인 대기" && c.field === field)
    .forEach((c) => {
      c.status = "대체됨"
      c.decidedAt = at
      clearWait(r, `fix:${c.id}`)
    })
  const c: FactCorrection<string | number> = {
    id: uid(),
    by: role,
    field,
    from,
    to,
    reason: reason.trim(),
    at,
    status: "확인 대기",
    baseVersion: versionOf(pay ?? r),
  }
  if (pay)
    pay.corrections = [...(pay.corrections || []), c as FactCorrection<number>]
  else r.corrections = [...(r.corrections || []), c as FactCorrection<string>]
  if (!stamped) {
    // 도장 전: 바로 바뀌고 확인 요청은 생기지 않아요(REP-7a).
    c.mode = "확인 전"
    applyCorrection(r, c, pay)
  }
  return c
}
// [고치기] 한 번에 여러 칸: 바뀐 칸마다 고친 내용 하나(확인 요청은 기록 하나로 묶어 보여 줘요)
export function requestRepairEdits(
  r: Repair,
  role: Role,
  next: { title: string; description: string; zone: string },
  reason: string,
) {
  if (closedByBoth(r)) throw new Error(CLOSED_BY_BOTH_MESSAGE)
  const title = next.title.trim(),
    description = next.description.trim()
  if (!title) throw new Error("제목을 입력해 주세요.")
  // 지금 기록과 같거나, 이미 같은 값으로 확인을 기다리는 칸은 건너뜁니다.
  const waiting = (field: string, to: string) =>
    r.corrections?.some(
      (c) => c.status === "확인 대기" && c.field === field && c.to === to,
    )
  const changes = (
    [
      ["제목", r.title, title],
      ["설명", r.description, description],
      ["부위", r.zone, next.zone],
    ] as const
  ).filter(([f, a, b]) => a !== b && !waiting(f, b))
  if (!changes.length) throw new Error("바뀐 내용이 없어요.")
  return changes.map(([field, from, to]) =>
    requestCorrection(r, undefined, role, field, from, to, reason),
  )
}
export function answerCorrection(
  r: Repair,
  role: Role,
  id: string,
  accept: boolean,
  reason = "",
) {
  const found = findCorrection(r, id)
  // 같은 칸을 다시 고쳐 이 요청이 닫혔으면, 보던 내용과 달라졌으니 다시 보고 확인해요(REP-7j).
  if (found?.c.status === "대체됨") throw new Error(VERSION_CONFLICT_MESSAGE)
  if (found?.c.status === "처리 완료로 닫힘")
    throw new Error(CLOSED_BY_BOTH_MESSAGE)
  if (!found || found.c.status !== "확인 대기" || found.c.by === role)
    throw new Error("상대가 고친 확인 대기 내용에만 답할 수 있어요.")
  if (!accept && !reason.trim()) throw new Error("거절 사유를 입력해 주세요.")
  found.c.decidedBy = role
  if (accept) {
    found.c.mode = "상대 확인"
    if (found.pay) found.pay.confirmed = true
    applyCorrection(r, found.c, found.pay)
  } else {
    found.c.status = "거절"
    found.c.rejectReason = reason.trim()
    found.c.decidedAt = nowText()
  }
  clearWait(r, `fix:${id}`)
}
// [모두 확인]: 수리 건에 상대가 고친 확인 대기 내용을 칸마다 확인 행동으로 저장
export function acceptAllCorrections(r: Repair, role: Role) {
  const ids = (r.corrections || [])
    .filter((c) => c.status === "확인 대기" && c.by !== role)
    .map((c) => c.id)
  if (!ids.length) throw new Error("확인할 고친 내용이 없어요.")
  ids.forEach((id) => answerCorrection(r, role, id, true))
}
export function withdrawCorrection(r: Repair, role: Role, id: string) {
  const found = findCorrection(r, id)
  if (!found || found.c.status !== "확인 대기" || found.c.by !== role)
    throw new Error("상대가 답하기 전인 내 고친 내용만 거둘 수 있어요.")
  found.c.status = "철회"
  found.c.decidedAt = nowText()
  clearWait(r, `fix:${id}`)
}

/* ── 덧붙이기: 사진 추가와 잘못 올린 사진 표시(G·PHO-9) ── */
// 사진은 바꾸지 않고 더하기만 해요. 같이 확인한 내용을 바꾸지 않으니 상대 확인 없이 남고 '확인 뒤 추가'로 표시돼요.
export function addRepairPhotos(r: Repair, role: Role, photos: string[]) {
  if (r.author !== role)
    throw new Error("처음 기록한 쪽만 이 기록에 사진을 더할 수 있어요.")
  if (!photos.length) throw new Error("더할 사진을 골라 주세요.")
  const afterConfirm =
    r.ack === "확인됨" || (r.status === "처리 완료" && !r.soloClosed)
  r.photos = [...r.photos, ...photos]
  r.notes = [
    ...(r.notes || []),
    {
      kind: afterConfirm ? "사진 추가 · 확인 뒤 추가" : "사진 추가",
      by: role,
      text: `사진 ${photos.length}장을 더했어요. 기존 사진은 그대로 남아요.`,
      at: nowText(),
    },
  ]
}
export function toggleWrongPhoto(r: Repair, role: Role, index: number) {
  if (r.author !== role)
    throw new Error("올린 사람만 잘못 올린 사진 표시를 붙이거나 뗄 수 있어요.")
  r.wrongPhotos = r.wrongPhotos?.includes(index)
    ? r.wrongPhotos.filter((n) => n !== index)
    : [...(r.wrongPhotos || []), index]
}

/* ── 사진별 링크 숨김 ── */
export const isHidden = (r: Repair, key: string) =>
  Boolean(r.hidden) || Boolean(r.hiddenPhotos?.includes(key))
export function togglePhoto(r: Repair, key: string) {
  r.hiddenPhotos = r.hiddenPhotos?.includes(key)
    ? r.hiddenPhotos.filter((k) => k !== key)
    : [...(r.hiddenPhotos || []), key]
}
export const counterpartOf = (role: Role) => other(role)

// (자동) 수리 건 변경(상태와 규칙 I): 수리 건 출처 정산 항목은 서버가 다시 확인 대기로 돌리고 이전 합의는 이력으로 남깁니다.
// 제안자(by)는 바꾸지 않고, 실제로 수리 건을 바꾼 쪽은 autoChange.changedBy로 따로 남깁니다.
// 수리 건 돈 상태가 미정·합의 대기·이견이면 금액을 정할 수 없으므로 0원으로 바꾸지 않습니다.
// 이전 금액은 그대로 두고 '금액 미정(pending)'으로 표시하며, 합의된 항목이 아니라서 정산 계산에도 들어가지 않아요.
export function syncRepairSettlements(
  d: {
    repairs: Repair[]
    settlements: import("@/data/demo").Settlement[]
    events: import("@/data/demo").Event[]
    notices: import("@/data/demo").Notice[]
  },
  repairId: string,
  changedBy: Role,
) {
  const r = d.repairs.find((x) => x.id === repairId)
  if (!r) return
  const detail = moneyLeftDetail(r)
  const undecided =
    detail?.kind === "status"
      ? `돈 상태 ${detail.status}`
      : detail?.kind === "gap" && !detail.transfer
        ? "부담과 지불이 맞지 않음"
        : undefined
  const gap = moneyGap(r)
  const won = (n: number) => n.toLocaleString("ko-KR")
  d.settlements
    .filter((s) => s.repairId === repairId && !s.excluded && !s.withdrawn)
    .forEach((s) => {
      const at = nowText()
      if (undecided) {
        // 이미 금액 미정으로 기다리는 중이면 현재 상태 문구만 갱신(이력·알림을 반복해서 쌓지 않음)
        if (s.autoChange?.pending) {
          s.autoChange.moneyStatus = undecided
          return
        }
        const old = structuredClone(s)
        delete old.history
        s.history = [...(s.history || []), old]
        s.autoChange = {
          from: old.amount,
          fromStatus: old.status,
          at,
          changedBy,
          pending: true,
          moneyStatus: undecided,
        }
        s.status = "합의 대기"
        s.disputeReason = undefined
        s.disputeBy = undefined
        d.events.unshift({
          id: uid(),
          roomId: s.roomId,
          text: `(자동) ${s.title} · 수리 건 ${undecided} · 금액이 정해질 때까지 다시 확인 대기(${old.status === "합의됨" ? "이전 합의" : "이전 제안"} ${won(old.amount)})`,
          by: "서버 자동",
          at,
        })
        d.notices.unshift({
          id: uid(),
          roomId: s.roomId,
          title: `${s.title} · 수리 건 부담 합의가 다시 열려 정산 금액이 미정이에요`,
          to: other(s.by),
          read: false,
        })
        return
      }
      const amount = gap?.amount ?? 0
      const direction: "가산" | "차감" = gap
        ? gap.owedTo === "임대인"
          ? "차감"
          : "가산"
        : s.direction
      if (s.autoChange?.pending) {
        // 수리 건 금액이 다시 정해짐: 이전 금액 → 새 금액, 확인 대기 유지(이전 합의는 이미 이력에 있음)
        const from = s.autoChange.from
        s.autoChange = {
          from,
          fromStatus: s.autoChange.fromStatus,
          to: amount,
          at,
          changedBy,
        }
        s.amount = amount
        s.direction = direction
        s.status = "합의 대기"
        d.events.unshift({
          id: uid(),
          roomId: s.roomId,
          text: `(자동) ${s.title} · 수리 건 금액이 정해져 다시 확인 대기 · ${won(from)} → ${won(amount)}`,
          by: "서버 자동",
          at,
        })
        d.notices.unshift({
          id: uid(),
          roomId: s.roomId,
          title: `${s.title} · 수리 건 금액이 정해져 정산 항목을 다시 확인해 주세요`,
          to: other(s.by),
          read: false,
        })
        return
      }
      if (s.amount === amount && s.direction === direction) return
      const old = structuredClone(s)
      delete old.history
      s.history = [...(s.history || []), old]
      s.autoChange = {
        from: old.amount,
        fromStatus: old.status,
        to: amount,
        at,
        changedBy,
      }
      s.amount = amount
      s.direction = direction
      s.status = "합의 대기"
      s.disputeReason = undefined
      s.disputeBy = undefined
      d.events.unshift({
        id: uid(),
        roomId: s.roomId,
        text: `(자동) ${s.title} · 수리 건 변경으로 다시 확인 대기 · ${won(old.amount)} → ${won(amount)}`,
        by: "서버 자동",
        at,
      })
      d.notices.unshift({
        id: uid(),
        roomId: s.roomId,
        title: `${s.title} · 수리 건 금액이 바뀌어 정산 항목을 다시 확인해 주세요`,
        to: other(s.by),
        read: false,
      })
    })
}
