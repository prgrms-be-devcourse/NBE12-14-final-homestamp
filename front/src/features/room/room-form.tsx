import { useState } from "react"
import { Input } from "@/components/ui/input"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Notice } from "@/components/common/notice"
import { AddressSearch } from "@/components/address-search"
import { FormDialog } from "@/components/common/form-dialog"
import { post } from "@/api/records"
import { endpoints } from "@/api/endpoints"
import type { Account } from "@/api/auth"
import { API_MODE } from "@/config"
import { uid, type Role } from "@/data/demo"
import { todayKey } from "@/lib/workflows"
import { isMobile, onlyDigits, type Send } from "@/lib/record-commands"

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
  return (
    <FormDialog
      title="호실 기록 만들기"
      description="한 거주 기간의 입주 점검, 수리, 정산이 이 기록 하나에 모여요."
      error={error}
      onError={setError}
      onCancel={onCancel}
      submitLabel="만들기"
      onSubmit={(text) => {
        // 서버 연결 시 주소는 검색 결과로만 채워져요(숨은 칸이라 required가 걸리지 않아 여기서 확인)
        if (API_MODE === "http" && !text("address")) {
          setError("[주소 검색]으로 동까지 골라 주세요.")
          return
        }
        const phone = onlyDigits(text("phone"))
        if (phone && !isMobile(phone)) {
          setError("선택 전화번호를 010으로 시작하는 11자리로 입력해 주세요.")
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
          post(endpoints.rooms, {
            side,
            address,
            alias: name,
            moveInDate: since || undefined,
            counterpartPhone: phone || undefined,
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
        <label htmlFor={API_MODE === "http" ? "address-search" : "address"}>
          주소 · 시·구·동까지
        </label>
        {/* 서버 연결 시: 도로명주소 검색으로 시·도·시·군·구·읍·면·동만 채워요. 개발 환경(서버 없음): 가짜 결과 없이 직접 입력 */}
        {API_MODE === "http" ? (
          <AddressSearch name="address" inputId="address-search" />
        ) : (
          <>
            <Notice tone="amber">
              개발 환경 · 서버가 연결되지 않아 주소 검색을 쓸 수 없어요.
              시·구·동을 직접 적어 주세요.
            </Notice>
            <Input
              id="address"
              name="address"
              placeholder="예: 서울시 마포구 연남동"
              required
            />
          </>
        )}
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
          maxLength={30}
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
        </div>
      </section>
    </FormDialog>
  )
}
