import type { ActionOptions, RecordRequest } from "@/api/records"
import type { DemoState, RecordTarget, Role } from "@/data/demo"

// 입력 창 칸 값 읽기(앞뒤 빈칸 제거)
export type FieldText = (name: string) => string

// 입력 창이 기록을 보내는 함수 모양. 실제 함수는 App이 만들어 넘겨요(기록 API로 보내기 + 안내 + 창 닫기).
// request는 06 4장의 주소, text·update는 목업이 서버 대신 쓰는 값이에요.

// 이 호실 기록으로 남기고, 성공하면 "기록했어요." 안내와 함께 창을 닫아요(거주 종료·정산 끝내기는 한 번 더 물어요).
// 저장 결과(성공 true, 실패 false)를 돌려주면 창이 보내는 동안 [보내기]를 막을 수 있어요
export type Commit = (
  request: RecordRequest,
  text: string,
  update: (d: DemoState) => void,
  eventRoomId?: string,
  privateTo?: Role,
  target?: RecordTarget,
) => void | Promise<boolean>

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

// 구역·부위 칸(PartField)에서 부위를 고르지 않았을 때
export const ZONE_PART_ERROR =
  "구역과 부위를 선택해 주세요. 맞는 부위가 없으면 기타(직접 입력)로 더해 주세요."
