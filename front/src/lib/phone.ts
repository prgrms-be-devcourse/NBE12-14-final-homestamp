// 휴대폰 번호(06 4-0 "전화번호"): 숫자만 남긴 한 형식으로 저장·비교해요. 예: 010-1234-5678 → 01012345678
export const normalizePhone = (phone?: string | null) =>
  (phone ?? "").replace(/\D/g, "")

// 국내 휴대폰 번호(서버 auth/rule/PhoneNumber와 같은 규칙): 010은 11자리, 예전 번호 011·016·017·018·019는 10~11자리
export const isMobilePhone = (phone?: string | null) =>
  /^(010\d{8}|01[16789]\d{7,8})$/.test(normalizePhone(phone))

// 번호가 규칙에 맞지 않을 때 화면 안내
export const MOBILE_PHONE_MESSAGE =
  "휴대폰 번호를 확인해 주세요. 010·011·016·017·018·019로 시작하는 번호만 받아요."

// 화면에 보일 때는 가운데 자리를 가려요(11자리는 네 자리, 10자리는 세 자리)
export const maskPhone = (phone?: string | null) => {
  const n = normalizePhone(phone)
  if (n.length === 11) return `${n.slice(0, 3)}-••••-${n.slice(7)}`
  if (n.length === 10) return `${n.slice(0, 3)}-•••-${n.slice(6)}`
  return n
}
