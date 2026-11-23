package com.chilsuitjo.back.notification.rule;

/**
 * 당사자 자리의 생애. room의 {@code PartyStatus}(05 {@code party.status})를 notification이 import하지 않고 이 값으로
 * 받는다(09 3장, notification은 도메인을 부르지 않는다).
 *
 * <p>05의 {@code SHARING_STOPPED}는 없다. 공유 중단은 {@code PartyFacts.sharingStoppedAt}으로 따로 받고, 자리는
 * {@link #CONNECTED}다. 탈퇴하면 {@code sharing_stopped_at}이 남아 있어도 {@link #WITHDRAWN}이다(03 B "판단은 상대 자리
 * 상태로").
 */
public enum PartyPresence {
  /** 상대 자리가 아직 없음(초대 전). */
  EMPTY,
  /** 번호만 있는 자리(나만 보기 등). */
  NUMBER_ONLY,
  /** 초대 링크를 보냄, 연결 전. */
  INVITED,
  /** 회원으로 연결됨(공유 중단 포함). */
  CONNECTED,
  /** 초대를 거절함. */
  REJECTED,
  /** 탈퇴. */
  WITHDRAWN
}
