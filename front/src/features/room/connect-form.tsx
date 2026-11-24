import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Choice } from "@/components/common/blocks"
import { FormDialog } from "@/components/common/form-dialog"
import { ConsentList } from "@/components/common/consent-list"
import { post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import type { Account } from "@/api/auth"
import type { DemoState, Role, Room } from "@/data/demo"
import {
  acceptInvitation,
  canReceiveInvitation,
  declineInvitation,
  invitationMatches,
  requestSoloRecordsOnConnect,
  sideOf,
} from "@/lib/workflows"
import { sideText } from "@/lib/words"
import type { Act } from "@/lib/record-commands"

// 받은 초대 연결(S-08, ROOM-5). 로그인 계정의 인증 번호와 초대 번호가 같아야 하고,
// [동의하고 연결하기]를 누르는 것으로 기록 공유에 동의해요.
// 같은 집을 내가 따로 기록해 두었으면 ① 상대 기록에 합류 / ② 거절하고 내 기록으로 초대 중에서 골라요(기획서 195).
export function ConnectForm({
  sharedRoom,
  role,
  account,
  guest,
  db,
  myRooms,
  act,
  onConnected,
  onRejectToOwn,
  onReject,
  onCancel,
}: {
  sharedRoom?: Room
  role: Role
  account: Account | null
  guest: boolean
  db: DemoState
  myRooms: Room[]
  act: Act
  onConnected: (room: Room) => void
  // ② 거절하고 내 기록으로 초대: 거절을 보낸 뒤 내 기록의 초대 창으로 가요
  onRejectToOwn: (own: Room) => void
  onReject: () => void
  onCancel: () => void
}) {
  const [joinChoice, setJoinChoice] = useState<"" | "join" | "own">("")
  const [sameHouseId, setSameHouseId] = useState("")
  const [error, setError] = useState("")
  const accountId = account?.id
  const sessionPhone = account?.phone ?? ""
  const phoneMatched = Boolean(
    account &&
    sharedRoom &&
    invitationMatches(sharedRoom, role, account.phone, account.phone),
  )
  const canConnect = Boolean(account && phoneMatched && sharedRoom)
  // 같은 쪽으로 내가 만든 거주 중 호실 중에서 같은 집을 골라요
  const myOwnRooms = sharedRoom
    ? myRooms.filter(
        (x) =>
          x.id !== sharedRoom.id &&
          x.creator === role &&
          sideOf(x, accountId) === role &&
          x.status === "거주 중" &&
          !x.preJoinOf,
      )
    : []
  const sameHouseRooms = myOwnRooms.filter((x) => x.id === sameHouseId)
  const recordCount = (id: string) =>
    db.repairs.filter((x) => x.roomId === id).length +
    db.inspections.filter((x) => x.roomId === id).length +
    db.partPhotos.filter((x) => x.roomId === id).length
  return (
    <FormDialog
      title="호실 연결 확인"
      error={error}
      onError={setError}
      onCancel={onCancel}
      cancelLabel="연결하지 않기"
      submitLabel="동의하고 연결하기"
      submitDisabled={!canConnect}
      onSubmit={() => {
        if (!account || !guest || !canConnect || !sharedRoom) {
          setError(
            phoneMatched
              ? "기록 공유에 동의해 주세요."
              : "초대받은 번호와 이 계정의 인증 번호가 달라 연결할 수 없어요.",
          )
          return
        }
        if (sameHouseRooms.length && joinChoice !== "join") {
          setError(
            "같은 집의 내 기록을 골랐어요. 상대 기록에 합류할지, 거절하고 내 기록으로 초대할지 골라 주세요.",
          )
          return
        }
        const joining = sameHouseRooms.map((x) => x.id)
        const accountPhone = account.phone,
          me = account.id
        void act(
          post(endpoints.connect(sharedRoom.id), {
            consent: true,
            joinRoomIds: joining,
          }),
          "호실 연결 수락 · 번호 일치 확인 및 기록 공유 동의",
          (d) => {
            const target = d.rooms.find((r) => r.id === sharedRoom.id)!
            acceptInvitation(
              target,
              role,
              accountPhone,
              phoneMatched,
              true,
              accountPhone,
            )
            target.members = { ...target.members, [role]: me }
            // 혼자 끝낸 기록(점검·끝낸 수리 건·지불)에 확인 요청이 한 번에 가요(시나리오 3)
            requestSoloRecordsOnConnect(d, sharedRoom.id)
            // ① 상대 기록에 합류: 내 기록은 '합류 전 기록'(읽기만)으로 남아요
            joining.forEach((id) => {
              d.rooms.find((x) => x.id === id)!.preJoinOf = sharedRoom.id
            })
          },
          true,
          sharedRoom.id,
          true,
        ).then((ok) => {
          if (ok) onConnected(sharedRoom)
        })
      }}
    >
      <p className="connect-ask">
        {phoneMatched
          ? `${sharedRoom?.address.split(" ").at(-1) ?? ""} ${sharedRoom?.name ?? ""}`
          : "초대받은 호실"}
        에 <strong>{sideText(role)}</strong>으로 연결할까요?
      </p>
      {!sharedRoom?.phone && (
        <p className="muted">
          이 기록에는 등록된 초대 번호가 없습니다. 초대한 쪽에 번호를 확인해
          다시 초대해 달라고 요청해 주세요.
        </p>
      )}
      {phoneMatched ? (
        <ConsentList
          rows={[
            [
              "함께 볼 사람",
              `${sharedRoom?.creator === "임대인" ? "집주인" : "세입자"} (초대한 분)`,
            ],
            ["함께 보는 것", "이 호실의 사진·설명·행동 기록 (이름은 가려서)"],
            ["목적", "집 상태와 수리 기록을 함께 확인하려고"],
            ["기간", "정산 완료 뒤 1년까지"],
            ["동의하지 않으면", "연결되지 않고, 받은 기록만 볼 수 있어요"],
          ]}
        />
      ) : (
        <p className="form-error">
          초대받은 번호와 내 인증 번호가 달라요. 초대한 분께 번호 확인을 부탁해
          주세요.
        </p>
      )}
      {sharedRoom && myOwnRooms.length > 0 && (
        <div className="field">
          <label>같은 집에 대해 내가 만든 기록</label>
          <Choice
            label="같은 집의 내 기록"
            value={
              myOwnRooms.find((x) => x.id === sameHouseId)?.name ||
              "없음 · 바로 연결"
            }
            values={["없음 · 바로 연결", ...myOwnRooms.map((x) => x.name)]}
            onChange={(v) => {
              setSameHouseId(myOwnRooms.find((x) => x.name === v)?.id || "")
              setJoinChoice("")
              setError("")
            }}
          />
          <small className="muted">
            같은 집을 기록한 내 호실이 있으면 골라 주세요. 없으면 바로 연결해요.
          </small>
        </div>
      )}
      {sharedRoom && sameHouseRooms.length > 0 && (
        <div className="info-box">
          <div className="space-y-2">
            <strong>같은 집의 기록이 두 개예요</strong>
            {[sharedRoom, ...sameHouseRooms].map((x) => (
              <p key={x.id}>
                {x.id === sharedRoom.id ? "상대 기록" : "내 기록"} · {x.name} ·
                기록 {recordCount(x.id)}건
                {recordCount(x.id) ===
                Math.max(
                  ...[sharedRoom, ...sameHouseRooms].map((y) =>
                    recordCount(y.id),
                  ),
                )
                  ? " · 추천(기록이 많은 쪽)"
                  : ""}
              </p>
            ))}
            <div className="actions">
              <Button
                type="button"
                variant={joinChoice === "join" ? "default" : "outline"}
                onClick={() => setJoinChoice("join")}
              >
                ① 상대 기록에 합류
              </Button>
              <Button
                type="button"
                variant={joinChoice === "own" ? "default" : "outline"}
                onClick={() => setJoinChoice("own")}
              >
                ② 거절하고 내 기록으로 초대
              </Button>
            </div>
            {joinChoice === "join" && (
              <p className="muted">
                연결을 수락하면 내 기록은 ‘합류 전 기록’으로 남고 읽기만 할 수
                있어요.
              </p>
            )}
            {joinChoice === "own" && (
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  const own = sameHouseRooms[0]
                  void act(
                    post(endpoints.rejectInvitation(sharedRoom.id)),
                    "같은 집 기록 · 거절하고 내 기록으로 상대 초대 선택",
                    (d) => {
                      declineInvitation(
                        d.rooms.find((x) => x.id === sharedRoom.id)!,
                        role,
                        sessionPhone,
                      )
                    },
                    true,
                    sharedRoom.id,
                    true,
                  )
                  onRejectToOwn(own)
                }}
              >
                거절하고 내 기록({sameHouseRooms[0].name})으로 초대하기
              </Button>
            )}
          </div>
        </div>
      )}
      {sharedRoom && canReceiveInvitation(sharedRoom, role, sessionPhone) && (
        <Button type="button" variant="ghost" onClick={onReject}>
          제 집이 아니에요
        </Button>
      )}
    </FormDialog>
  )
}
