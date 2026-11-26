import { Bell, ChevronRight, Menu } from "lucide-react"
import { Button } from "@/components/ui/button"

// 회원 화면 위쪽 막대: 메뉴 열기(좁은 화면), 지금 위치, 받은 초대·알림·내 계정
export function AppTopbar({
  title,
  invitations,
  unread,
  accountInitial,
  onMenu,
  onInvitations,
  onNotifications,
  onAccount,
}: {
  title?: string
  invitations: number
  unread: number
  accountInitial: string
  onMenu: () => void
  onInvitations: () => void
  onNotifications: () => void
  onAccount: () => void
}) {
  return (
    <header className="topbar">
      <div className="top-left">
        <Button
          variant="ghost"
          size="icon"
          className="mobile-menu"
          aria-label="메뉴 열기"
          onClick={onMenu}
        >
          <Menu />
        </Button>
        <span className="breadcrumb">
          내 공간 <ChevronRight size={13} /> <strong>{title}</strong>
        </span>
      </div>
      <div className="top-actions">
        {invitations > 0 && (
          <Button variant="outline" onClick={onInvitations}>
            받은 초대 {invitations}건
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon"
          className="bell"
          aria-label={`알림 ${unread}개`}
          onClick={onNotifications}
        >
          <Bell />
          {unread > 0 && <span>{unread}</span>}
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className="avatar small"
          aria-label="내 계정"
          onClick={onAccount}
        >
          {accountInitial}
        </Button>
      </div>
    </header>
  )
}
