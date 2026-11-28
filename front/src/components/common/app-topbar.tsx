import { Bell, Building2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Choice } from "@/components/common/blocks"
import type { RoomOption } from "@/lib/room-options"

// PC: 초대/알림(화면 제목은 본문 머리말 하나만, #229 리뷰) | 모바일: 브랜드/호실 빠른 전환 + 알림 + 내 계정(1줄 슬림 바)
export function AppTopbar({
  roomId,
  roomName,
  roomOptions,
  invitations,
  unread,
  accountInitial,
  onRoom,
  onInvitations,
  onNotifications,
  onAccount,
}: {
  roomId?: string
  roomName?: string
  // 호실 이름은 겹칠 수 있어서 값은 호실 ID로 골라요
  roomOptions: RoomOption[]
  invitations: number
  unread: number
  accountInitial: string
  onRoom: (roomId: string) => void
  onInvitations: () => void
  onNotifications: () => void
  onAccount: () => void
}) {
  return (
    <header className="topbar">
      <div className="top-left">
        {/* 모바일: 왼쪽 이름표, 오른쪽에 호실 고르기(시안 A의 상단 바 모양, B의 남색·회색) */}
        <div className="flex min-w-0 flex-1 items-center gap-2 md:hidden">
          <strong className="flex-none font-brand text-[15px] font-bold tracking-[-0.2px] text-brand">
            HomeStamp
          </strong>
          {roomId && roomName && roomOptions.length > 1 ? (
            <div className="mobile-room-select ml-auto">
              <Choice
                label="현재 호실 선택"
                value={roomId}
                values={roomOptions}
                onChange={onRoom}
              />
            </div>
          ) : (
            roomName && (
              <span className="ml-auto inline-flex h-9 min-w-0 items-center gap-1.5 rounded-[6px] border border-line-strong bg-white px-2.5 text-sm font-semibold text-brand">
                <Building2 size={15} className="flex-none" aria-hidden />
                <span className="truncate">{roomName}</span>
              </span>
            )
          )}
        </div>
      </div>
      <div className="top-actions">
        {invitations > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="h-9 rounded-full border-brand-line bg-brand-soft px-3 text-xs font-bold text-brand-ink hover:bg-[#eef1f5]"
            onClick={onInvitations}
          >
            초대 {invitations}건
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon"
          className="bell size-10"
          aria-label={`알림 ${unread}개`}
          onClick={onNotifications}
        >
          <Bell size={20} />
          {unread > 0 && <span>{unread}</span>}
        </Button>
        {/* 모바일에서만 Topbar에 내 계정 버튼 표시(PC는 사이드바 하단으로 일원화). 숨기는 것은 readability.css */}
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
