package com.chilsuitjo.back.room.rule;

import java.util.Objects;

/**
 * {@link PartyTransitions}가 다루는 상대 자리 하나의 상태(03 B "당사자 연결"). 규칙이 Repository를 부르지 않도록 판단에 필요한 사실을
 * {@code StateStore.load}가 잠근 뒤 읽어 담는다(09 3-1 "계약").
 *
 * @param status 자리 상태
 * @param byCreatorSide 행동하는 회원이 호실을 만든 측인지({@code room_record.created_by_side}). 초대만 본다
 * @param living 호실이 거주 중인지. 초대만 본다(거주 종료 뒤 초대는 {@code INVALID_STATE}, #82·#163 3-1)
 * @param selfInvite 초대할 번호가 행동하는 회원의 인증 번호와 같은지. 초대만 본다(06 ROOM-4a)
 * @param phoneMatches 이 자리의 초대 번호가 행동하는 회원의 인증 번호와 같은지. 연결·거절만 본다(06 ROOM-5a·5b)
 */
public record PartyState(
    PartyStatus status,
    boolean byCreatorSide,
    boolean living,
    boolean selfInvite,
    boolean phoneMatches) {

  public PartyState {
    Objects.requireNonNull(status, "status");
  }
}
