import type { Repair, RepairWait, Role } from "@/data/demo"
import { sideName } from "@/lib/words"

// 이 수리 건에서 지금 내가 답할 일(홈 S-03 '내가 답할 것'과 같은 기준). 없으면 undefined.
// 판단은 하지 않고, 상대가 나에게 요청해 둔 응답만 찾아요. 버튼 노출 조건과 같은 규칙을 써요.
// 서버 목록의 열린 응답 요청(waiting, 06 4-6)이 있으면 하자·종료·바뀐 상황·진행 상황·비용 제안은 그 값으로 정해요
// (확인 없음이어도 늦게 답할 수 있어 내 차례는 그대로, 03 1-4). 서버 목록에 없는 고친 내용·지불 금액 확인은 상태로 셉니다.
export function myTurn(r: Repair, role: Role): string | undefined {
  if (r.waiting) {
    const mine = r.waiting.find((w) => w.by === role)
    if (mine) return mine.kind
  } else {
    const open = r.status !== "처리 완료" || Boolean(r.soloClosed)
    if (
      r.author !== role &&
      open &&
      (r.ack === "확인 대기" || r.ack === "상대 미확인")
    )
      return "하자 확인"
    if (r.closeBy && r.closeBy !== role && r.status !== "처리 완료")
      return "종료 확인"
  }
  if (r.corrections?.some((c) => c.status === "확인 대기" && c.by !== role))
    return "고친 내용 확인"
  if (
    !r.waiting &&
    r.proposal?.status === "합의 대기" &&
    r.proposal.by !== role
  )
    return "비용 제안 응답"
  if (r.changeProposal && r.changeProposal.by !== role)
    return "바꾸자는 안 응답"
  if (
    r.payments?.some(
      (p) =>
        p.by !== role &&
        (!p.confirmed ||
          p.corrections?.some(
            (c) => c.status === "확인 대기" && c.by !== role,
          )),
    )
  )
    return "지불 금액 확인"
  return undefined
}

const HOUR = 36e5
// 기한까지 남은 시간("2일 남음"·"5시간 남음"). 지났으면 undefined(서버가 곧 확인 없음으로 바꿔요)
function timeLeft(dueAt: string, now: number) {
  const ms = Date.parse(dueAt) - now
  if (Number.isNaN(ms) || ms <= 0) return undefined
  const hours = Math.max(1, Math.floor(ms / HOUR))
  return hours >= 24 ? `${Math.floor(hours / 24)}일 남음` : `${hours}시간 남음`
}

// 상대가 답할 차례인 요청 한 줄(06 4-6 waiting): "종료 확인 · 집주인 확인 기다림 · 2일 남음",
// 기한이 지났으면 "종료 확인 · 집주인 확인 없음 · 이유"(03 4장 "'확인 없음' 화면 표시")
export function waitText(w: RepairWait, now = Date.now()) {
  const who = sideName(w.by)
  if (w.unconfirmed)
    return [w.kind, `${who} 확인 없음`, w.reason].filter(Boolean).join(" · ")
  const left = w.dueAt ? timeLeft(w.dueAt, now) : undefined
  return [w.kind, `${who} 확인 기다림`, left].filter(Boolean).join(" · ")
}

// 이 수리 건에서 상대의 답을 기다리는 요청들. 서버 값이 없으면(목업) 빈 목록
export function theirWaits(r: Repair, role: Role, now = Date.now()) {
  return (r.waiting ?? [])
    .filter((w) => w.by !== role)
    .map((w) => waitText(w, now))
}
