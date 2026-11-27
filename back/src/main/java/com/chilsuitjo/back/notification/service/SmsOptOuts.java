package com.chilsuitjo.back.notification.service;

/**
 * 수신 거부 목록({@code state.sms_opt_out}, 05 {@code sms_opt_out}) 조회 포트(#163 1-3·4-2). notification이 두고
 * 구현한다(D). 번호는 {@link PhoneHmacs}로 번호 HMAC으로 바꿔 찾고, HMAC 값은 밖으로 내지 않는다(나중에 키 교체로 옛 키 값도 찾게 되어도 부르는
 * 쪽은 그대로다).
 *
 * <p>지금은 조회만 있다. 등록(그만 받기)과 번호 주인 확인 뒤 해제(감사·{@code AUDIT_WRITE} 포함)는 #163 1·2·4 답을 받은 뒤 더한다. 그래서
 * 지금 {@code false}는 "그 번호의 거부 행이 없음"일 뿐, 그만 받기 기능이 있어서 아무도 거부하지 않았다는 뜻은 아니다.
 *
 * <p>기본값을 돌려주는 가짜를 main에 두지 않는다(수신 거부 확인을 운영에서 조용히 건너뛰지 않게). 테스트 가짜는 test 코드에만 둔다.
 */
public interface SmsOptOuts {

  /**
   * 그 번호가 수신 거부 목록에 있는지 본다. 형식만 다른 번호(하이픈 등)는 같은 번호다({@link PhoneHmacs}가 정규화).
   *
   * <ul>
   *   <li>{@code true}: 그 번호의 수신 거부 행이 있다
   *   <li>{@code false}: HMAC을 정상으로 계산해 조회했고 행이 없다
   *   <li>예외: 정규화할 수 없는 번호는 {@code BusinessException(VALIDATION_FAILED)}를 그대로 올린다(서버가 저장된 번호로 부른
   *       경우도 같음). HMAC 계산·DB 조회 실패도 예외로 올린다. 어느 실패도 {@code false}로 바꾸지 않으므로, 부르는 쪽도 판정 실패를 "거부
   *       없음"으로 다루지 않는다
   * </ul>
   *
   * <p>읽기만 하고 잠그지 않는다. 부르는 쪽 트랜잭션이 있으면 그 안에서 읽는다. 결과는 읽은 순간의 값이라, 초대 때 확인해도 문자 보내기 직전 확인(#163 켜는 순서
   * 4, 아직 없음)을 대신하지 않는다. 번호와 HMAC 값은 로그·예외 문구에 남기지 않는다.
   */
  boolean isOptedOut(String phone);
}
