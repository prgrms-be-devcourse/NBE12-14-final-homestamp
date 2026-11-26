import { Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Notice } from "@/components/common/notice"

// 첫 화면(S-03 빈 화면): 호실 기록이 없을 때는 만들기 하나만 크게
export function Welcome({
  name,
  invitations,
  onCreate,
}: {
  name: string
  invitations: number
  onCreate: () => void
}) {
  return (
    <div className="welcome">
      <Card className="svc-card">
        <CardContent className="svc-card-body welcome-body">
          <div>
            <p className="eyebrow">시작하기</p>
            <h1 className="welcome-title">{name}님, 환영해요</h1>
            <p className="muted mt-2">
              호실 기록을 만들면 입주 점검, 거주 중 수리, 퇴거 정산이 그 기록
              하나에 이어져요. 상대가 없어도 혼자 시작할 수 있어요.
            </p>
          </div>
          <ol className="welcome-steps">
            {[
              [
                "호실 기록 만들기",
                "이 집에 살아요(임차인) 또는 빌려줬어요(임대인)를 골라요.",
              ],
              [
                "입주 점검",
                "부위별 사진을 남기고 상대 확인을 받아 기준 기록으로 잠가요.",
              ],
              [
                "하자 · 수리",
                "하자를 사진과 함께 알리고 수리와 비용 합의를 따로 정리해요.",
              ],
              [
                "퇴거 · 정산",
                "같은 부위 사진을 비교하고 합의한 항목으로 보증금을 정산해요.",
              ],
            ].map(([t, d], i) => (
              <li key={t}>
                <span aria-hidden>{i + 1}</span>
                <div>
                  <strong>{t}</strong>
                  <small>{d}</small>
                </div>
              </li>
            ))}
          </ol>
          <div className="welcome-cta">
            <Button size="lg" className="w-full sm:w-auto" onClick={onCreate}>
              <Plus />
              호실 기록 만들기
            </Button>
          </div>
          {invitations > 0 && (
            <Notice>
              받은 초대가 {invitations}건 있어요. 위의 ‘받은 초대’에서 연결할 수
              있어요.
            </Notice>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
