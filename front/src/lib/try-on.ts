import type { Repair } from "@/data/demo"

// 상태를 바꾸는 행동은 먼저 복사본에 적용해 규칙 위반(오류)을 확인한 뒤 실제로 기록합니다.
export function tryOn(r: Repair, update: (draft: Repair) => void): string {
  try {
    update(structuredClone(r))
    return ""
  } catch (e) {
    return e instanceof Error ? e.message : "처리할 수 없어요."
  }
}
