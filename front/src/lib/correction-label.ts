import type { FactCorrection } from "@/data/demo"

// 고친 내용의 상태 문구(상태와 규칙 G: 도장 전이면 바로 바뀜, 도장 뒤면 상대 확인이 있어야 바뀜)
export function correctionLabel(
  c: FactCorrection<string | number>,
  stage?: string,
) {
  if (c.status === "확인 대기")
    return stage === "미확인"
      ? "확인 없음 · 72시간 동안 답이 오지 않았어요 · 지금 기록 그대로"
      : stage === "재통지"
        ? "확인 전 · 다시 알렸어요"
        : "상대 확인 전"
  if (c.status === "반영")
    return c.mode === "확인 전"
      ? "확인 전에 고침"
      : c.mode === "상대 없음"
        ? "고쳐짐 · 혼자 기록할 때 고침"
        : c.mode === "상대 미확인"
          ? "고쳐짐 · 확인 없음일 때 고침"
          : "고쳐짐 · 상대 확인"
  if (c.status === "거절")
    return c.answerKind === "보류"
      ? "보충 요청됨 · 지금 기록 그대로"
      : c.answerKind === "이견"
        ? "이견 · 지금 기록 그대로"
        : "동의하지 않음 · 지금 기록 그대로"
  if (c.status === "대체됨") return "새로 고쳐서 바뀜"
  if (c.status === "처리 완료로 닫힘") return "처리 완료로 닫힘"
  return "거둠 · 지금 기록 그대로"
}
