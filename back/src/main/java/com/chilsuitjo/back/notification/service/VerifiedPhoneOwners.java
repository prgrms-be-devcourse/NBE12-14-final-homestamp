package com.chilsuitjo.back.notification.service;

/**
 * 번호 HMAC이 지금 인증된 활동 회원의 번호인지 번호 잠금 안에서 보는 포트(#83 Q2·Q3, #462). notification이 두고 auth(A)가
 * 구현한다({@link PhoneHmacs}와 같은 방향이라 {@code link → auth}가 생기지 않는다). 링크 그만 받기({@code links/stop})가 원문
 * 번호 없이 링크의 {@code phone_hmac}만으로 "회원이면 수신 거부 목록에 넣지 않는다"(03 K)를 판단할 때 쓴다.
 *
 * <p>경합: 가입은 같은 번호 잠금을 잡고 회원을 만든 뒤 그 번호의 거부 행을 지운다. 그래서 가입이 먼저 커밋되면 이쪽이 회원으로 보고, 이쪽이 먼저면 가입이 나중에 거부
 * 행을 지운다. 탈퇴는 회원 → 비회원 방향이라 번호 잠금을 잡지 않는다(#83 Q4, 어느 쪽이 먼저든 결과가 같음).
 *
 * <p>잠금 순서: 부르는 쪽은 호실(pipeline) → 링크 → 이 번호 잠금 → 회원 확인 → 목록 등록 순으로 잡는다. 가입·번호 변경은 번호 잠금 뒤에 호실을 잠그지
 * 않으므로 교착이 없다.
 */
public interface VerifiedPhoneOwners {

  /**
   * 부르는 쪽 트랜잭션({@code MANDATORY})에서 그 번호의 잠금({@code pg_advisory_xact_lock}, 가입과 같은 키, 트랜잭션이 끝날 때
   * 풀림)을 잡고, 그 번호 HMAC으로 인증한 {@code ACTIVE} 회원이 있으면 {@code true}.
   *
   * <p>실패
   *
   * <ul>
   *   <li>트랜잭션 밖: {@code IllegalTransactionStateException}
   *   <li>{@link PhoneHmacs} 값(소문자 hex 64자)이 아님: {@code IllegalArgumentException}(서버 버그)
   *   <li>회원 번호 HMAC을 아직 채우지 않은 행이 있음(V15 전 가입자, 블루/그린 전환 중 이전 앱이 쓴 행): {@code
   *       IllegalStateException}. {@code false}로 바꾸지 않는다(회원 번호를 비회원 목록에 넣지 않게). 부르는 쪽은 잡지 않고 실패시킨다.
   *       엄격한 CHECK가 들어오면 이 실패는 없어진다
   * </ul>
   *
   * <p>값은 로그·예외 문구에 남기지 않는다.
   */
  boolean lockAndCheckVerifiedMember(String phoneHmac);
}
