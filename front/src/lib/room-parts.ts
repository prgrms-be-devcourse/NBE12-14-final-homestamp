import { ApiError } from "@/api/errors"

// 호실 부위(06 4-3 GET /api/rooms/{id}/parts 한 줄, 05 room_part). 칸 이름은 백엔드 RoomPartView와 같아요.
// 수리 건·점검 항목·부위 사진은 이 id(roomPartId)로 부위를 가리켜요. 화면 글자("구역 · 부위")는 id로 찾아 만들어요.
export interface RoomPart {
  id: string
  zone: string
  name: string
  // 설비·가전 켜고 끔(ROOM-3d). 꺼 두면 새 기록을 남길 수 없어요
  enabled: boolean
  // 숨김(ROOM-3a). 목록 응답에는 남지만 기본 화면 목록에서는 빼요
  hidden: boolean
  // 켜고 끌 수 있는 부위(기본 목록의 설비·가전)
  toggleable: boolean
}
export interface RoomPartList {
  // 공유를 그만둔 뒤 보이는 범위를 고정한 시각(없으면 null, 03 6-6 9번). 지금은 받아만 둬요
  asOf: string | null
  parts: RoomPart[]
}
// 부위 더하기 본문(06 4-3 POST /api/rooms/{id}/parts)
export interface NewRoomPart {
  zone: string
  name: string
}

// V7 기본 6구역(ROOM-3c). 부위를 더할 때 구역은 이 글자와 같아야 해요(직접 입력 없음)
export const PART_ZONES = [
  "현관",
  "방",
  "주방",
  "욕실",
  "설비·가전",
  "기타",
] as const
// V7 seed_part_template.sql과 같은 순서의 기본 부위 29개. optional = 설비·가전(켜고 끔)
export const PART_TEMPLATES: readonly {
  zone: string
  name: string
  optional: boolean
}[] = [
  ...["현관문·도어락", "신발장", "바닥"].map((name) => ({
    zone: "현관",
    name,
  })),
  ...[
    "벽",
    "천장",
    "바닥",
    "창문·창틀",
    "방충망",
    "붙박이장",
    "조명",
    "콘센트·스위치",
  ].map((name) => ({ zone: "방", name })),
  ...["싱크대·배수구", "수전", "가스레인지·인덕션", "후드", "수납장"].map(
    (name) => ({ zone: "주방", name }),
  ),
  ...[
    "천장",
    "벽·타일",
    "바닥·배수구",
    "변기",
    "세면대·수전",
    "샤워기·욕조",
    "환풍기",
  ].map((name) => ({ zone: "욕실", name })),
  ...["보일러", "에어컨", "냉장고", "세탁기", "인터폰"].map((name) => ({
    zone: "설비·가전",
    name,
  })),
  { zone: "기타", name: "기타" },
].map((t) => ({ ...t, optional: t.zone === "설비·가전" }))

// 호실을 만들 때 기본 목록을 그 호실의 부위로 복사해요(ROOM-1a). 서버처럼 호실마다 새 ID예요(목업용)
export const copyDefaultParts = (): RoomPart[] =>
  PART_TEMPLATES.map((t) => ({
    id: crypto.randomUUID(),
    zone: t.zone,
    name: t.name,
    enabled: true,
    hidden: false,
    toggleable: t.optional,
  }))

// 화면 글자: "욕실 · 천장". 기록의 zone 칸과 같은 모양이에요
export const partLabel = (p: Pick<RoomPart, "zone" | "name">) =>
  `${p.zone} · ${p.name}`
// 화면 글자를 구역·이름으로 나눠요(부위 ID가 없는 이전 기록에서 같은 부위를 찾을 때만)
export const splitPartLabel = (label: string) => {
  const [zone, ...rest] = label.split(" · ")
  return { zone, name: rest.join(" · ") }
}
// 새 기록에 고를 수 있는 부위: 숨긴 부위(ROOM-3a)와 꺼 둔 설비·가전(ROOM-3d)은 빼요. 서버도 VALIDATION_FAILED로 막아요
export const isPickable = (p: RoomPart) => !p.hidden && p.enabled
// 이미 있는 기록의 부위 글자: 목록에서 id로 찾고, 없으면(공유를 그만둔 뒤 등) 서버가 준 이름을 그대로 써요
export const partLabelOf = (
  parts: readonly RoomPart[] | undefined,
  id: string | undefined,
  fallback: string,
) => {
  const p = id ? parts?.find((x) => x.id === id) : undefined
  return p ? partLabel(p) : fallback
}

// 부위 더하기 검사(06 4-3, ROOM-3c): 구역은 6구역과 글자까지 같고, 이름은 앞뒤 공백을 자르고 1~50자, NUL 문자 불가
export const PART_NAME_MAX = 50
export function checkNewPart(b: NewRoomPart): NewRoomPart {
  const name = b.name.trim()
  const fail = (detail: string) =>
    new ApiError({ status: 400, code: "VALIDATION_FAILED", detail })
  if (!(PART_ZONES as readonly string[]).includes(b.zone))
    throw fail("구역을 골라 주세요.")
  if (!name || name.includes("\u0000")) throw fail("부위 이름을 입력해 주세요.")
  if (name.length > PART_NAME_MAX)
    throw fail(`부위 이름은 ${PART_NAME_MAX}자까지 쓸 수 있어요.`)
  return { zone: b.zone, name }
}
