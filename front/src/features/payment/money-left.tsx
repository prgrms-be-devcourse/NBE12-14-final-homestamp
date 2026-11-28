import { Wallet } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Status } from "@/components/common/blocks"
import { won, type Repair, type Role } from "@/data/demo"
import { moneyLeftDetail } from "@/lib/repair-rules"
import { statusText } from "@/lib/plain-words"
import { sideName, withSubject } from "@/lib/words"

// '고쳤지만 돈이 남은 건' 안내(상태와 규칙 D). 왜 남았는지와, 한쪽이 상대 몫까지 냈다면 '돌려줬다면 기록하기 [보냈어요] [받았어요]'를 보여 줍니다(문자 없음).
// onRecord: 수리 상세에서 지불 기록 창을 미리 채워 엽니다. 홈 목록에서는 해당 수리 건 상세로 이동해 같은 창을 엽니다.
export function MoneyLeftNotice({
  r,
  role,
  readOnly,
  onRecord,
}: {
  r: Repair
  role: Role
  readOnly: boolean
  onRecord?: (from: Role, to: Role, amount: number) => void
}) {
  const d = r.status === "처리 완료" ? moneyLeftDetail(r) : undefined
  if (!d) return null
  return (
    <div className="info-box">
      <Wallet size={20} />
      <div className="space-y-1">
        <p>
          <strong>수리는 끝났고 수리비가 남았어요</strong>{" "}
          {/* 수리비 상태는 카드·수리비 줄에 이미 꼬리표로 있어서 여기서는 다른 경우만 꼬리표로 보여요 */}
          {d.kind !== "status" && <Status>정한 몫과 낸 돈이 달라요</Status>}
        </p>
        {d.kind === "status" ? (
          <p>
            수리비는 아직 ‘{statusText(d.status)}’예요. 같은 나누기 안에 둘 다
            동의하거나 ‘비용 없음’에 동의하면 끝나요.
          </p>
        ) : (
          <>
            <p>
              정한 몫 · 세입자 {won(d.burden.tenant)} / 집주인{" "}
              {won(d.burden.landlord)}
            </p>
            <p>
              실제로 낸 돈(상대에게 받은 돈은 뺌) · 세입자 {won(d.paid.tenant)}{" "}
              / 집주인 {won(d.paid.landlord)}
            </p>
            {d.transfer ? (
              <>
                <p>
                  <strong>
                    {sideName(d.transfer.from)} 몫을{" "}
                    {withSubject(sideName(d.transfer.owedTo))} 냈어요.{" "}
                    {won(d.transfer.amount)}을 돌려줬다면 기록해 주세요.
                  </strong>
                </p>
                {!readOnly && onRecord && (
                  <div className="actions">
                    {role === d.transfer.from && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() =>
                          onRecord(
                            d.transfer!.from,
                            d.transfer!.owedTo,
                            d.transfer!.amount,
                          )
                        }
                      >
                        보냈어요
                      </Button>
                    )}
                    {role === d.transfer.owedTo && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() =>
                          onRecord(
                            d.transfer!.from,
                            d.transfer!.owedTo,
                            d.transfer!.amount,
                          )
                        }
                      >
                        받았어요
                      </Button>
                    )}
                  </div>
                )}
              </>
            ) : (
              <p>
                정한 몫과 실제로 낸 돈이 달라요. 낸 돈이 있으면 지불 기록을
                남기고, 금액이 다르면 수리비 제안이나 지불 금액 고치기를 확인해
                주세요.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  )
}
