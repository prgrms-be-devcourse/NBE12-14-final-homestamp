package com.chilsuitjo.back.notification.service;

/**
 * 번호를 번호 HMAC으로 바꾸는 포트(#163 1-3). notification이 두고 auth(A)가 구현한다. 수신 거부({@code
 * sms_opt_out.phone_hmac})·삭제 감사({@code deletion_log.phone_hmac})·발송 한도가 같은 값을 써야 해서 계산은 이 한 곳에서만
 * 한다. 키({@code app.keys.phone-hmac})는 auth에 그대로 두고, 정규화는 auth 구현이 {@code global/common}의 {@code
 * PhoneNumber}로 한다(인증 번호·초대 번호와 같은 형식, decisions 2026-11-26). notification은 도메인을 부르지 않는다(09 3장).
 *
 * <p>계약은 #281에서 A·D가 정했다(decisions 2026-11-26). auth 구현은 기존 {@code VerificationKeys}의 번호 HMAC 키를
 * 그대로 쓰고, 번호 잠금 키({@code phoneLockKey})와 이 저장용 값은 쓰임에 맞게 따로 둔다. 구현은 auth의 {@code PhoneHmacService}
 * 하나이고 모든 환경에서 켜진다(키가 없으면 앱이 시작하지 않으므로 빈이 빠진 채 뜨는 일이 없다).
 *
 * <p>기본값을 돌려주는 가짜를 main에 두지 않는다(수신 거부 확인·해제를 운영에서 조용히 건너뛰지 않게). 테스트 가짜는 이 포트를 처음 쓰는 {@code
 * SmsOptOuts} 구현과 함께 test 코드에만 둔다.
 *
 * <p>키 교체: {@code sms_opt_out}에는 번호 원문과 키 버전이 없어서, 키를 바꾸면 기존 수신 거부 행을 찾지 못한다. 키를 유지·교체하는 원칙은 아직 정하지
 * 않았고, 수신 거부를 운영에 켜기 전에 정해야 한다. 이 포트는 지금 키 하나로만 계산한다. 옛 키 값도 찾아야 하게 되면 메서드를 더하는 것으로 하고, 이 메서드의 뜻은
 * 바꾸지 않는다.
 */
public interface PhoneHmacs {

  /**
   * 번호 HMAC. 구현은 {@code PhoneNumber.normalize}로 정규화한 번호를 지금 키로 HMAC-SHA256 하고, 32바이트 전체를 소문자 hex
   * 64자로 돌려준다. 같은 키에서 같은 번호(하이픈 등 형식만 다른 입력 포함)는 늘 같은 값이다. 번호 잠금 키({@code phoneLockKey}, 같은 HMAC의 앞
   * 8바이트)와는 쓰임이 달라 따로 둔다.
   *
   * <p>실패
   *
   * <ul>
   *   <li>정규화할 수 없는 번호: {@code PhoneNumber.normalize}와 같이 {@code
   *       BusinessException(VALIDATION_FAILED)}. 서버가 저장된 번호로 부른 경우에도 같은 예외를 그대로 올린다(삼키거나 다른 값으로 바꾸지
   *       않음)
   *   <li>키가 없거나 다른 키와 같은 설정 오류: 이 메서드가 아니라 앱 시작에서 막는다({@code VerificationKeys}). 사용자 입력 오류와 섞지 않는다
   *   <li>그 밖의 계산 실패는 예외로 올린다. 부르는 쪽은 이 실패를 "수신 거부 없음" 같은 정상 결과로 바꾸지 않는다
   * </ul>
   *
   * <p>번호와 HMAC 값은 로그·예외 문구에 남기지 않는다.
   */
  String phoneHmac(String phone);
}
