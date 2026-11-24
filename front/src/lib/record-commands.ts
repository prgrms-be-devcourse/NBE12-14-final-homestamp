import type { ActionOptions, RecordRequest } from "@/api/records"
import type { DemoState, RecordTarget, Role } from "@/data/demo"

// 입력 창 칸 값 읽기(앞뒤 빈칸 제거)
export type FieldText = (name: string) => string

// 입력 창이 기록을 보내는 함수 모양. 실제 함수는 App이 만들어 넘겨요(기록 API로 보내기 + 안내 + 창 닫기).
// request는 06 4장의 주소, text·update는 목업이 서버 대신 쓰는 값이에요.

// 이 호실 기록으로 남기고, 성공하면 "기록했어요." 안내와 함께 창을 닫아요(거주 종료·정산 끝내기는 한 번 더 물어요)
export type Commit = (
  request: RecordRequest,
  text: string,
  update: (d: DemoState) => void,
  eventRoomId?: string,
  privateTo?: Role,
  target?: RecordTarget,
) => void

// 지금 내 쪽으로 기록을 보내요(창은 닫지 않음). 실패하면 false
export type Act = (
  request: RecordRequest,
  text: string,
  update: (d: DemoState) => void,
  notify?: boolean,
  eventRoomId?: string,
  connecting?: boolean,
) => Promise<{ id?: string } | false>

// 쪽을 정해 보내요(호실 만들기처럼 아직 내 쪽이 없는 기록)
export type Send = (
  request: RecordRequest,
  by: Role,
  text: string,
  update: (d: DemoState) => void,
  options: ActionOptions,
) => Promise<{ id?: string } | false>

// 구역·부위 칸 값(기타면 직접 입력한 이름). 비어 있으면 null
export function readZonePart(zone: string, part: string, text: FieldText) {
  const z = zone === "기타" ? text("customZone") : zone
  const p = part === "기타" ? text("customPart") : part
  return !z || z === "구역 선택" || !p ? null : { zone: z, part: p }
}
export const ZONE_PART_ERROR =
  "구역과 부위를 선택하고, 기타는 이름을 직접 입력해 주세요."

// 상대 휴대폰 번호: 010으로 시작하는 11자리, 내 계정 번호는 안 돼요
export const onlyDigits = (v: string) => v.replace(/\D/g, "")
export const isMobile = (phone: string) => /^010\d{8}$/.test(phone)
