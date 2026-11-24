import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Choice } from "@/components/common/blocks"
import { Notice } from "@/components/common/notice"
import { MoneyInput } from "@/components/common/money-input"
import { FormDialog } from "@/components/common/form-dialog"
import { post, settlementIdOf } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { uid, type Role } from "@/data/demo"
import { parseMoney } from "@/lib/money"
import type { Commit } from "@/lib/record-commands"

// 정산 항목 제안(SET-2). 상대가 동의하기 전에는 계산한 금액에 넣지 않아요.
// zones: 원상복구로 이어 볼 수 있는 이 호실의 부위(점검·부위 사진·수리 건)
export function SettlementItemForm({
  roomId,
  role,
  zones,
  commit,
  onCancel,
}: {
  roomId: string
  role: Role
  zones: string[]
  commit: Commit
  onCancel: () => void
}) {
  const [direction, setDirection] = useState("차감")
  const [zone, setZone] = useState("연결 안 함")
  const [error, setError] = useState("")
  return (
    <FormDialog
      title="정산 항목 추가"
      error={error}
      onError={setError}
      onCancel={onCancel}
      submitLabel="제안 보내기"
      onSubmit={(text) => {
        const amount = parseMoney(text("amount"))
        if (!Number.isSafeInteger(amount) || amount < 0) {
          setError("금액은 0 이상의 정수로 입력해 주세요.")
          return
        }
        const zoneOfItem = zone === "연결 안 함" ? undefined : zone
        commit(
          post(endpoints.settlementAction(settlementIdOf(roomId), "items"), {
            title: text("title"),
            amount,
            direction,
            reason: text("description"),
            zone: zoneOfItem,
          }),
          `${text("title")} · 정산 항목 제안`,
          (d) =>
            d.settlements.push({
              id: uid(),
              roomId,
              title: text("title"),
              amount,
              direction: direction as "가산" | "차감",
              status: "합의 대기",
              by: role,
              reason: text("description"),
              zone: zoneOfItem,
            }),
        )
      }}
    >
      <div className="field">
        <label htmlFor="title">정산 항목</label>
        <Input
          id="title"
          name="title"
          placeholder="예: 퇴거 월 관리비"
          required
        />
      </div>
      <div className="form-grid">
        <div className="field">
          <label htmlFor="amount">금액 (원)</label>
          <MoneyInput id="amount" name="amount" required />
        </div>
        <div className="field">
          <label>계산 방향</label>
          <Choice
            value={direction}
            values={["차감", "가산"]}
            onChange={setDirection}
            label="계산 방향"
          />
        </div>
      </div>
      <div className="field">
        <label htmlFor="description">제안 사유</label>
        <Textarea id="description" name="description" required />
      </div>
      <div className="field">
        <label>원상복구 부위 연결 · 선택</label>
        <Choice
          label="원상복구 부위"
          value={zone}
          values={["연결 안 함", ...zones]}
          onChange={setZone}
        />
      </div>
      <Notice>상대가 동의하기 전에는 계산한 금액에 반영하지 않아요.</Notice>
    </FormDialog>
  )
}
