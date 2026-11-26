import type { ReactNode } from "react"
import { Building2 } from "lucide-react"
import { Choice } from "@/components/common/blocks"

// 화면 제목 줄: 작은 위치 줄, 제목, 한 줄 설명, 지금 보는 호실 고르기와 화면별 버튼
export function PageHeading({
  eyebrow,
  title,
  greeting,
  intro,
  hideIntroOnMobile,
  roomName,
  roomNames,
  onRoom,
  action,
}: {
  eyebrow: string
  title?: string
  // 홈 인사말 끝의 점 장식
  greeting?: boolean
  intro: string
  hideIntroOnMobile?: boolean
  roomName: string
  roomNames: string[]
  onRoom: (name: string) => void
  action?: ReactNode
}) {
  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>
          {title}
          {greeting && <span className="greeting-dot">.</span>}
        </h1>
        <p className={hideIntroOnMobile ? "max-md:hidden" : undefined}>
          {intro}
        </p>
      </div>
      <div className="heading-actions">
        <div className="room-select">
          <Building2 size={16} />
          <Choice
            label="현재 호실 선택"
            value={roomName}
            values={roomNames}
            onChange={onRoom}
          />
        </div>
        {action}
      </div>
    </div>
  )
}
