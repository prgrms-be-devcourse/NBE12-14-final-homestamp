import { LogOut } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Logo, Status } from "@/components/common/blocks"
import type { Room } from "@/data/demo"
import type { Page } from "@/lib/router"
import { nav } from "@/components/common/app-nav"

export function SideMenu({
  page,
  repairCount,
  openCount,
  room,
  account,
  roleText,
  onGo,
  onAccount,
}: {
  page: Page
  repairCount: number
  openCount: number
  // 지금 보는 호실(접근할 수 있을 때만)
  room?: Room
  account: { name: string; email: string } | null
  roleText: string
  onGo: (p: (typeof nav)[number]["id"]) => void
  onAccount: () => void
}) {
  return (
    <>
      <div className="side-top">
        <Logo />
      </div>
      <nav aria-label="주 메뉴">
        {nav.map((n) => (
          <Button
            variant="ghost"
            key={n.id}
            className={`nav-item ${page === n.id || (page === "detail" && n.id === "repairs") ? "active" : ""}`}
            onClick={() => onGo(n.id)}
          >
            <n.icon size={19} />
            {n.label}
            {n.id === "repairs" && repairCount > 0 && (
              <span
                className="nav-count"
                title={`전체 ${repairCount}건 · 진행 중 ${openCount}건`}
                aria-label={`전체 ${repairCount}건, 진행 중 ${openCount}건`}
              >
                {repairCount}
              </span>
            )}
          </Button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        {room && (
          <div className="side-room">
            <small>지금 보는 호실</small>
            <strong>{room.name}</strong>
            <span className="badges">
              <Status>{room.status}</Status>
              <Status>{room.connection}</Status>
            </span>
          </div>
        )}
        <Button variant="ghost" className="profile" onClick={onAccount}>
          <span className="avatar">{account?.name.slice(0, 1) || "나"}</span>
          <span>
            <strong>{account?.name} 님</strong>
            <small>{room ? `이 호실에서 ${roleText}` : account?.email}</small>
          </span>
          <LogOut size={16} />
        </Button>
      </div>
    </>
  )
}
