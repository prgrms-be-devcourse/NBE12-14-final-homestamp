import { Building2, LogOut } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Choice, Logo, Status } from "@/components/common/blocks"
import type { Room } from "@/data/demo"
import type { Page } from "@/lib/router"
import { activeTabOf, nav, type NavTabId } from "@/components/common/app-nav"
import { connectionText } from "@/lib/workflows"
import type { RoomOption } from "@/lib/room-options"

// PC 전용 좌측 슬림 사이드바: 로고 → 호실 전환 → 4개 핵심 메뉴 → 내 계정
export function SideMenu({
  page,
  openCount,
  invitations,
  room,
  roomOptions,
  account,
  roleText,
  onRoom,
  onGo,
  onAccount,
}: {
  page: Page
  openCount: number
  invitations: number
  room?: Room
  // 호실 이름은 겹칠 수 있어서 값은 호실 ID로 골라요
  roomOptions: RoomOption[]
  account: { name: string; email: string } | null
  roleText: string
  onRoom: (roomId: string) => void
  onGo: (p: NavTabId) => void
  onAccount: () => void
}) {
  const activeTab = page === "notifications" ? null : activeTabOf(page)
  return (
    <>
      <div className="side-top">
        <Logo />
      </div>
      {room && (
        <div className="side-room-box">
          <div className="side-room-head">
            <Building2 size={14} aria-hidden />
            <span>지금 보는 호실</span>
          </div>
          {roomOptions.length > 1 ? (
            <div className="side-room-select">
              <Choice
                label="현재 호실 선택"
                value={room.id}
                values={roomOptions}
                onChange={onRoom}
              />
            </div>
          ) : (
            <strong className="side-room-name">{room.name}</strong>
          )}
          <div className="badges mt-2">
            <Status>{room.status}</Status>
            <Status>{connectionText(room)}</Status>
          </div>
        </div>
      )}
      <nav aria-label="주 메뉴">
        {nav.map((n) => {
          const active = activeTab === n.id
          const badge =
            n.id === "repairs" ? openCount : n.id === "rooms" ? invitations : 0
          return (
            <Button
              variant="ghost"
              key={n.id}
              aria-current={active ? "page" : undefined}
              className={`nav-item ${active ? "active" : ""}`}
              onClick={() => onGo(n.id)}
            >
              <n.icon size={19} aria-hidden />
              {n.label}
              {badge > 0 && (
                <span
                  className="nav-count"
                  aria-label={
                    n.id === "repairs"
                      ? `진행 중 ${badge}건`
                      : `받은 초대 ${badge}건`
                  }
                >
                  {badge}
                </span>
              )}
            </Button>
          )
        })}
      </nav>
      <div className="sidebar-bottom">
        <Button variant="ghost" className="profile" onClick={onAccount}>
          <span className="avatar">{account?.name.slice(0, 1) || "나"}</span>
          <span>
            <strong>{account?.name} 님</strong>
            <small>{room ? `이 호실에서 ${roleText}` : account?.email}</small>
          </span>
          <LogOut size={16} aria-hidden />
        </Button>
      </div>
    </>
  )
}
