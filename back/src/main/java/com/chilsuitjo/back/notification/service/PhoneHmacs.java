package com.chilsuitjo.back.notification.service;

/**
 * 번호를 번호 HMAC으로 바꾸는 포트(#163 1-3). notification이 두고 auth(A)가 구현한다. 수신 거부({@code
 * sms_opt_out.phone_hmac})·삭제 감사({@code deletion_log.phone_hmac})·발송 한도가 같은 값을 써야 해서 계산은 이 한 곳에서만
 * 한다. 키({@code app.keys.phone-hmac})와 정규화({@code PhoneNumber})는 auth에 그대로 두고, notification은 도메인을
 * 부르지 않는다(09 3장).
 *
 * <p><b>[제안 · #163 리뷰 대기]</b> 시그니처와 값 모양은 이 PR에서 A와 정한다. 정해지기 전에는 구현하거나 주입받지 않는다.
 *
 * <p>운영 구현이 들어오기 전에는 이 타입의 빈이 없다. 기본값을 돌려주는 가짜를 main에 두지 않는다(수신 거부 확인·해제를 운영에서 조용히 건너뛰지 않게). 테스트
 * 가짜는 이 포트를 처음 쓰는 {@code SmsOptOuts} 구현과 함께 test 코드에만 둔다.
 *
 * <p>키 교체: {@code sms_opt_out}에는 번호 원문과 키 버전이 없어서, 키를 바꾸면 기존 수신 거부 행을 찾지 못한다. 키를 유지·교체하는 원칙은 수신 거부를
 * 운영에 켜기 전에 따로 정한다(아직 정한 것 없음). 이 포트는 지금 키 하나로만 계산한다.
 */
public interface PhoneHmacs {

  /**
   * 번호 HMAC. 구현은 {@code PhoneNumber.normalize}로 정규화한 번호를 지금 키로 HMAC-SHA256 하고, 32바이트 전체를 소문자 hex
   * 64자로 돌려준다. 같은 키에서 같은 번호(하이픈 등 형식만 다른 입력 포함)는 늘 같은 값이다. 번호 잠금 키({@code phoneLockKey}, 같은 HMAC의 앞
   * 8바이트)와는 쓰임이 달라 따로 둔다.
   *
   * <p>정규화할 수 없는 번호는 {@code PhoneNumber.normalize}와 같이 {@code
   * BusinessException(VALIDATION_FAILED)}이다. 번호와 HMAC 값은 로그·예외 문구에 남기지 않는다.
   */
  String phoneHmac(String phone);
}
