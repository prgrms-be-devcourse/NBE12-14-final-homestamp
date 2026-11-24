import type { WaitStage } from "@/data/demo"
import { Status } from "@/components/common/blocks"
import { waitLabel, type WaitLabelOptions } from "@/lib/words"

// "확인 없음" 표시(09 5장 "같은 뜻은 한 부품", 03 1-1 기다림): 다시 알렸으면 "다시 알렸어요",
// 답이 없으면 "(누구) 확인 없음". 호실 단위 안내(상대 없음)는 counterpart-notice가 맡아요.
export function WaitStatus({
  stage,
  ...options
}: { stage?: WaitStage } & WaitLabelOptions) {
  if (!stage) return null
  return <Status>{waitLabel(stage, options)}</Status>
}
