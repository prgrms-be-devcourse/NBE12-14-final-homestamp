package com.chilsuitjo.back.action.rule;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 행동 종류 전체. 기준은 05 2-3 {@code type} 목록이고, DB {@code ck_action_type}(V1)과 같다. 새 행동은 05 표와 새 V 파일을 같은
 * PR에서 고치고 여기에 한 줄 더한다(B가 성질을 정함).
 *
 * <p>DB의 {@code action.type}에는 {@code name()}이 글자로 저장된다. 각 도메인의 {@code Transitions}는 자기 영역({@link
 * #area()})의 행동만 받는다.
 *
 * <p>성질은 pipeline이 호실 단위로 막을 때 쓴다(09 3-1 {@code ActionType}·순서 3, decisions 2026-11-18). 막힌 상태에서도
 * 되는 행동에만 {@link Allow} 표시를 단다. 표시가 없으면 막는다(허용 목록).
 *
 * <ul>
 *   <li>{@link Allow#AFTER_SETTLED}: 정산 완료 뒤에도 된다. 개인정보 사진 지우기와 다시 공유(보기만)(06 CONS-3i)
 *   <li>{@link Allow#ON_JOINED}: 합류 전 기록에서도 된다. 개인정보 사진 지우기만(decisions 2026-11-17)
 *   <li>{@link Allow#WHILE_SHARING_STOPPED}: 공유를 그만둔 사람도 된다. 나만 보는 기록과 거주 종료·다시 공유(03 B)
 * </ul>
 *
 * <p>사진을 어디에 붙이는지처럼 대상으로 갈리는 것은 표시로 정하지 않고 도메인 {@code Transitions}가 본다(예: 그만둔 동안 {@code ADD_PHOTO}는
 * 진행 상황과 그동안 만든 새 수리 건에만).
 */
public enum ActionType {
  // 호실·참여
  CREATE_ROOM(Area.ROOM),
  EDIT_ROOM_INFO(Area.ROOM),
  ADD_PART(Area.ROOM),
  HIDE_PART(Area.ROOM),
  TOGGLE_PART(Area.ROOM),
  INVITE(Area.ROOM),
  CHANGE_INVITE_PHONE(Area.ROOM),
  REJECT_INVITE(Area.ROOM),
  CONNECT(Area.ROOM),
  JOIN(Area.ROOM),
  SHARE_CONSENT(Area.ROOM, Allow.AFTER_SETTLED, Allow.WHILE_SHARING_STOPPED),
  STOP_SHARING(Area.ROOM),
  RESUME_SHARING(Area.ROOM, Allow.AFTER_SETTLED, Allow.WHILE_SHARING_STOPPED),
  MOVE_OUT(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  REQUEST_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  CONFIRM_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  REJECT_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  CONFIRM_LIVING(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  // 링크·통지
  LINK_VIEW(Area.LINK_NOTICE),
  OPT_OUT(Area.LINK_NOTICE),
  RESEND_NOTICE(Area.LINK_NOTICE),
  RENOTIFY(Area.LINK_NOTICE),
  MARK_UNCONFIRMED(Area.LINK_NOTICE),
  MARK_UNDELIVERED(Area.LINK_NOTICE),
  // 수리
  REGISTER(Area.REPAIR, Allow.WHILE_SHARING_STOPPED),
  SUPPLEMENT(Area.REPAIR),
  RESPOND_DEFECT(Area.REPAIR),
  START_REPAIR(Area.REPAIR),
  REQUEST_CLOSE(Area.REPAIR),
  CONFIRM_CLOSE(Area.REPAIR),
  NOT_YET(Area.REPAIR),
  CLOSE_ALONE(Area.REPAIR),
  REOPEN(Area.REPAIR),
  ADD_PHOTO(Area.REPAIR, Allow.WHILE_SHARING_STOPPED),
  ASK_PROGRESS(Area.REPAIR),
  NOTE_PROGRESS(Area.REPAIR, Allow.WHILE_SHARING_STOPPED),
  RESPOND_SITUATION(Area.REPAIR),
  NOTE_CAUSE(Area.REPAIR),
  RESPOND_CAUSE(Area.REPAIR),
  // 고치기
  CORRECT(Area.CORRECTION),
  RESPOND_CORRECTION(Area.CORRECTION),
  ACCEPT_ALL_CORRECTIONS(Area.CORRECTION),
  RESUBMIT_CORRECTION(Area.CORRECTION),
  WITHDRAW_CORRECTION(Area.CORRECTION),
  // 돈·제안
  RECORD_PAYMENT(Area.MONEY),
  RESPOND_AMOUNT(Area.MONEY),
  SUPPLEMENT_PAYMENT(Area.MONEY),
  PROPOSE(Area.MONEY),
  AGREE(Area.MONEY),
  DISPUTE_PROPOSAL(Area.MONEY),
  COUNTER(Area.MONEY),
  WITHDRAW(Area.MONEY),
  // 점검
  START_INSPECTION(Area.INSPECTION),
  ADD_ITEM(Area.INSPECTION),
  ADD_PART_PHOTO(Area.INSPECTION),
  REQUEST_INSPECTION_CONFIRM(Area.INSPECTION),
  RESPOND_ITEM(Area.INSPECTION),
  SUPPLEMENT_ITEM(Area.INSPECTION),
  CONFIRM_ALL(Area.INSPECTION),
  LOCK(Area.INSPECTION),
  FINISH_INSPECTION(Area.INSPECTION),
  // 정산
  OPEN_SETTLEMENT(Area.SETTLEMENT),
  INCLUDE_CANDIDATE(Area.SETTLEMENT),
  ADD_SETTLEMENT_ITEM(Area.SETTLEMENT),
  PROPOSE_EXCLUDE(Area.SETTLEMENT),
  RECORD_TRANSFER(Area.SETTLEMENT),
  RESPOND_TRANSFER(Area.SETTLEMENT),
  PROPOSE_FINISH(Area.SETTLEMENT),
  CONFIRM_FINISH(Area.SETTLEMENT),
  NOT_YET_FINISH(Area.SETTLEMENT),
  FINISH_ALONE(Area.SETTLEMENT),
  CLOSE_FINISH_REQUEST(Area.SETTLEMENT),
  AUTOCLOSE(Area.SETTLEMENT),
  // 사진
  HIDE_PHOTO(Area.PHOTO),
  SHOW_PHOTO(Area.PHOTO),
  MARK_PHOTO_WRONG(Area.PHOTO),
  UNMARK_PHOTO_WRONG(Area.PHOTO),
  ERASE_PHOTO(Area.PHOTO, Allow.AFTER_SETTLED, Allow.ON_JOINED, Allow.WHILE_SHARING_STOPPED);

  /** 05 2-3 표의 "영역" 칸. 각 Transitions가 자기 영역이 아닌 행동을 거절할 때 쓴다. */
  public enum Area {
    ROOM,
    LINK_NOTICE,
    REPAIR,
    CORRECTION,
    MONEY,
    INSPECTION,
    SETTLEMENT,
    PHOTO
  }

  /** 호실이 막힌 상태에서도 되는 행동. */
  public enum Allow {
    AFTER_SETTLED,
    ON_JOINED,
    WHILE_SHARING_STOPPED
  }

  private final Area area;
  private final Set<Allow> allows;

  ActionType(Area area, Allow... allows) {
    this.area = area;
    this.allows = EnumSet.noneOf(Allow.class);
    this.allows.addAll(Arrays.asList(allows));
  }

  public Area area() {
    return area;
  }

  public boolean allows(Allow allow) {
    return allows.contains(allow);
  }

  /** 거주가 끝난 호실에서 할 수 없는 행동: 새 수리 건 등록(03 불변식 9, 06 REP-1c {@code ROOM_ENDED}). */
  public boolean blockedAfterEnded() {
    return this == REGISTER;
  }
}
