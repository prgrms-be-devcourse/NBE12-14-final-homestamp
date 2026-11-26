import type { Room } from "@/data/demo"

export interface RoomOption {
  value: string
  label: string
}

// 호실 고르기 칸의 항목. 이름(별칭)은 겹칠 수 있어서(다른 건물의 302호, 같은 집의 다음 거주 기간) 값은 호실 ID로 두고,
// 이름이 겹치는 것만 주소를, 주소까지 같으면 입주일과 호실을 만든 날(둘 다 없으면 상태)을 붙여 구분해요.
export function roomOptions(rooms: Room[]): RoomOption[] {
  const count = (key: (r: Room) => string, r: Room) =>
    rooms.filter((x) => key(x) === key(r)).length
  const withAddress = (r: Room) => `${r.name} · ${r.address}`
  return rooms.map((r) => {
    if (count((x) => x.name, r) < 2) return { value: r.id, label: r.name }
    if (count(withAddress, r) < 2) return { value: r.id, label: withAddress(r) }
    const dates = [
      r.since && `${r.since} 입주`,
      r.createdAt && `${r.createdAt} 만듦`,
    ].filter(Boolean)
    return {
      value: r.id,
      label: `${withAddress(r)} · ${dates.length ? dates.join(" · ") : r.status}`,
    }
  })
}
