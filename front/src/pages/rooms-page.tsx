import { ArrowRight, Building2, Plus } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { CountChip } from "@/components/fold"
import { Status } from "@/components/prototype"
import { won, type DemoState, type Role, type Room } from "@/data/demo"
import { sideOf } from "@/lib/workflows"
import { sideText } from "@/lib/words"

// 내 호실 목록(GET /api/rooms). 사는 집 / 관리하는 호실로 나눠 보여 줘요(06 S-03).
export function RoomsPage({
  db,
  role,
  roomId,
  accountId,
  accessibleRooms,
  nextStep,
  setRoomId,
  go,
  open,
}: {
  db: DemoState
  role: Role
  roomId: string
  accountId?: string
  accessibleRooms: Room[]
  nextStep: (r: Room) => string
  setRoomId: (id: string) => void
  go: (p: "home") => void
  open: (m: "room") => void
}) {
  const roomCard = (r: Room) => {
    const openRepairs = db.repairs.filter(
      (x) => x.roomId === r.id && x.status !== "처리 완료",
    ).length
    return (
      <Card
        className={`svc-card svc-room-card ${r.id === roomId ? "is-current" : ""}`}
        key={r.id}
      >
        <CardContent className="svc-card-body space-y-3">
          <div className="svc-room-card-head">
            <span className="svc-room-icon" aria-hidden>
              <Building2 size={20} />
            </span>
            <div className="min-w-0">
              <h2>{r.name}</h2>
              <p className="muted">{r.address}</p>
            </div>
          </div>
          <div className="badges">
            <Status>{r.status}</Status>
            <Status>{r.connection}</Status>
            {r.creator && (
              <Badge variant="outline">
                {r.creator === role
                  ? `${sideText(role)} · 내가 만듦`
                  : `${sideText(role)}`}
              </Badge>
            )}
          </div>
          <dl className="svc-facts">
            <div>
              <dt>입주일</dt>
              <dd>{r.since || "미입력"}</dd>
            </div>
            <div>
              <dt>진행 중 수리</dt>
              <dd>{openRepairs}건</dd>
            </div>
            <div>
              <dt>보증금</dt>
              <dd>{r.deposit === undefined ? "미입력" : won(r.deposit)}</dd>
            </div>
          </dl>
          <p className="svc-next">
            <ArrowRight size={14} aria-hidden />
            {nextStep(r)}
          </p>
          <Button
            className="w-full"
            variant={r.id === roomId ? "default" : "outline"}
            onClick={() => {
              setRoomId(r.id)
              go("home")
            }}
          >
            {r.id === roomId ? "선택한 호실 보기" : "호실 선택"}
            <ArrowRight />
          </Button>
        </CardContent>
      </Card>
    )
  }
  const live = accessibleRooms.filter((r) => sideOf(r, accountId) === "임차인"),
    own = accessibleRooms.filter((r) => sideOf(r, accountId) === "임대인")
  // 새 호실 기록 만들기는 두 묶음(사는 집 · 관리하는 호실) 어느 쪽에도 속하지 않아서 목록 맨 위에 하나만 둬요.
  return (
    <div className="space-y-6">
      <div className="rooms-bar">
        <p className="muted">내 호실 {accessibleRooms.length}곳</p>
        <Button onClick={() => open("room")}>
          <Plus />새 호실 기록 만들기
        </Button>
      </div>
      {live.length > 0 && (
        <section>
          <h2 className="room-group-title">
            사는 집 <CountChip>{live.length}</CountChip>
          </h2>
          <div className="svc-room-grid">{live.map(roomCard)}</div>
        </section>
      )}
      {own.length > 0 && (
        <section>
          <h2 className="room-group-title">
            관리하는 호실 <CountChip>{own.length}</CountChip>
          </h2>
          <div className="svc-room-grid">{own.map(roomCard)}</div>
        </section>
      )}
    </div>
  )
}
