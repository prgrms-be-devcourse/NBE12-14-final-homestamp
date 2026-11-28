import { useEffect, useRef, useState } from "react"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Choice } from "@/components/common/blocks"
import { useRoomParts } from "@/api/use-room-parts"
import { errorMessage } from "@/api/errors"
import {
  isPickable,
  PART_NAME_MAX,
  PART_ZONES,
  type RoomPart,
} from "@/lib/room-parts"

const CUSTOM = "__custom__"
const NO_ZONE = "구역 선택"

// 구역 · 부위 칸(하자 등록 · 점검). 퇴거 때 같은 부위끼리 비교하는 기준이에요.
// 호실 부위 목록(06 4-3)에서 고르고, 값은 부위 ID(roomPartId)예요. 숨긴 부위·꺼 둔 설비·가전은 고를 수 없어요(ROOM-3a·3d).
// 맞는 부위가 없으면 "기타 (직접 입력)"으로 고른 구역에 부위를 더하고 그 부위를 골라요(06 S-10 ② 부위, ROOM-3c).
// value: 이 칸이 onChange로 넘긴 부위의 ID만 넣어요. 이전 기록의 부위 ID는 value가 아니라 defaultPart.id로 넘겨요.
// 그래야 목록을 받기 전이나 고를 수 없는 ID일 때 화면은 "부위 선택"인데 제출에는 그 ID가 남는 일이 없어요(#402)
// pickPart: 구역을 바꾸면 그 구역의 첫 부위로 둘지(그 밖), 부위를 비울지(입주 점검)
// defaultPart: 처음 열 때 고를 부위. id가 있으면 그 부위를 고를 수 있을 때만 골라요. id가 없으면(이전 기록·새 기록)
// 이 호실에 같은 구역·이름의 부위가 하나뿐이고 고를 수 있을 때만 골라요
export function PartField({
  roomId,
  value,
  onChange,
  pickPart = true,
  defaultPart,
}: {
  roomId: string
  value: string
  onChange: (part: RoomPart | undefined) => void
  pickPart?: boolean
  defaultPart?: { id?: string; zone: string; name: string }
}) {
  const { parts: loaded, loading, error, reload, add } = useRoomParts(roomId)
  // 방금 더한 부위는 목록을 다시 받기 전에도 고른 것으로 보여요
  const [added, setAdded] = useState<RoomPart>()
  const parts =
    loaded && added && !loaded.some((p) => p.id === added.id)
      ? [...loaded, added]
      : loaded
  // 숨겼거나 꺼 둔 부위(이전 기록의 부위 등)는 고른 것으로 두지 않아요. 서버도 새 기록에 받지 않아요(ROOM-3a·3d)
  const selected = parts?.find((p) => p.id === value && isPickable(p))
  // 기본 부위의 구역이 6구역이 아니면(이전 기록의 "침실" 등) 구역을 비워 두고 고르게 해요
  const [pickedZone, setPickedZone] = useState<string>(
    defaultPart && (PART_ZONES as readonly string[]).includes(defaultPart.zone)
      ? defaultPart.zone
      : NO_ZONE,
  )
  const [custom, setCustom] = useState(false)
  const [name, setName] = useState("")
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState("")
  const zone = selected?.zone ?? pickedZone
  const pickable = (z: string) =>
    (parts ?? []).filter((p) => p.zone === z && isPickable(p))

  // 처음 열 때 기본 부위를 골라 둬요(서버 목록을 받은 뒤 한 번)
  const defaulted = useRef(false)
  useEffect(() => {
    if (defaulted.current || !loaded) return
    defaulted.current = true
    // ID가 있으면 그 부위만 골라요. 고를 수 없는 ID를 같은 이름의 다른 부위로 바꾸지 않아요.
    // ID가 없는 이전 기록이나 새 기록만 구역·이름으로 기본 부위를 찾아요. 같은 구역·이름의 부위가
    // 정확히 하나이고 고를 수 있을 때만 골라요. 여럿이면(같은 이름 허용, ROOM-3c) 어느 부위인지 알 수 없고,
    // 하나뿐인데 숨겼거나 꺼 두었으면(ROOM-3a·3d) 다른 부위로 바꾸지 않고 다시 고르게 해요
    const id = value || defaultPart?.id
    const matches = id
      ? loaded.filter((x) => x.id === id)
      : defaultPart
        ? loaded.filter(
            (x) => x.zone === defaultPart.zone && x.name === defaultPart.name,
          )
        : []
    const p =
      matches.length === 1 && isPickable(matches[0]) ? matches[0] : undefined
    // 고른 부위를 부모에 넘겨요. value가 고를 수 없는 ID였으면 비워요
    if ((p?.id ?? "") !== value) onChange(p)
  }, [loaded, value, defaultPart, onChange])

  if (loading && !parts)
    return <p className="muted">부위 목록을 불러오는 중이에요.</p>
  if (!parts)
    return (
      <div className="field">
        <p className="muted">{error ?? "부위 목록을 불러오지 못했어요."}</p>
        <Button type="button" variant="outline" onClick={reload}>
          다시 불러오기
        </Button>
      </div>
    )

  const inZone = pickable(zone)
  // 같은 구역에 같은 이름이 있으면(같은 이름 허용, ROOM-3c) 순서를 붙여 가려요
  const options = inZone.map((p) => {
    const same = inZone.filter((x) => x.name === p.name)
    return {
      value: p.id,
      label: same.length > 1 ? `${p.name} (${same.indexOf(p) + 1})` : p.name,
    }
  })
  const pickZone = (z: string) => {
    setPickedZone(z)
    setCustom(false)
    setAddError("")
    onChange(pickPart ? pickable(z)[0] : undefined)
  }
  const addPart = () => {
    if (adding) return
    setAdding(true)
    setAddError("")
    add({ zone, name })
      .then((p) => {
        setAdded(p)
        setCustom(false)
        setName("")
        onChange(p)
      })
      .catch((e) => setAddError(errorMessage(e)))
      .finally(() => setAdding(false))
  }
  return (
    <div className="form-grid">
      <div className="field">
        <label>구역</label>
        <Choice
          label="구역"
          value={zone}
          values={[...PART_ZONES]}
          onChange={pickZone}
        />
      </div>
      {zone !== NO_ZONE && (
        <div className="field">
          <label>부위 · 사진 비교 기준</label>
          <Choice
            label="부위"
            value={custom ? CUSTOM : selected ? selected.id : "부위 선택"}
            values={[...options, { value: CUSTOM, label: "기타 (직접 입력)" }]}
            onChange={(id) => {
              setAddError("")
              if (id === CUSTOM) {
                setCustom(true)
                onChange(undefined)
                return
              }
              setCustom(false)
              onChange(parts.find((p) => p.id === id))
            }}
          />
        </div>
      )}
      {custom && (
        <div className="field">
          <label htmlFor="custom-part">부위 직접 입력 · {zone}</label>
          <div className="actions">
            <Input
              id="custom-part"
              value={name}
              maxLength={PART_NAME_MAX}
              placeholder="예: 다락"
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                // 입력 창 안에서 Enter는 기록을 보내지 않고 부위만 더해요
                if (e.key === "Enter") {
                  e.preventDefault()
                  addPart()
                }
              }}
            />
            <Button
              type="button"
              variant="outline"
              disabled={adding || !name.trim()}
              onClick={addPart}
            >
              부위 더하기
            </Button>
          </div>
          {addError && <p className="muted">{addError}</p>}
        </div>
      )}
    </div>
  )
}
