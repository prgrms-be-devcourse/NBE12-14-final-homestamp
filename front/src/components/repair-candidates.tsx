import { Button } from "@/components/ui/button"
import { Panel, Status } from "@/components/prototype"
import {
  uid,
  won,
  type DemoState,
  type Repair,
  type Role,
  type Settlement,
} from "@/data/demo"
import { moneyGap } from "@/lib/repair-rules"

// 퇴거 정산(시나리오 4의 5·8단계): 열린 수리 건을 먼저 보여 주고, 합의된 부담과 순지출의 차이를 정산 후보로 넣습니다.
// 넣는 것도 제안이라 상대 확인이 필요합니다(기존 정산 항목 동의·이견 흐름을 그대로 씁니다).
export function RepairCandidates({
  repairs,
  settlements,
  role,
  readOnly,
  onOpen,
  onAct,
}: {
  repairs: Repair[]
  settlements: Settlement[]
  role: Role
  readOnly: boolean
  onOpen: (id: string) => void
  onAct: (text: string, update: (d: DemoState) => void) => void
}) {
  const open = repairs.filter(
    (r) =>
      r.status !== "처리 완료" ||
      !r.proposal ||
      !["합의됨", "비용 없음"].includes(r.proposal.status),
  )
  const candidates = repairs
    .map((r) => ({ r, gap: moneyGap(r) }))
    .filter(
      (x): x is { r: Repair; gap: NonNullable<ReturnType<typeof moneyGap>> } =>
        Boolean(x.gap),
    )
  if (!open.length && !candidates.length) return null
  return (
    <Panel className="mb-5" title="수리 건 · 정산 후보">
      {open.length > 0 && (
        <>
          <p className="muted mb-3">
            아직 끝나지 않은 수리 건이에요. 수리 상태나 돈 상태가 정리되지
            않았어요.
          </p>
          {open.map((r) => (
            <div className="actions mb-2" key={r.id}>
              <strong>{r.title}</strong>
              <Status>{r.status}</Status>
              <Status>돈 · {r.proposal?.status || "미정"}</Status>
              <Button size="sm" variant="ghost" onClick={() => onOpen(r.id)}>
                보기
              </Button>
            </div>
          ))}
        </>
      )}
      {candidates.length > 0 && (
        <>
          <p className="muted my-3">
            수리 건에서 온 후보 · 합의된 부담과 실제 순지출의 차이예요. 넣으면
            상대에게 확인 요청이 가요.
          </p>
          {candidates.map(({ r, gap }) => {
            const added = settlements.find((s) => s.repairId === r.id)
            return (
              <div className="actions mb-2" key={r.id}>
                <strong>{r.title}</strong>
                <span>
                  {gap.owedTo === "임차인" ? "+" : "−"}
                  {won(gap.amount)} · {gap.from} → {gap.owedTo}
                </span>
                {added ? (
                  <Status>{`정산 항목에 넣음 · ${added.status}`}</Status>
                ) : (
                  !readOnly && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        onAct(
                          `${r.title} · 수리 건 후보 정산 항목 넣기 제안`,
                          (d) => {
                            d.settlements.push({
                              id: uid(),
                              roomId: r.roomId,
                              title: `${r.title} · 수리비 정리`,
                              amount: gap.amount,
                              direction:
                                gap.owedTo === "임차인" ? "가산" : "차감",
                              status: "합의 대기",
                              by: role,
                              reason: `수리 건에서 온 후보 · 합의된 부담과 순지출 차이(${gap.from} → ${gap.owedTo})`,
                              repairId: r.id,
                            })
                          },
                        )
                      }
                    >
                      넣기
                    </Button>
                  )
                )}
              </div>
            )
          })}
        </>
      )}
    </Panel>
  )
}
