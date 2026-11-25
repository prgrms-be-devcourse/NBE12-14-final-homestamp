// 휴대폰 번호(06 4-0 "전화번호"): 숫자만 남긴 한 형식으로 저장·비교해요. 예: 010-1234-5678 → 01012345678
export const normalizePhone = (phone?: string | null) =>
  (phone ?? "").replace(/\D/g, "")

// 국내 휴대폰 번호: 010으로 시작하는 11자리
export const isMobilePhone = (phone?: string | null) =>
  /^010\d{8}$/.test(normalizePhone(phone))

// 화면에 보일 때는 가운데 네 자리를 가려요
export const maskPhone = (phone?: string | null) => {
  const n = normalizePhone(phone)
  return n.length === 11 ? `${n.slice(0, 3)}-••••-${n.slice(7)}` : n
}
