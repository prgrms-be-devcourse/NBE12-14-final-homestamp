import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Choice } from "@/components/common/blocks"
import { FormDialog } from "@/components/common/form-dialog"
import { ConsentList } from "@/components/common/consent-list"
import { post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import {
  inviteIdOf,
  rejectInvitation,
  SHARE_CONSENT_VERSION,
  type ConnectBody,
} from "@/api/http-invitations"
import type { Account } from "@/api/auth"
import type { DemoState, Role, Room } from "@/data/demo"
import { roomOptions } from "@/lib/room-options"
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

// "같은 집의 내 기록" 칸에서 고르지 않음을 뜻하는 값(호실 ID와 겹치지 않아요)
const NO_SAME_HOUSE = "none"

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
  // 같은 쪽으로 내가 만든 거주 중 호실 중에서 같은 집을 골라요.
  // 서버에서 받은 초대(sharedRoom.invitation)는 같은 집 합류(ROOM-6)를 서버가 아직 받지 않아 고르지 않아요(별도 작업)
  const myOwnRooms =
    sharedRoom && !sharedRoom.invitation
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
        // 서버 본문은 공유 동의 문구 판과 초대 회차예요(06 4-3 connect, #437). 같은 집 합류는 목업만 update로 해요(ROOM-6은 별도 작업)
        void act(
          post(endpoints.connect(sharedRoom.id), {
            consentVersion: SHARE_CONSENT_VERSION,
            inviteId: inviteIdOf(sharedRoom),
          } satisfies ConnectBody),
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
        ).then((made) => {
          if (!made) return
          // 서버 초대의 id는 자리(partyId)라 연결 응답의 호실 기록 ID로 바꿔요. 응답에 없으면(같은 키로 다시 온 요청에서
          // 그사이 다른 회원이 연결함, 06 4-3) 호실을 고르지 않고 홈으로 가요
          const id = sharedRoom.invitation ? (made.id ?? "") : sharedRoom.id
          onConnected({ ...sharedRoom, id })
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
            // 호실 이름은 겹칠 수 있어서 ID로 골라요
            value={sameHouseId || NO_SAME_HOUSE}
            values={[
              { value: NO_SAME_HOUSE, label: "없음 · 바로 연결" },
              ...roomOptions(myOwnRooms),
            ]}
            onChange={(v) => {
              setSameHouseId(v === NO_SAME_HOUSE ? "" : v)
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
                    rejectInvitation(sharedRoom),
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
