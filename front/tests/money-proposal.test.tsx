import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import {
  CurrentProposal,
  PastProposal,
  ProposalDispute,
} from "../src/components/common/money-proposal"
import { proposalHead } from "../src/lib/words"

describe("돈 안건 카드(09 5장 공통 부품)", () => {
  it("머리 줄은 회차·쪽·덧붙일 것을 가운데 점으로 이어요", () => {
    expect(proposalHead("현재 제안", undefined, "세입자 쪽", undefined)).toBe(
      "현재 제안 1회 · 세입자 쪽",
    )
    expect(proposalHead("이전 제안", 2, "집주인", "50,000원", "이견")).toBe(
      "이전 제안 2회 · 집주인 · 50,000원 · 이견",
    )
  })
  it("현재 안은 금액·설명·사유와 이견을 함께 보여요", () => {
    render(
      <CurrentProposal
        head="현재 제안 1회"
        amount={65000}
        detail="나눠서"
        reason="반반"
      >
        <ProposalDispute by="임대인" reason="금액이 커요" />
      </CurrentProposal>,
    )
    expect(screen.getByText("현재 제안 1회")).toBeTruthy()
    expect(screen.getByText("나눠서")).toBeTruthy()
    expect(screen.getByText("반반")).toBeTruthy()
    expect(screen.getByText(/임대인 이견: 금액이 커요/)).toBeTruthy()
  })
  it("이전 안은 지우지 않고 이력으로 보여요", () => {
    render(<PastProposal head="이전 제안 1회 · 거둠" />)
    expect(screen.getByText("이전 제안 1회 · 거둠")).toBeTruthy()
  })
})
