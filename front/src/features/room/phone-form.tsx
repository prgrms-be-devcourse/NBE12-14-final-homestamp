import { useState } from "react"
import { Input } from "@/components/ui/input"
import { Notice } from "@/components/common/notice"
import { FormDialog } from "@/components/common/form-dialog"
import { notInSpec } from "@/api/records"
import type { Role, Room } from "@/data/demo"
import { updateNumberOnlyPhone } from "@/lib/workflows"
import { type Commit } from "@/lib/record-commands"
import { isMobilePhone, maskPhone, normalizePhone } from "@/lib/phone"

// 초대 전 상대 번호 고치기: 번호만 바꾸고 초대·링크 발급은 하지 않아요.
export function PhoneForm({
  room,
  role,
  sessionPhone,
  commit,
  onCancel,
}: {
  room: Room
  role: Role
  sessionPhone: string
  commit: Commit
  onCancel: () => void
}) {
  const [error, setError] = useState("")
  return (
    <FormDialog
      title="상대 번호 수정 · 초대 전"
      error={error}
      onError={setError}
      onCancel={onCancel}
      submitLabel="번호 저장"
      onSubmit={(text) => {
        const phone = normalizePhone(text("phone"))
        if (!isMobilePhone(phone)) {
          setError("010으로 시작하는 11자리 번호를 입력해 주세요.")
          return
        }
        if (sessionPhone && phone === sessionPhone) {
          setError("내 계정 번호는 상대로 넣을 수 없어요.")
          return
        }
        if (phone === normalizePhone(room.phone ?? "")) {
          setError("기존 번호와 같아요.")
          return
        }
        commit(
          notInSpec(
            "초대 전 상대 번호 고치기(POST /api/rooms/{id}/info에 번호 칸 없음)",
          ),
          "상대 번호 수정 · 초대 전",
          (d) =>
            updateNumberOnlyPhone(
              d.rooms.find((r) => r.id === room.id)!,
              role,
              phone,
            ),
          room.id,
          role,
        )
      }}
    >
      <Notice>
        지금 저장된 번호: {maskPhone(room.phone || "")} · 아직 초대 전이라
        번호만 바뀌고, 초대 문자나 링크는 만들어지지 않아요.
      </Notice>
      <div className="field">
        <label htmlFor="edit-phone">새 상대 휴대폰 번호</label>
        <Input
          id="edit-phone"
          name="phone"
          type="tel"
          placeholder="010으로 시작하는 번호"
          required
        />
      </div>
    </FormDialog>
  )
}
