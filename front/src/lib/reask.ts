// 다시 확인 요청 규칙(03 G '응답과 그 뒤'·'다시 확인 요청의 한도'). 수리 건·지불·점검 항목이 같이 써요.
export const REASK_DAYS = 7
export function reaskWaitDays(reaskedAt?: string, now = Date.now()) {
  if (!reaskedAt) return 0
  const left = Date.parse(reaskedAt) + REASK_DAYS * 86_400_000 - now
  return left > 0 ? Math.ceil(left / 86_400_000) : 0
}
export function assertCanReask(reaskedAt: string | undefined, now: number) {
  const days = reaskWaitDays(reaskedAt, now)
  if (days > 0) throw new Error(`${days}일 뒤 다시 요청할 수 있어요.`)
}
// 다시 요청에는 새 근거가 하나 이상 있어야 해요(03 G: "같은 요청 반복 방지").
// 이미 올린 사진, 이미 남긴 설명(원래 설명·이전 보충)과 같은 것은 새 근거로 치지 않아요.
export const NEW_EVIDENCE_REQUIRED =
  "새 사진이나 새 설명을 하나 이상 더해 주세요. 이미 남긴 사진이나 설명과 같은 것은 보충이 되지 않아요."
export function newEvidence(
  data: { text: string; photos: string[] },
  had: { texts: (string | undefined)[]; photos: string[] },
) {
  const norm = (t?: string) => (t ?? "").replace(/\s+/g, " ").trim()
  const text = norm(data.text)
  const photos = data.photos.filter((p) => !had.photos.includes(p))
  const freshText = text && !had.texts.some((t) => norm(t) === text) ? text : ""
  if (!freshText && !photos.length) throw new Error(NEW_EVIDENCE_REQUIRED)
  return { text: freshText, photos }
}
