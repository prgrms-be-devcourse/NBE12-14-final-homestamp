import type { Payment, Repair, Role, Settlement } from "../data/demo"
import { burdenText } from "./workflows"

export function settlementTotal(deposit: number, items: Settlement[]) {
  return (
    deposit +
    items
      .filter((item) => item.status === "합의됨")
      .reduce(
        (sum, item) =>
          sum + (item.direction === "가산" ? item.amount : -item.amount),
        0,
      )
  )
}
export function hasUnsettledMoney(repair: Repair) {
  const proposal = repair.proposal
  if (!proposal || !["합의됨", "비용 없음"].includes(proposal.status))
    return true
  const b =
    proposal.status === "비용 없음"
      ? { tenant: 0, landlord: 0 }
      : burdenText(proposal)
  return (
    netExpenditure(repair.payments || [], "임차인") !== b.tenant ||
    netExpenditure(repair.payments || [], "임대인") !== b.landlord
  )
}
// by는 기록한 쪽, payer/payee는 실제 돈이 이동한 방향입니다.
// 이전 목업의 payer/payee 없는 기록은 by 쪽 → 업체 지불로 해석합니다.
export function netExpenditure(payments: Payment[], side: Role) {
  return payments.reduce(
    (sum, p) =>
      sum +
      ((p.payer ?? p.by) === side ? p.amount : 0) -
      (p.payee === side ? p.amount : 0),
    0,
  )
}
// 주민등록번호 형태(000000-0000000) 입력 차단(제24조의2, 기획서 239).
// 앞 6자리가 실제 날짜(월 01~12, 일 01~31)이고 하이픈 뒤 첫 자리가 1~8, 앞뒤에 다른 숫자가 붙지 않은 13자리만 막아요.
// 하이픈 없는 긴 숫자(계좌·주문 번호 등)와 같은 숫자 반복(111111-1111111 등)은 막지 않아요.
const RESIDENT_ID =
  /(?<!\d)(\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))\s*-\s*([1-8]\d{6})(?!\d)/g
export function hasResidentId(text: string) {
  for (const m of text.matchAll(RESIDENT_ID))
    if (!/^(\d)\1*$/.test(m[1] + m[2])) return true
  return false
}
export const RESIDENT_ID_MESSAGE = "주민등록번호 형태는 적을 수 없어요."
export function safeSharedText(text: string) {
  return text.replace(/01[016789][ -]?\d{3,4}[ -]?\d{4}/g, "[연락처 가림]")
}
