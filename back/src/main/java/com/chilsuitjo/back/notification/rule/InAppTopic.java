package com.chilsuitjo.back.notification.rule;

/**
 * 알려 줌·서비스 안내의 주제(V14 {@code ck_in_app_topic}, #116). 주제가 종류와 범위(계정·호실)를 정한다. 이름은 받는 사람이 겪은 사건을
 * 과거형으로 쓰고, 문구는 08이 정한다. 주제를 더하면 새 V 파일로 CHECK도 다시 만든다.
 */
public enum InAppTopic {
  /**
   * 번호 인증 해제(03 L, 불변식 8): 다른 계정이 같은 번호를 인증해 이 계정의 번호 인증이 풀렸다. 계정 알림이라 호실·대상·요청 ID가 없고 묶지 않는다. 가입과
   * 같은 트랜잭션에 저장하고 실패하면 가입도 실패한다(#106).
   */
  PHONE_VERIFICATION_RELEASED(InAppKind.SERVICE, false),

  /**
   * 상대가 수리 건에 사진을 더했다(06 REP-7g, 08 "덧붙이기": 확인이 필요 없어 서비스 안 알림만). 호실 알림이고 대상은 그 수리 건이다. 사진 추가와 같은
   * 트랜잭션에 저장한다(#381).
   */
  REPAIR_PHOTO_ADDED(InAppKind.INFO, true);

  private final InAppKind kind;
  private final boolean roomScoped;

  InAppTopic(InAppKind kind, boolean roomScoped) {
    this.kind = kind;
    this.roomScoped = roomScoped;
  }

  public InAppKind kind() {
    return kind;
  }

  /** 호실 알림이면 true(호실 필수), 계정 알림이면 false(호실·대상 없음). */
  public boolean roomScoped() {
    return roomScoped;
  }
}
