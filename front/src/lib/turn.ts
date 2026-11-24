import type { Repair, Role } from "@/data/demo"

// 이 수리 건에서 지금 내가 답할 일(홈 S-03 '내가 답할 것'과 같은 기준). 없으면 undefined.
// 판단은 하지 않고, 상대가 나에게 요청해 둔 응답만 찾아요. 버튼 노출 조건과 같은 규칙을 써요.
export function myTurn(r: Repair, role: Role): string | undefined {
  const open = r.status !== "처리 완료" || Boolean(r.soloClosed)
  if (
    r.author !== role &&
    open &&
    (r.ack === "확인 대기" || r.ack === "상대 미확인")
  )
    return "하자 확인"
  if (r.closeBy && r.closeBy !== role && r.status !== "처리 완료")
    return "종료 확인"
  if (r.corrections?.some((c) => c.status === "확인 대기" && c.by !== role))
    return "고친 내용 확인"
  if (r.proposal?.status === "합의 대기" && r.proposal.by !== role)
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
