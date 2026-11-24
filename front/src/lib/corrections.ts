import type { FactCorrection } from "@/data/demo"
import { assertCanReask, newEvidence } from "@/lib/reask"

const nowText = () =>
  new Date().toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })

type C = FactCorrection<string | number>
// 한 번의 [고치기]로 남긴 칸별 고친 내용을 한 요청으로 묶어요(같은 사람·같은 시각·같은 이유).
// 확인·거절은 칸마다 따로 처리하는 기존 규칙을 그대로 두고, 화면에서만 '고치기 요청 1건 · 변경 항목 3개'처럼 보여 줘요.
export interface CorrectionGroup {
  key: string
  by: C["by"]
  at: string
  reason: string
  items: C[]
}
export function groupCorrections(list: C[]): CorrectionGroup[] {
  const groups: CorrectionGroup[] = []
  for (const c of list) {
    const key = `${c.by}|${c.at}|${c.reason}`
    const g = groups.find((x) => x.key === key)
    if (g) g.items.push(c)
    else groups.push({ key, by: c.by, at: c.at, reason: c.reason, items: [c] })
  }
  return groups
}
// 요청 하나의 상태: 칸이 하나라도 기다리면 '상대 확인 전', 모두 끝났으면 칸별 결과를 모아 한 줄로
export function groupStatus(g: CorrectionGroup) {
  const s = g.items.map((c) => c.status)
  if (s.includes("확인 대기"))
    return s.every((x) => x === "확인 대기")
      ? "상대 확인 전"
      : "일부 응답 · 상대 확인 전"
  if (g.items.every((c) => c.mode === "확인 전")) return "확인 전에 고침"
  if (s.every((x) => x === "반영")) return "고쳐짐"
  if (s.every((x) => x === "거절")) return "동의하지 않음 · 지금 기록 그대로"
  if (s.every((x) => x === "철회")) return "거둠 · 지금 기록 그대로"
  if (s.every((x) => x === "대체됨")) return "새로 고쳐서 바뀜"
  if (s.every((x) => x === "처리 완료로 닫힘")) return "처리 완료로 닫힘"
  const n = (v: C["status"]) => s.filter((x) => x === v).length
  return [
    n("반영") && `고쳐짐 ${n("반영")}`,
    n("거절") && `동의하지 않음 ${n("거절")}`,
    n("철회") && `거둠 ${n("철회")}`,
    n("대체됨") && `새로 고침 ${n("대체됨")}`,
    n("처리 완료로 닫힘") && `처리 완료로 닫힘 ${n("처리 완료로 닫힘")}`,
  ]
    .filter(Boolean)
    .join(" · ")
}

// 기록당 확인 요청은 하나(상태와 규칙 G, REP-7h): 확인을 기다리는 칸들을 요청 하나로 묶어 보여 줘요.
// 칸마다 기다리는 고친 내용은 하나뿐이에요(같은 칸을 다시 고치면 이전 것은 '대체됨', 저장 규칙 3).
export function pendingRequest(list: C[]): CorrectionGroup | undefined {
  const items = list.filter((c) => c.status === "확인 대기")
  if (!items.length) return undefined
  const reasons = Array.from(new Set(items.map((c) => c.reason)))
  const last = items[items.length - 1]
  return {
    key: "pending",
    by: last.by,
    at: last.at,
    reason: reasons.length === 1 ? reasons[0] : "",
    items,
  }
}
// 칸별로 지금 확인을 기다리는 값. 다시 고치기 창을 열 때 미리 채워요.
export const pendingValue = (list: C[] | undefined, field: string) =>
  list?.filter((c) => c.status === "확인 대기" && c.field === field).at(-1)?.to

// 고친 내용 응답(03 G표, 08 3장 장면 3·4): 상대가 [아니에요 · 더 필요해요]로 답하면 앞모습은 그대로이고
// 고친 내용은 보류·이견으로 나란히 남아요(상태 '거절' + 종류). 고친 쪽은 [사진 더해서 다시 요청]을 할 수 있어요.
export function declineCorrection(
  c: C,
  role: C["by"],
  kind: "보류" | "이견",
  reason: string,
) {
  if (!reason.trim()) throw new Error("사유를 입력해 주세요.")
  c.status = "거절"
  c.answerKind = kind
  c.rejectReason = reason.trim()
  c.decidedBy = role
  c.decidedAt = nowText()
  c.wait = undefined
}
// [사진 더해서 다시 요청]: 같은 고친 내용에 새 근거(사진·설명 하나 이상)를 더해 다시 확인을 받아요. 한 기록에 7일에 한 번.
// 그사이 같은 칸을 새로 고쳤으면(더 나중 고친 내용이 있으면) 다시 요청하지 않아요.
export function resubmitCorrection(
  c: C,
  role: C["by"],
  list: C[],
  data: { text: string; photos: string[] },
  now = Date.now(),
) {
  if (c.by !== role) throw new Error("고친 쪽만 다시 요청할 수 있어요.")
  if (c.status !== "거절")
    throw new Error("동의받지 못한 고친 내용만 다시 요청할 수 있어요.")
  // 고친 내용은 남긴 순서대로 쌓여요. 같은 칸에 뒤에 남긴 것이 있으면 이건 지난 고친 내용이에요.
  const newer = list.slice(list.indexOf(c) + 1).some((x) => x.field === c.field)
  if (newer)
    throw new Error(
      "같은 칸을 새로 고쳤어요. 새 고친 내용으로 확인을 받아 주세요.",
    )
  const { text, photos } = newEvidence(data, {
    texts: [c.reason, ...(c.supplements || []).map((s) => s.text)],
    photos: c.evidencePhotos || [],
  })
  assertCanReask(c.reaskedAt, now)
  c.answers = [
    ...(c.answers || []),
    {
      kind: c.answerKind,
      reason: c.rejectReason || "",
      by: c.decidedBy,
      at: c.decidedAt,
    },
  ]
  c.supplements = [
    ...(c.supplements || []),
    { text, photos: photos.length, at: nowText() },
  ]
  c.evidencePhotos = [...(c.evidencePhotos || []), ...photos]
  c.status = "확인 대기"
  c.answerKind = undefined
  c.rejectReason = undefined
  c.decidedBy = undefined
  c.decidedAt = undefined
  c.reaskedAt = new Date(now).toISOString()
}
