package com.chilsuitjo.back.room.rule;

import java.util.Objects;

/**
 * {@link PartyTransitions}가 다루는 상대 자리 하나의 상태(03 B "당사자 연결"). 규칙이 Repository를 부르지 않도록 판단에 필요한 사실을
 * {@code StateStore.load}가 잠근 뒤 읽어 담는다(09 3-1 "계약").
 *
 * <p><b>요청할 때만 아는 값</b>({@code byCreatorSide}·{@code living}·{@code selfInvite}·{@code
 * phoneMatches}·{@code alreadyInRoom}): 호실 단계·인증 번호처럼 행동 기록 밖의 사실이라 {@code load}가 그때 읽어 채운다. 행동
 * 기록만으로 다시 계산할 때({@code Replay.fold}, REP-10)는 알 수 없어 {@code null}이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가
 * 이미 확인했다). {@code RepairState}·{@code InspectionState}와 같은 방식이다.
 *
 * @param status 자리 상태
 * @param byCreatorSide 행동하는 회원이 호실을 만든 측인지({@code room_record.created_by_side}). 초대만 본다. 재계산이면 null
 * @param living 호실이 거주 중인지. 초대만 본다(거주 종료 뒤 초대는 {@code INVALID_STATE}, #82·#163 3-1). 재계산이면 null
 * @param selfInvite 초대할 번호가 행동하는 회원의 인증 번호와 같은지. 초대만 본다(06 ROOM-4a). 재계산이면 null
 * @param phoneMatches 이 자리의 초대 번호가 행동하는 회원의 인증 번호와 같은지. 연결·거절만 본다(06 ROOM-5a·5b). 재계산이면 null
 * @param alreadyInRoom 행동하는 회원이 이 호실의 다른 자리에 이미 있는지. 연결·거절만 본다(한 호실에 한 회원은 한 자리, 05 {@code
 *     uq_party_room_member}·불변식 13). 재계산이거나 보지 않는 행동이면 null
 */
public record PartyState(
    PartyStatus status,
    Boolean byCreatorSide,
    Boolean living,
    Boolean selfInvite,
    Boolean phoneMatches,
    Boolean alreadyInRoom) {

  public PartyState {
    Objects.requireNonNull(status, "status");
  }

  /** {@code alreadyInRoom}을 보지 않는 행동(초대)의 상태. */
  public PartyState(
      PartyStatus status,
      Boolean byCreatorSide,
      Boolean living,
      Boolean selfInvite,
      Boolean phoneMatches) {
    this(status, byCreatorSide, living, selfInvite, phoneMatches, null);
  }
}
