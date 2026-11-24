import { Button } from "@/components/ui/button"
import { Panel, Status } from "@/components/prototype"
import { inviteeSide, isSettlementInvitation } from "@/lib/workflows"
import { maskPhone } from "@/api/auth"
import type { Room } from "@/data/demo"
import { sideText } from "@/lib/words"

// 받은 초대: 가입할 때 인증한 번호와 호실의 초대 번호가 같은 초대만 받아 보여 줍니다(거르는 일은 화면 위쪽에서).
// 정산 초대 링크는 내용 없이 '퇴거 정산 확인 요청이 있어요'만 보여 줍니다(호실 별칭·금액 없음).
export function ReceivedInvitations({
  rooms,
  accountPhone,
  onOpen,
}: {
  rooms: Room[]
  accountPhone: string
  onOpen: (room: Room) => void
}) {
  if (!rooms.length) return null
  return (
    <Panel className="mb-5" title="받은 초대 · 연결 요청">
      <p className="muted">
        가입할 때 인증한 번호({maskPhone(accountPhone)})로 받은 초대예요. 연결을
        수락해야 호실 전체 기록을 함께 볼 수 있어요.
      </p>
      {rooms.map((room) =>
        isSettlementInvitation(room) ? (
          <div className="received-invitation" key={room.id}>
            <div>
              <h3>퇴거 정산 확인 요청이 있어요</h3>
              <Status>정산 초대 링크</Status>
              <p className="muted">금액과 항목은 연결한 뒤에 볼 수 있어요.</p>
            </div>
            <Button variant="outline" onClick={() => onOpen(room)}>
              확인하기
            </Button>
          </div>
        ) : (
          <div className="received-invitation" key={room.id}>
            <div>
              <h3>{room.name}</h3>
              <p>
                보낸 사람: {sideText(room.creator)} · 내 자리:{" "}
                {sideText(inviteeSide(room))}
              </p>
              <Status>{room.connection}</Status>
              <p className="muted">
                초대 날짜:{" "}
                {room.invitedAt ? (
                  <time dateTime={room.invitedAt}>
                    {new Date(room.invitedAt).toLocaleString("ko-KR", {
                      timeZone: "Asia/Seoul",
                    })}
                  </time>
                ) : (
                  "날짜 정보 없음"
                )}
              </p>
            </div>
            <Button variant="outline" onClick={() => onOpen(room)}>
              받은 초대 확인
            </Button>
          </div>
        ),
      )}
    </Panel>
  )
}
