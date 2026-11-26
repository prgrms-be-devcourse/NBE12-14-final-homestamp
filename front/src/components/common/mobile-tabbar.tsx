import { cn } from "cn"
import type { Page } from "@/lib/router"
import { activeTabOf, nav, type NavTabId } from "@/components/common/app-nav"

// 모바일 전용 하단 고정 탭바(4개 핵심 탭, 최소 44px 터치 영역 + safe-area 대응)
export function MobileTabbar({
  page,
  openRepairCount,
  invitations,
  onGo,
}: {
  page: Page
  openRepairCount: number
  invitations: number
  onGo: (id: NavTabId) => void
}) {
  const activeTab = page === "notifications" ? null : activeTabOf(page)
  return (
    // 좁은 화면(760px 이하)에서만 보임. readability.css의 .mobile-tabbar가 정한다
    <nav aria-label="모바일 주 메뉴" className="mobile-tabbar">
      {nav.map((item) => {
        const active = activeTab === item.id
        const badge =
          item.id === "repairs"
            ? openRepairCount
            : item.id === "rooms"
              ? invitations
              : 0
        return (
          <button
            key={item.id}
            type="button"
            aria-current={active ? "page" : undefined}
            onClick={() => onGo(item.id)}
            className={cn("mobile-tabbar-item", active && "active")}
          >
            <span className="relative inline-flex">
              <item.icon
                size={21}
                strokeWidth={active ? 2.3 : 1.9}
                aria-hidden
              />
              {badge > 0 && (
                <span className="mobile-tabbar-badge" aria-hidden>
                  {badge > 9 ? "9+" : badge}
                </span>
              )}
            </span>
            <span>{item.shortLabel}</span>
            {badge > 0 && (
              <span className="sr-only">
                {item.id === "repairs"
                  ? `, 진행 중 ${badge}건`
                  : `, 받은 초대 ${badge}건`}
              </span>
            )}
          </button>
        )
      })}
    </nav>
  )
}
