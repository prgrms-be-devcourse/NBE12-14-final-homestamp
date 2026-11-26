import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { Notice } from "@/components/common/notice"
import { FormDialog } from "@/components/common/form-dialog"
import { ConsentList } from "@/components/common/consent-list"
import { post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { API_MODE } from "@/config"
import { other, type Role, type Room } from "@/data/demo"
import { requestInspectionAll } from "@/lib/inspection-rules"
import {
  canChangeInvitePhone,
  issueInvite,
  logRepairNotice,
} from "@/lib/workflows"
import { type Commit } from "@/lib/record-commands"
import {
  isMobilePhone,
  maskPhone,
  MOBILE_PHONE_MESSAGE,
  normalizePhone,
} from "@/lib/phone"

// 공유 확인 · 상대 초대(ROOM-4, S-07). target이 있으면 그 기록 한 건과 함께 알려요.
export function InviteForm({
  room,
  role,
  sessionPhone,
  target,
  commit,
  toast,
  onCancel,
}: {
  room: Room
  role: Role
  sessionPhone: string
  target?: { kind: "repair" | "inspection"; id: string }
  commit: Commit
  toast: (text: string) => void
  onCancel: () => void
}) {
  // 사용자가 '번호 변경'을 직접 골랐을 때만 상대 번호를 바꿔요.
  const [changePhone, setChangePhone] = useState(false)
  const [invitePhone, setInvitePhone] = useState(room.phone || "")
  const [error, setError] = useState("")
  const counterpart = other(role) === "임대인" ? "집주인" : "세입자"
  return (
    <FormDialog
      title={`이 호실 기록을 ${counterpart}에게 보여 줄까요?`}
      error={error}
      onError={setError}
      onCancel={onCancel}
      cancelLabel="나만 보기"
      submitLabel="동의하고 보여 주기"
      onSubmit={(text) => {
        // 번호 변경을 고르지 않으면 호실에 저장된 기준 연락처를 그대로 써요.
        const changing = changePhone && canChangeInvitePhone(room, role)
        const phone = normalizePhone(
          changing ? text("phone") : room.phone || text("phone"),
        )
        if (!isMobilePhone(phone)) {
          setError(MOBILE_PHONE_MESSAGE)
          return
        }
        if (sessionPhone && phone === sessionPhone) {
          setError("내 계정 번호는 상대로 넣을 수 없어요.")
          return
        }
        if (room.connection === "거절됨" && !changing) {
          setError(
            "상대가 거절한 번호로는 다시 초대할 수 없어요. 번호 변경을 선택하고 새 번호로 초대해 주세요.",
          )
          return
        }
        if (changing && phone === room.phone?.replace(/\D/g, "")) {
          setError(
            "기존 번호와 같아요. 다른 번호를 입력하거나 번호 변경을 취소해 주세요.",
          )
          return
        }
        // 행동 기록에는 번호 값을 남기지 않고 변경 사실만 남겨요.
        commit(
          changing
            ? post(endpoints.counterpartPhone(room.id), { phone })
            : post(endpoints.invite(room.id), {
                phone,
                consent: true,
                target: target ?? undefined,
              }),
          changing
            ? "상대 번호 변경 · 이전 초대 링크 무효 · 새 번호로 다시 초대"
            : "공유 확인 · 상대 초대 · 문자 발송 요청",
          (d) => {
            const r = d.rooms.find((r) => r.id === room.id)!
            issueInvite(r, role, phone, changing)
            r.shareTarget = target
            if (target?.kind === "repair") {
              const rep = d.repairs.find((x) => x.id === target.id)
              if (rep) logRepairNotice(rep)
            }
            if (target?.kind === "inspection") {
              const i = d.inspections.find((i) => i.id === target.id)!
              requestInspectionAll(d, room.id, i.kind, role)
            }
          },
        )
        toast(
          API_MODE === "mock"
            ? "초대를 만들었어요. 서버가 연결되지 않아 문자는 보내지 않았어요. 상대가 이 번호로 가입하면 받은 초대에 보여요."
            : "초대 문자를 곧 보낼게요. 상대가 이 번호로 가입하고 연결하면 함께 볼 수 있어요.",
        )
      }}
    >
      {canChangeInvitePhone(room, role) && (
        <label className="consent">
          <Checkbox
            checked={changePhone}
            onCheckedChange={(v) => {
              setChangePhone(Boolean(v))
              setInvitePhone(v ? "" : room.phone || "")
              setError("")
            }}
          />
          번호를 바꿔서 다시 초대할게요
        </label>
      )}
      {room.connection === "거절됨" && !changePhone && (
        <Notice>
          상대가 ‘제 집이 아니에요’로 거절한 번호예요. 번호를 바꿔서 초대해
          주세요.
        </Notice>
      )}
      {changePhone && (
        <p className="muted">
          번호를 바꾸면 이전 초대 링크는 더 이상 열리지 않아요.
        </p>
      )}
      <div className="field">
        <label htmlFor="phone">
          {changePhone ? "새 휴대폰 번호" : `${counterpart} 휴대폰 번호`}
        </label>
        <Input
          id="phone"
          name="phone"
          type="tel"
          inputMode="numeric"
          value={changePhone ? invitePhone : room.phone || invitePhone}
          readOnly={Boolean(room.phone) && !changePhone}
          onChange={(e) => setInvitePhone(e.target.value)}
          placeholder="01012345678"
          required
        />
      </div>
      <ConsentList
        rows={[
          [
            "보여 줄 사람",
            `${counterpart} · ${maskPhone((changePhone ? invitePhone : room.phone || invitePhone) || "") || "번호 입력 후"}`,
          ],
          ["보여 주는 것", "이 호실의 사진·설명·행동 기록 (내 이름은 가려서)"],
          ["목적", "집 상태와 수리 기록을 함께 확인하려고"],
          ["기간", "정산 완료 뒤 1년까지"],
          ["동의하지 않으면", "나만 보는 기록으로 남아요"],
        ]}
      />
      <p className="muted">언제든 공유를 그만둘 수 있어요.</p>
      {API_MODE === "mock" && (
        <Notice tone="amber">
          개발 환경 · 서버가 연결되지 않아 초대 문자는 보내지 않아요.
        </Notice>
      )}
    </FormDialog>
  )
}
