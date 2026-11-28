import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { Notice } from "@/components/common/notice"
import { FormDialog } from "@/components/common/form-dialog"
import { ConsentList } from "@/components/common/consent-list"
import { post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { SHARE_CONSENT_VERSION, type InviteBody } from "@/api/http-invitations"
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

type InviteFormProps = {
  room: Room
  role: Role
  sessionPhone: string
  target?: { kind: "repair" | "inspection"; id: string }
  commit: Commit
  toast: (text: string) => void
  onCancel: () => void
}

// 공유 확인 · 상대 초대(ROOM-4, S-07). target이 있으면 그 기록 한 건과 함께 알려요.
// 서버에서 받은 호실(room.server)은 서버의 자리 상태·가린 번호로 그리고, 목업 호실은 화면이 가진 번호로 그려요.
export function InviteForm(props: InviteFormProps) {
  return props.room.server ? (
    <ServerInviteForm {...props} />
  ) : (
    <DemoInviteForm {...props} />
  )
}

const SHARE_ROWS = (counterpart: string, shown: string): [string, string][] => [
  ["보여 줄 사람", `${counterpart} · ${shown}`],
  ["보여 주는 것", "이 호실의 사진·설명·행동 기록 (내 이름은 가려서)"],
  ["목적", "집 상태와 수리 기록을 함께 확인하려고"],
  ["기간", "정산 완료 뒤 1년까지"],
  ["동의하지 않으면", "나만 보는 기록으로 남아요"],
]

// 서버 호실의 초대(06 4-3 POST /api/rooms/{id}/invite).
// - 번호만(NUMBER_ONLY) 자리: 호실을 만들 때 저장한 번호(가린 모양만 보여요)로 초대해요. 번호를 비워 보내면 서버가 그 번호를 써요.
//   [다른 번호로 초대]를 고르면 새 번호를 보내고, 서버가 저장한 번호를 그 번호로 덮어써요(decisions 2026-11-27)
// - 비어 있음(EMPTY) 자리: 번호를 꼭 넣어요
// - 초대됨·거절됨 자리의 다시 초대·번호 고치기는 아직 없어요(별도 작업). 서버도 INVALID_STATE로 막아요
// - 기록 링크는 지금 점검만 받아요(record). 수리 건 기록 링크는 수리 건 공개 조회가 준비된 뒤라(#370 ③) 호실 초대로만 보내요
function ServerInviteForm({
  room,
  role,
  sessionPhone,
  target,
  commit,
  onCancel,
}: InviteFormProps) {
  const [otherPhone, setOtherPhone] = useState(false)
  const [phoneInput, setPhoneInput] = useState("")
  const [error, setError] = useState("")
  const counterpart = other(role) === "임대인" ? "집주인" : "세입자"
  const status = room.server?.counterpartStatus
  const invitable = status === "EMPTY" || status === "NUMBER_ONLY"
  const saved =
    status === "NUMBER_ONLY" ? (room.server?.counterpartMaskedPhone ?? "") : ""
  const typing = !saved || otherPhone
  const inspectionId =
    target?.kind === "inspection" ? room.moveIn?.server?.id : undefined
  return (
    <FormDialog
      title={`이 호실 기록을 ${counterpart}에게 보여 줄까요?`}
      error={error}
      onError={setError}
      onCancel={onCancel}
      cancelLabel="나만 보기"
      submitLabel="동의하고 보여 주기"
      submitDisabled={!invitable}
      onSubmit={(text) => {
        if (!invitable) return
        const phone = typing ? normalizePhone(text("phone")) : undefined
        if (phone !== undefined) {
          if (!isMobilePhone(phone)) {
            setError(MOBILE_PHONE_MESSAGE)
            return
          }
          if (sessionPhone && phone === sessionPhone) {
            setError("내 계정 번호는 상대로 넣을 수 없어요.")
            return
          }
        }
        const body: InviteBody = {
          ...(phone !== undefined && { phone }),
          consentVersion: SHARE_CONSENT_VERSION,
          ...(inspectionId && {
            record: { kind: "INSPECTION", id: inspectionId },
          }),
        }
        // 행동 기록에는 번호 값을 남기지 않아요. 화면 기록은 서버에서 다시 받아요(update는 목업만 써요).
        // 성공·실패 안내는 commit이 서버 답을 받은 뒤에 해요(보내기 전에 "보냈어요"라고 하지 않게)
        commit(
          post(endpoints.invite(room.id), body),
          "공유 확인 · 상대 초대 · 문자 발송 요청",
          () => {},
        )
      }}
    >
      {!invitable && (
        <Notice>
          {status === "INVITED" || status === "REJECTED"
            ? "이미 초대한 상대예요. 번호 고치기·다시 초대는 아직 준비 중이에요."
            : "지금은 상대를 초대할 수 없어요."}
        </Notice>
      )}
      {invitable && saved && (
        <>
          <p className="muted">
            호실을 만들 때 저장한 번호({saved})로 초대해요.
          </p>
          <label className="consent">
            <Checkbox
              checked={otherPhone}
              onCheckedChange={(v) => {
                setOtherPhone(Boolean(v))
                setPhoneInput("")
                setError("")
              }}
            />
            다른 번호로 초대할게요
          </label>
          {otherPhone && (
            <p className="muted">
              새 번호로 초대하면 저장한 번호는 새 번호로 바뀌어요.
            </p>
          )}
        </>
      )}
      {invitable && typing && (
        <div className="field">
          <label htmlFor="phone">
            {saved ? "새 휴대폰 번호" : `${counterpart} 휴대폰 번호`}
          </label>
          <Input
            id="phone"
            name="phone"
            type="tel"
            inputMode="numeric"
            value={phoneInput}
            onChange={(e) => setPhoneInput(e.target.value)}
            placeholder="01012345678"
            required
          />
        </div>
      )}
      <ConsentList
        rows={SHARE_ROWS(
          counterpart,
          typing ? maskPhone(phoneInput) || "번호 입력 후" : saved,
        )}
      />
      <p className="muted">언제든 공유를 그만둘 수 있어요.</p>
    </FormDialog>
  )
}

// 목업 호실의 초대. 서버가 없어 번호·링크·확인 요청을 화면이 대신 바꿔요(update)
function DemoInviteForm({
  room,
  role,
  sessionPhone,
  target,
  commit,
  toast,
  onCancel,
}: InviteFormProps) {
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
                consentVersion: SHARE_CONSENT_VERSION,
              } satisfies InviteBody),
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
        rows={SHARE_ROWS(
          counterpart,
          maskPhone(
            (changePhone ? invitePhone : room.phone || invitePhone) || "",
          ) || "번호 입력 후",
        )}
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
