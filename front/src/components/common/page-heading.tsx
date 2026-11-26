import type { ReactNode } from "react"

// 슬림 화면 머리말: 제목 + (선택적 한 줄 설명) + 핵심 버튼 최대 1개(호실 전환은 사이드바/상단바로 일원화)
export function PageHeading({
  title,
  greeting,
  intro,
  action,
}: {
  title?: string
  greeting?: boolean
  intro?: string
  action?: ReactNode
}) {
  return (
    <div className="page-heading">
      <div className="min-w-0">
        <h1>
          {title}
          {greeting && <span className="greeting-dot">.</span>}
        </h1>
        {intro && <p className="max-md:hidden">{intro}</p>}
      </div>
      {action && <div className="heading-actions">{action}</div>}
    </div>
  )
}
