import { useState } from "react"
import { Input } from "@/components/ui/input"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { FormDialog } from "@/components/common/form-dialog"
import { post, roomSide } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import { useApi } from "@/api/hooks"

import type { Account } from "@/api/auth"
import { uid, type Role } from "@/data/demo"
import { RegionPicker } from "@/features/room/region-picker"
import { todayKey } from "@/lib/workflows"
import { type Send } from "@/lib/record-commands"
import {
  isMobilePhone,
  MOBILE_PHONE_MESSAGE,
  normalizePhone,
} from "@/lib/phone"

// 호실 별칭 최대 길이. 서버(CreateRoomRequest)와 같이 05 room_record 별칭 칸(50)에 맞춰요
const ALIAS_MAX = 50

// 호실 기록 만들기(S-05, ROOM-1). 꼭 적을 것(내 쪽·주소·별칭)은 위, 선택(입주일·상대 번호)은 아래 묶음.
// initialSide: 마지막에 고른 쪽(내가 만든 가장 최근 호실의 쪽)
export function RoomForm({
  account,
  initialSide,
  send,
  onCreated,
  onCancel,
}: {
  account: Account
  initialSide: Role
  send: Send
  onCreated: (roomId: string) => void
  onCancel: () => void
}) {
  const [side, setSide] = useState<Role>(initialSide)
  const [error, setError] = useState("")
  // 서버는 만들 때 상대 번호를 아직 받지 않아요(초대 ROOM-4에서 받음, #163). HTTP에서는 칸을 숨기고 초대 때 넣게 안내해요.
  const askPhone = useApi().mode === "mock"
  return (
    <FormDialog
      title="호실 기록 만들기"
      description="한 거주 기간의 입주 점검, 수리, 정산이 이 기록 하나에 모여요."
      error={error}
      onError={setError}
      onCancel={onCancel}
      submitLabel="만들기"
      onSubmit={(text) => {
        // 주소는 법정동을 고를 때만 채워져요(숨은 칸이라 required가 걸리지 않아 여기서 확인)
        if (!text("regionCode")) {
          setError("주소를 동까지 골라 주세요.")
          return
        }
        const phone = normalizePhone(text("phone"))
        if (phone && !isMobilePhone(phone)) {
          setError(`선택 전화번호: ${MOBILE_PHONE_MESSAGE}`)
          return
        }
        if (account.phone && phone === account.phone) {
          setError("내 계정 번호는 상대 번호로 넣을 수 없어요.")
          return
        }
        // 보증금은 만들 때 받지 않아요(S-05 칸 아님). 미입력으로 만들고 정산에서 보증금 확인·제안으로 남겨요.
        // 입주일도 비워 두면 그대로 비워 둬요(자동 날짜 없음). 입주 점검을 시작할 때 묻는 것은 INSP-2b
        const id = uid()
        // 처음 입력한 상대 번호는 이 호실의 기준 연락처로 저장만 하고, 초대·연결은 하지 않아요.
        const me = account.id
        const name = text("title"),
          address = text("address"),
          since = text("date")
        void send(
          // 서버 모양(06 4-3, CreateRoomRequest). 목업이 쓰는 상대 번호는 아래 update에만 넣어요.
          post(endpoints.rooms, {
            side: roomSide(side),
            regionCode: text("regionCode"),
            alias: name,
            moveInDate: since || undefined,
          }),
          side,
          "새 호실 기록 만들기",
          (d) => {
            d.rooms.push({
              id,
              name,
              address,
              since,
              createdAt: todayKey(),
              status: "거주 중",
              connection: "연결 전",
              received: false,
              creator: side,
              members: { [side]: me },
              phone: phone || undefined,
            })
          },
          { roomId: id, notify: false },
        ).then((done) => {
          if (done) onCreated(done.id ?? id)
        })
      }}
    >
      <div className="field">
        <label id="room-side-label">내 쪽</label>
        <RadioGroup
          aria-labelledby="room-side-label"
          value={side}
          onValueChange={(v) => setSide(v as Role)}
          className="grid grid-cols-2 gap-2"
        >
          {(["임차인", "임대인"] as const).map((s) => (
            <label
              key={s}
              className={`flex min-h-[72px] min-w-0 cursor-pointer items-start gap-2.5 rounded-[12px] border p-3 transition-colors ${side === s ? "border-brand bg-brand-soft ring-1 ring-brand ring-inset" : "border-line bg-white hover:border-line-strong"}`}
            >
              <RadioGroupItem
                value={s}
                className="mt-0.5"
                aria-label={
                  s === "임차인"
                    ? "이 집에 살아요 (임차인)"
                    : "이 집을 빌려줬어요 (임대인)"
                }
              />
              <span className="min-w-0">
                <strong className="block text-[15px] leading-snug font-semibold text-ink">
                  {s === "임차인" ? "이 집에 살아요" : "이 집을 빌려줬어요"}
                </strong>
                <small className="block text-[13px] text-sub">{s}</small>
              </span>
            </label>
          ))}
        </RadioGroup>
        <small className="muted">
          처음 알리거나 초대하기 전에는 호실 설정에서 고칠 수 있어요.
        </small>
      </div>
      <div className="field">
        <label htmlFor="room-region">주소 · 시·구·동까지</label>
        <RegionPicker inputId="room-region" />
        <small className="muted">
          번지·건물명은 적지 않아요. 공유 링크에는 동까지만 보여요.
        </small>
      </div>
      <div className="field">
        <label htmlFor="title">호실 별칭</label>
        <Input
          id="title"
          name="title"
          placeholder="예: 302호"
          maxLength={ALIAS_MAX}
          required
        />
        <small className="muted">
          상대가 문자로 알아보는 이름이에요. 처음 알린 뒤에는 바꿀 수 없어요.
        </small>
      </div>
      <section
        aria-labelledby="room-optional"
        className="flex flex-col gap-4 rounded-[14px] border border-line bg-soft p-4 max-sm:p-3.5"
      >
        <p id="room-optional" className="m-0 text-[13px] font-bold text-sub">
          선택 · 나중에 넣어도 돼요
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="field">
            <label htmlFor="date">입주일 · 선택</label>
            <Input id="date" name="date" type="date" className="bg-white" />
            <small className="muted">
              비워 두면 입주 점검을 시작할 때 물어요.
            </small>
          </div>
          {askPhone ? (
            <div className="field">
              <label htmlFor="room-phone">
                {side === "임차인" ? "집주인" : "세입자"} 휴대폰 번호 · 선택
              </label>
              <Input
                id="room-phone"
                name="phone"
                type="tel"
                inputMode="numeric"
                autoComplete="off"
                className="bg-white"
                placeholder="초대할 때 넣어도 돼요"
              />
              <small className="muted">
                번호를 넣어도 바로 초대되지 않아요. 초대할 때 이 번호를 써요.
              </small>
            </div>
          ) : (
            <p className="muted m-0 self-end text-[13px]">
              {side === "임차인" ? "집주인" : "세입자"} 휴대폰 번호는 상대를
              초대할 때 넣을 수 있어요.
            </p>
          )}
        </div>
      </section>
    </FormDialog>
  )
}
