package com.chilsuitjo.back.notification.service;

import java.time.Instant;

/**
 * 수신 거부 목록({@code state.sms_opt_out}, 05 {@code sms_opt_out}) 포트(#163 1-3·4-2). notification이 두고
 * 구현한다(D). 번호는 {@link PhoneHmacs}로 번호 HMAC으로 바꿔 쓰고, HMAC 값은 밖으로 내지 않는다(나중에 키 교체로 옛 키 값도 찾게 되어도 부르는
 * 쪽은 그대로다).
 *
 * <p>조회·등록·해제가 있다(#163 7184980200, #370 A-1~A-4 A 동의 7211690277). 등록을 부르는 곳(그만 받기 {@code
 * links/stop})과 해제를 부르는 곳(가입·번호 변경 완료)은 아직 없다. 그래서 지금 {@code false}는 "그 번호의 거부 행이 없음"일 뿐, 그만 받기 기능이
 * 켜져서 아무도 거부하지 않았다는 뜻은 아니다.
 *
 * <p>기본값을 돌려주는 가짜를 main에 두지 않는다(수신 거부 확인을 운영에서 조용히 건너뛰지 않게). 테스트 가짜는 test 코드에만 둔다.
 *
 * <p>모든 메서드는 번호를 정규화할 수 없으면 {@code BusinessException(VALIDATION_FAILED)}를, HMAC 계산·DB 실패는 그 예외를 그대로
 * 올린다. 실패를 "거부 없음"·"지울 행 없음"으로 바꾸지 않는다. 번호와 HMAC 값은 로그·예외 문구에 남기지 않는다.
 */
public interface SmsOptOuts {

  /**
   * 그 번호가 수신 거부 목록에 있는지 본다. 형식만 다른 번호(하이픈 등)는 같은 번호다({@link PhoneHmacs}가 정규화).
   *
   * <ul>
   *   <li>{@code true}: 그 번호의 수신 거부 행이 있다
   *   <li>{@code false}: HMAC을 정상으로 계산해 조회했고 행이 없다
   * </ul>
   *
   * <p>읽기만 하고 잠그지 않는다. 부르는 쪽 트랜잭션이 있으면 그 안에서 읽는다. 결과는 읽은 순간의 값이라, 초대 때 확인해도 문자 보내기 직전
   * 확인(OutboxWorker)을 대신하지 않는다.
   */
  boolean isOptedOut(String phone);

  /**
   * 그 번호를 수신 거부 목록에 넣는다(06 NOTI-8a의 저장 한 부분). 부르는 쪽 트랜잭션 안에서만 부른다({@code MANDATORY}). 그만 받기의 {@code
   * OPT_OUT} 행동·자리 처리와 함께 커밋되거나 함께 되돌아가야 하기 때문이다.
   *
   * <ul>
   *   <li>{@code true}: 새로 넣었다
   *   <li>{@code false}: 이미 있었다(다른 그만 받기가 먼저 커밋함). 아무것도 바꾸지 않는다
   * </ul>
   *
   * @param at 거부 행의 시각(부르는 쪽 행동 시각)
   */
  boolean optOut(String phone, Instant at);

  /**
   * 번호 주인이 가입·번호 인증을 <b>완료</b>한 트랜잭션에서 그 번호의 수신 거부를 푼다(06 NOTI-8c, decisions 2026-11-16, #370 A-1).
   * 부르는 쪽 트랜잭션 안에서만 부른다({@code MANDATORY}). 번호 주인이 맞는지는 부르는 쪽(auth)이 보장한다. 인증 코드를 보내거나 맞히기만 한 단계에서는
   * 부르지 않는다. 과거 {@code OPT_OUT} 행동과 그 전 초대는 그대로다(옛 초대가 다시 유효해지지 않는다).
   *
   * <ul>
   *   <li>{@code true}: 행을 지우고, 같은 트랜잭션에 삭제 감사({@code deletion_log}: {@code SMS_OPT_OUT}, {@code
   *       OWNER_VERIFIED}, 번호 HMAC)와 {@code AUDIT_WRITE} 아웃박스를 남겼다
   *   <li>{@code false}: 지울 행이 없어 아무것도 바꾸지 않았고 감사도 없다. 다시 불러도 감사가 두 번 생기지 않는다
   *   <li>예외: 그 트랜잭션 전체(가입 포함)가 되돌아가야 한다. 부르는 쪽은 잡아서 가입을 계속하지 않는다
   * </ul>
   *
   * <p>등록과 겹칠 때(번호 잠금 없음, #370 A-3): 결과는 이 메서드의 삭제 문장이 도는 순간에 정해진다. 그때 이미 커밋된 거부 행이 있으면 지우고 {@code
   * true}, 아직 커밋되지 않은 등록 행은 보지 못해 {@code false}다. 그래서 이 트랜잭션이 등록보다 늦게 커밋되어도 거부가 남을 수 있다(커밋 순서가 아니라
   * 삭제 문장 시점이 기준). 지우려는 행을 다른 해제가 지우는 중이면 그 커밋을 기다렸다가 다시 보고, 먼저 지운 쪽만 {@code true}다. PostgreSQL 기본
   * 격리(READ COMMITTED)를 전제로 한다.
   *
   * @param at 감사·아웃박스 시각(부르는 쪽 완료 시각)
   */
  boolean releaseForVerifiedOwner(String phone, Instant at);
}
