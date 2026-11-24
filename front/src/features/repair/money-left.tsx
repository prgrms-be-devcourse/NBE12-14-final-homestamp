import { Wallet } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Status } from "@/components/prototype"
import { won, type Repair, type Role } from "@/data/demo"
import { moneyLeftDetail } from "@/lib/repair-rules"

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
          <strong>고쳤지만 돈이 남은 건</strong>{" "}
          <Status>
            {d.kind === "status"
              ? `돈 · ${d.status}`
              : "부담과 순지출이 달라요"}
          </Status>
        </p>
        {d.kind === "status" ? (
          <p>
            수리는 끝났지만 돈 상태가 ‘{d.status}’예요. 수리비 부담 합의에서
            같은 안에 둘 다 동의하거나 ‘비용 없음’에 동의해야 끝나요.
          </p>
        ) : (
          <>
            <p>
              합의된 부담 · 임차인 {won(d.burden.tenant)} / 임대인{" "}
              {won(d.burden.landlord)}
            </p>
            <p>
              실제 순지출(낸 돈 − 상대에게서 받은 돈) · 임차인{" "}
              {won(d.paid.tenant)} / 임대인 {won(d.paid.landlord)}
            </p>
            {d.transfer ? (
              <>
                <p>
                  <strong>
                    {d.transfer.from} 부담 몫을 {d.transfer.owedTo}이 냈어요 →{" "}
                    {d.transfer.from} → {d.transfer.owedTo}{" "}
                    {won(d.transfer.amount)} · 돌려줬다면 기록하기
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
                부담한 쪽의 지불 기록이 합의 금액과 달라요. 낸 돈이 있으면 지불
                사실을 남기고, 금액이 다르면 부담 제안이나 지불 금액 고치기를
                확인해 주세요.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  )
}
