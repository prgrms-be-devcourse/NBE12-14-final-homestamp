import { useState } from "react"
import { Info } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Choice, Panel } from "@/components/common/blocks"
import { Fold } from "@/components/common/fold"
import { useRoomParts } from "@/api/use-room-parts"
import { errorMessage } from "@/api/errors"
import { PART_NAME_MAX, PART_ZONES, type RoomPart } from "@/lib/room-parts"

const monthDay = (iso: string) => {
  const d = new Date(iso)
  return `${d.getMonth() + 1}월 ${d.getDate()}일`
}

// S-06 요약 탭의 부위 관리 카드(ROOM-3, #456). 6구역별 부위를 보고, 숨기기·다시 보이기·설비·가전 켜고 끄기·부위 더하기를 해요.
// 숨긴 부위와 꺼 둔 설비·가전은 하자 등록·점검의 부위 칸에서 빠지고(ROOM-3a·3d), 바꾸면 그 칸도 새 목록을 받아요(use-room-parts).
// canEdit: 호실 상세의 availableActions에 `parts`가 있을 때
export function RoomPartsCard({
  roomId,
  canEdit,
  lockedReason,
}: {
  roomId: string
  canEdit: boolean
  lockedReason: string
}) {
  const { parts, asOf, loading, error, reload, add, hide, show, toggle } =
    useRoomParts(roomId)
  // 보내는 중인 부위(같은 부위를 두 번 누르지 않게). 더하기는 "add"
  const [busy, setBusy] = useState<string>()
  const [message, setMessage] = useState("")
  const [zone, setZone] = useState<string>(PART_ZONES[0])
  const [name, setName] = useState("")

  const send = (key: string, done: string, req: () => Promise<RoomPart>) => {
    if (busy) return
    setBusy(key)
    setMessage("")
    req()
      .then(() => setMessage(done))
      // 실패해도 목록은 다시 받아요(상대가 먼저 바꿨으면 INVALID_STATE, use-room-parts)
      .catch((e) => setMessage(errorMessage(e)))
      .finally(() => setBusy(undefined))
  }

  if (!parts)
    return (
      <Panel title="부위 목록">
        {loading ? (
          <p className="muted">부위 목록을 불러오는 중이에요.</p>
        ) : (
          <div className="field">
            <p className="muted">{error ?? "부위 목록을 불러오지 못했어요."}</p>
            <Button type="button" variant="outline" onClick={reload}>
              다시 불러오기
            </Button>
          </div>
        )}
      </Panel>
    )

  const shown = parts.filter((p) => !p.hidden)
  const hidden = parts.filter((p) => p.hidden)
  // 꺼 둔 설비·가전은 숨겼어도 세요(숨김과 켜고 끔은 따로, ROOM-3d)
  const off = parts.filter((p) => p.toggleable && !p.enabled)
  // 같은 구역에 같은 이름이 있으면(같은 이름 허용, ROOM-3c) 순서를 붙여 가려요
  const nameOf = (p: RoomPart) => {
    const same = parts.filter((x) => x.zone === p.zone && x.name === p.name)
    return same.length > 1 ? `${p.name} (${same.indexOf(p) + 1})` : p.name
  }
  const label = (p: RoomPart) => `${p.zone} · ${nameOf(p)}`

  // 보이는 부위 한 줄: 설비·가전은 켜고 끄기, 모두 숨기기
  const row = (p: RoomPart) => (
    <li
      key={p.id}
      className="flex min-h-11 items-center gap-2 border-b border-line py-1.5 last:border-b-0"
    >
      <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
        {nameOf(p)}
      </span>
      {p.toggleable && (
        <Badge variant={p.enabled ? "secondary" : "outline"}>
          {p.enabled ? "있음" : "꺼 둠"}
        </Badge>
      )}
      {canEdit && p.toggleable && (
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={Boolean(busy)}
          aria-label={`${label(p)} ${p.enabled ? "끄기" : "켜기"}`}
          onClick={() =>
            send(
              p.id,
              `${label(p)}을(를) ${p.enabled ? "껐어요" : "켰어요"}.`,
              () => toggle(p.id, !p.enabled),
            )
          }
        >
          {p.enabled ? "끄기" : "켜기"}
        </Button>
      )}
      {canEdit && (
        <Button
          type="button"
          size="sm"
          variant="ghost"
          disabled={Boolean(busy)}
          aria-label={`${label(p)} 숨기기`}
          onClick={() =>
            send(p.id, `${label(p)}을(를) 숨겼어요.`, () => hide(p.id))
          }
        >
          숨기기
        </Button>
      )}
    </li>
  )

  const addPart = () => {
    const n = name.trim()
    if (!n) return
    send("add", `${zone} · ${n}을(를) 더했어요.`, () =>
      add({ zone, name: n }).then((p) => {
        setName("")
        return p
      }),
    )
  }

  return (
    <Panel title="부위 목록">
      <p className="muted">
        {`보이는 부위 ${shown.length}개 · 숨긴 부위 ${hidden.length}개 · 꺼 둔 설비·가전 ${off.length}개`}
      </p>
      <p className="muted">
        숨긴 부위와 꺼 둔 설비·가전은 하자 등록·점검에서 고를 수 없어요. 이미
        남긴 기록은 그대로예요.
      </p>
      {asOf && (
        // 공유를 그만둔 뒤에는 그 시각까지의 부위 상태로 보여요(03 6-6 9번, 06 4-3 asOf)
        <p className="mt-2 flex items-start gap-2 text-[13px] leading-[1.6] text-sub">
          <Info size={16} aria-hidden />
          {`${monthDay(asOf)} 기준 부위예요. 공유를 그만둔 뒤 상대가 바꾼 것은 보이지 않아요.`}
        </p>
      )}
      {!canEdit && <p className="muted mt-2">{lockedReason}</p>}
      <Fold title="부위 관리" count={shown.length}>
        {PART_ZONES.map((z) => {
          const inZone = shown.filter((p) => p.zone === z)
          if (!inZone.length) return null
          return (
            <section key={z} aria-label={z} className="mt-2">
              <h3 className="m-0 text-sm font-bold text-sub">{z}</h3>
              <ul className="m-0 list-none p-0">{inZone.map(row)}</ul>
            </section>
          )
        })}
        {canEdit && (
          <div className="form-grid mt-3">
            <div className="field">
              <label>더할 구역</label>
              <Choice
                label="더할 구역"
                value={zone}
                values={[...PART_ZONES]}
                onChange={setZone}
              />
            </div>
            <div className="field">
              <label htmlFor="room-part-name">부위 이름</label>
              <div className="actions">
                <Input
                  id="room-part-name"
                  value={name}
                  maxLength={PART_NAME_MAX}
                  placeholder="예: 다락"
                  onChange={(e) => setName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault()
                      addPart()
                    }
                  }}
                />
                <Button
                  type="button"
                  variant="outline"
                  disabled={Boolean(busy) || !name.trim()}
                  onClick={addPart}
                >
                  부위 더하기
                </Button>
              </div>
            </div>
          </div>
        )}
      </Fold>
      {hidden.length > 0 && (
        <Fold title="숨긴 부위" count={hidden.length}>
          {hidden.some((p) => p.toggleable) && (
            // 숨김과 켜고 끔은 따로 움직여요(ROOM-3d). 숨긴 동안에는 켜고 끄는 버튼을 두지 않아요
            <p className="muted">
              숨긴 설비·가전은 다시 보이게 한 뒤 켜고 끌 수 있어요.
            </p>
          )}
          <ul className="m-0 list-none p-0">
            {hidden.map((p) => (
              <li
                key={p.id}
                className="flex min-h-11 items-center gap-2 border-b border-line py-1.5 last:border-b-0"
              >
                <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
                  {label(p)}
                </span>
                {p.toggleable && !p.enabled && (
                  <Badge variant="outline">꺼 둠</Badge>
                )}
                {canEdit && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={Boolean(busy)}
                    aria-label={`${label(p)} 다시 보이기`}
                    onClick={() =>
                      send(p.id, `${label(p)}을(를) 다시 보이게 했어요.`, () =>
                        show(p.id),
                      )
                    }
                  >
                    다시 보이기
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Fold>
      )}
      <p className="muted mt-2" role="status" aria-live="polite">
        {message}
      </p>
    </Panel>
  )
}
