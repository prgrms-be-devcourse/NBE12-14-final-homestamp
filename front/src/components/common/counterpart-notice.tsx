import { Button } from "@/components/ui/button"
import { Notice } from "@/components/notice"
import { other, type Role, type Room } from "@/data/demo"
import { sideName } from "@/lib/words"

// "확인 없음" 표시 중 호실 단위 안내(08-화면-기본-틀 8장, 09 5장 "같은 뜻은 한 부품").
// 상대가 아직 없으면 호실 맨 위에 한 번만 "혼자 기록하고 있어요"(줄마다 붙이지 않음).
// 초대했지만 연결 전이면 돈·정산 화면 맨 위에 한 번만 "아직 연결하지 않았어요 · 돈은 혼자 기록돼요".
export function CounterpartNotice({
  room,
  role,
  moneyPage,
  onInvite,
}: {
  room: Room
  role: Role
  // 돈·정산 화면이면 연결 전 안내도 보여요
  moneyPage: boolean
  // 호실의 availableActions에 invite가 있을 때만 버튼을 그려요
  onInvite?: () => void
}) {
  const them = sideName(other(role))
  if (room.connection === "연결 전")
    return (
      <Notice className="mb-5">
        <span>혼자 기록하고 있어요</span>
        {onInvite && (
          <Button variant="link" size="sm" className="ml-1" onClick={onInvite}>
            상대 초대하기
          </Button>
        )}
      </Notice>
    )
  if (room.connection === "초대됨" && moneyPage)
    return (
      <Notice className="mb-5">
        <span>{them} 님이 아직 연결하지 않았어요 · 돈은 혼자 기록돼요</span>
        {onInvite && room.status === "거주 중" && (
          <Button variant="link" size="sm" className="ml-1" onClick={onInvite}>
            초대 다시 보내기
          </Button>
        )}
      </Notice>
    )
  return null
}
