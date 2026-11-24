import { Link2, Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Panel, Status } from "@/components/prototype"
import { maskPhone } from "@/api/auth"
import { other, type Role, type Room } from "@/data/demo"
import { activeInviteLinks } from "@/lib/workflows"
import { sideText } from "@/lib/words"
import type { RoomAction } from "@/api/actions"

// 연결 화면: 호실의 두 사람(초대·번호·연결 상태). 버튼은 호실의 availableActions로만 그려요.
export function ConnectionPage({
  room,
  role,
  readOnly,
  sessionPhone,
  roomHas,
  open,
}: {
  room: Room
  role: Role
  readOnly: boolean
  sessionPhone: string
  roomHas: (a: RoomAction) => boolean
  open: (m: "invite" | "phone" | "room") => void
}) {
  return (
    <Panel title="호실의 두 사람">
      <div className="connection-flow">
        <div className="person-card">
          <span className="avatar">나</span>
          <h3>{sideText(role)} (나)</h3>
          <Status>연결됨</Status>
          <p>인증한 휴대폰 · {maskPhone(sessionPhone)}</p>
        </div>
        <div className="connection-line">
          <Link2 size={26} />
          <Status>{room.connection}</Status>
        </div>
        <div className="person-card">
          <span className="avatar counterpart">상대</span>
          <h3>{sideText(other(role))}</h3>
          <Status>{room.connection}</Status>
          <p>
            {room.connection === "연결됨"
              ? "휴대폰 인증 후 연결된 계정"
              : "초대 후 가입·연결 확인이 필요해요"}
          </p>
        </div>
      </div>
      <p className="muted my-5">
        휴대폰 번호 인증으로 연결했다는 사실만 남아요. 집주인인지는 따로
        확인하지 않아요.
      </p>
      {!readOnly && (
        <div className="actions">
          {roomHas("invite") && (
            <Button onClick={() => open("invite")}>
              <Link2 />
              {room.connection === "거절됨"
                ? "번호 변경 후 다시 초대"
                : room.connection === "초대됨"
                  ? "번호 확인 · 다시 초대"
                  : "상대 초대하기"}
            </Button>
          )}
          {roomHas("counterpart-phone") && (
            <Button variant="outline" onClick={() => open("phone")}>
              상대 번호 수정 · 초대 전
            </Button>
          )}
          {room.connection === "거절됨" && room.creator === role && (
            <p className="muted">
              상대가 ‘제 집이 아니에요’로 거절했어요. 같은 번호로는 다시 초대할
              수 없으니 번호를 변경한 뒤 초대해 주세요.
            </p>
          )}
          {(room.inviteLinks?.length ?? 0) > 0 && (
            <p className="muted">
              보낸 초대 링크 · 링크로 봤어요{" "}
              {activeInviteLinks(room).reduce((n, l) => n + (l.views || 0), 0)}
              회
            </p>
          )}
          {room.connection === "초대됨" && (
            <p className="muted">
              초대 번호 {maskPhone(room.phone || "")} · 상대가 이 번호로
              가입·인증한 계정에서 연결을 수락하면 연결돼요.
            </p>
          )}
          {room.connection === "연결됨" && (
            <Button variant="outline" onClick={() => open("room")}>
              <Plus />
              상대 없이 새 호실 시작하기
            </Button>
          )}
        </div>
      )}
    </Panel>
  )
}
