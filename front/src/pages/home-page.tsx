import type { ComponentProps } from "react"
import { ClipboardCheck, Wrench } from "lucide-react"
import { Button } from "@/components/ui/button"
import { HomeDashboard } from "@/components/home-dashboard"

// S-03 홈(호실 요약). 처음 쓰는 사람에게는 할 일 하나를 먼저 보여 줘요(06 5-0 '빈 화면').
type HomePageProps = ComponentProps<typeof HomeDashboard> & {
  // 하자 등록을 할 수 있고(호실의 availableActions) 아직 남긴 기록이 없을 때
  showFirstSteps: boolean
  onStartInspection: () => void
}
export function HomePage({
  showFirstSteps,
  onStartInspection,
  ...dashboard
}: HomePageProps) {
  return (
    <>
      {showFirstSteps && (
        <div className="first-steps">
          <div>
            <strong>첫 할 일을 골라 주세요</strong>
            <p className="muted">
              입주 당시 모습을 남기거나, 지금 있는 하자를 바로 등록할 수 있어요.
            </p>
          </div>
          <div className="first-steps-actions">
            <Button onClick={onStartInspection}>
              <ClipboardCheck />
              입주 점검 시작
            </Button>
            <Button variant="outline" onClick={dashboard.onNewRepair}>
              <Wrench />
              하자 등록
            </Button>
          </div>
        </div>
      )}
      <HomeDashboard {...dashboard} />
    </>
  )
}
