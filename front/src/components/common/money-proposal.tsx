import type { ReactNode } from "react"
import { formatWon as won } from "@/lib/money"

// 돈 안건 카드(09 5장 "같은 뜻은 한 부품", 03 E 돈 안건 장치). 수리비 나누기·보증금·정산 항목이 같이 써요.
// 버튼 문구는 안건마다 문서가 따로 정해서(보증금은 [맞아요]·[아니에요 · 다른 금액], 08 10장) 쓰는 쪽이 children으로 넣어요.

// 지금 답을 기다리거나 합의된 안
export function CurrentProposal({
  head,
  amount,
  detail,
  reason,
  children,
}: {
  head: string
  amount: number
  detail?: ReactNode
  reason?: ReactNode
  children?: ReactNode
}) {
  return (
    <div className="proposal">
      <small>{head}</small>
      <strong>{won(amount)}</strong>
      {detail && <span>{detail}</span>}
      {reason && <p>{reason}</p>}
      {children}
    </div>
  )
}

// 이전 안 한 줄(이력). 지난 안은 지우지 않고 아래에 쌓아요(03 E).
export function PastProposal({
  head,
  children,
}: {
  head: ReactNode
  children?: ReactNode
}) {
  return (
    <div className="note">
      <strong>{head}</strong>
      {children}
    </div>
  )
}

// 상대가 남긴 이견과 사유
export function ProposalDispute({
  by,
  reason,
  title,
}: {
  by?: string
  reason: string
  // 제목 줄을 따로 보일 때(예: "임대인의 비용 이견")
  title?: string
}) {
  return (
    <div className="dispute-callout">
      {title ? (
        <>
          <strong>{title}</strong>
          <p>{reason}</p>
        </>
      ) : (
        <>
          {by} 이견: {reason}
        </>
      )}
    </div>
  )
}
