import type { AcknowledgementKind } from "@/api/auth"

// 가입 확인 항목과 약관 다시 확인이 함께 쓰는 문구(03 6-2, CONS-1·4). 정식 약관 전문이 준비되기 전까지는 이 요약과 안내만 보여요.
// 옛 판·바뀐 점은 실제로 제공된 문구가 없어 화면에 만들지 않아요. 보여 주는 부품은 terms-blocks.tsx

// 개인정보 처리 안내(PRIVACY_NOTICE_ACK). 동의가 아니라 안내와 확인이에요
export const PRIVACY_NOTICE_POINTS = [
  "같은 호실의 상대 쪽에게 내가 남긴 사진·설명·행동 기록과 가린 이름(예: 정○○)이 보여요.",
  "기록은 고치거나 지우지 않고 덧붙이며, 정산 완료 뒤 1년 동안 읽기 전용으로 보관한 뒤 삭제해요.",
  "탈퇴하면 혼자 쓴 기록과 계정은 지워지고, 상대와 함께 만든 기록은 이름을 가린 채 보관 기간까지 남아요.",
  "주민등록번호는 받지 않아요. 계약서·신분증·통장 사진은 올리지 마세요.",
]

// 이용약관(TERMS_AGREED) 보기 창의 설명과 요약
export const TERMS_DESCRIPTION =
  "HomeStamp는 세입자와 집주인이 같은 기록을 함께 보도록 돕는 서비스예요."
export const TERMS_SUMMARY = [
  "HomeStamp는 누가 얼마를 낼지 판단하지 않아요. 사람이 입력하고 상대가 확인한 사실만 남겨요.",
  "합의는 양쪽이 같은 안에 동의해야만 생기고, 사실(고쳤다·냈다·받았다)은 혼자서도 남길 수 있어요.",
  "약관 전문은 서비스 정식 공개 때 이 화면에 게시돼요.",
]

// 확인 항목 이름(가입 화면의 체크 문구와 같은 뜻)
export const ACKNOWLEDGEMENT_LABELS: Record<AcknowledgementKind, string> = {
  AGE_14_CONFIRMED: "만 14세 이상이에요",
  TERMS_AGREED: "이용약관에 동의해요",
  PRIVACY_NOTICE_ACK: "개인정보 처리 안내를 확인했어요",
}
