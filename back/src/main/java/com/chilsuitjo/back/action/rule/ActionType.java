package com.chilsuitjo.back.action.rule;

import java.util.EnumSet;
import java.util.Set;

/**
 * 행동 종류 전체. 기준은 05 2-3 {@code type} 목록이고, DB {@code ck_action_type}(V1)과 같다. 새 행동은 05 표와 새 V 파일을 같은
 * PR에서 고치고 여기에 한 줄 더한다(B가 성질을 정함).
 *
 * <p>DB의 {@code action.type}에는 {@code name()}이 글자로 저장된다. 각 도메인의 {@code Transitions}는 자기 영역({@link
 * #area()})의 행동만 받는다.
 *
 * <p>성질은 pipeline이 호실 단위로 막을 때 쓴다(09 3-1 순서 3, decisions 2026-11-16·17).
 *
 * <ul>
 *   <li>{@link Kind#RESPONSE}: 상대 기록에 대한 응답·확인. 공유를 그만둔 사람은 할 수 없다
 *   <li>{@link Allow#AFTER_SETTLED}: 정산 완료 뒤에도 된다(03 A "정산 완료"는 읽기·내려받기만)
 *   <li>{@link Allow#ON_JOINED}: 합류 전 기록에서도 된다(03 B "합류 전 기록"은 새 행동 없음)
 * </ul>
 *
 * <p>사진 행동은 {@code ERASE_PHOTO}(개인정보 지우기)만 두 {@code Allow}를 갖는다(decisions 2026-11-17). 공유 중단 중 되는
 * 행동은 PR #59가 머지되면 이 표시를 다시 맞춘다.
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
  SHARE_CONSENT(Area.ROOM),
  STOP_SHARING(Area.ROOM),
  RESUME_SHARING(Area.ROOM),
  MOVE_OUT(Area.ROOM),
  REQUEST_END(Area.ROOM),
  CONFIRM_END(Area.ROOM, Kind.RESPONSE),
  REJECT_END(Area.ROOM, Kind.RESPONSE),
  CONFIRM_LIVING(Area.ROOM),
  // 링크·통지
  LINK_VIEW(Area.LINK_NOTICE),
  OPT_OUT(Area.LINK_NOTICE),
  RESEND_NOTICE(Area.LINK_NOTICE),
  RENOTIFY(Area.LINK_NOTICE),
  MARK_UNCONFIRMED(Area.LINK_NOTICE),
  MARK_UNDELIVERED(Area.LINK_NOTICE),
  // 수리
  REGISTER(Area.REPAIR),
  SUPPLEMENT(Area.REPAIR),
  RESPOND_DEFECT(Area.REPAIR, Kind.RESPONSE),
  START_REPAIR(Area.REPAIR),
  REQUEST_CLOSE(Area.REPAIR),
  CONFIRM_CLOSE(Area.REPAIR, Kind.RESPONSE),
  NOT_YET(Area.REPAIR, Kind.RESPONSE),
  CLOSE_ALONE(Area.REPAIR),
  REOPEN(Area.REPAIR),
  ADD_PHOTO(Area.REPAIR),
  ASK_PROGRESS(Area.REPAIR),
  NOTE_PROGRESS(Area.REPAIR),
  RESPOND_SITUATION(Area.REPAIR, Kind.RESPONSE),
  NOTE_CAUSE(Area.REPAIR),
  RESPOND_CAUSE(Area.REPAIR, Kind.RESPONSE),
  // 고치기
  CORRECT(Area.CORRECTION),
  RESPOND_CORRECTION(Area.CORRECTION, Kind.RESPONSE),
  ACCEPT_ALL_CORRECTIONS(Area.CORRECTION, Kind.RESPONSE),
  RESUBMIT_CORRECTION(Area.CORRECTION),
  WITHDRAW_CORRECTION(Area.CORRECTION),
  // 돈·제안
  RECORD_PAYMENT(Area.MONEY),
  RESPOND_AMOUNT(Area.MONEY, Kind.RESPONSE),
  SUPPLEMENT_PAYMENT(Area.MONEY),
  PROPOSE(Area.MONEY),
  AGREE(Area.MONEY, Kind.RESPONSE),
  DISPUTE_PROPOSAL(Area.MONEY, Kind.RESPONSE),
  COUNTER(Area.MONEY, Kind.RESPONSE),
  WITHDRAW(Area.MONEY),
  // 점검
  START_INSPECTION(Area.INSPECTION),
  ADD_ITEM(Area.INSPECTION),
  ADD_PART_PHOTO(Area.INSPECTION),
  REQUEST_INSPECTION_CONFIRM(Area.INSPECTION),
  RESPOND_ITEM(Area.INSPECTION, Kind.RESPONSE),
  SUPPLEMENT_ITEM(Area.INSPECTION),
  CONFIRM_ALL(Area.INSPECTION, Kind.RESPONSE),
  LOCK(Area.INSPECTION),
  FINISH_INSPECTION(Area.INSPECTION),
  // 정산
  OPEN_SETTLEMENT(Area.SETTLEMENT),
  INCLUDE_CANDIDATE(Area.SETTLEMENT),
  ADD_SETTLEMENT_ITEM(Area.SETTLEMENT),
  PROPOSE_EXCLUDE(Area.SETTLEMENT),
  RECORD_TRANSFER(Area.SETTLEMENT),
  RESPOND_TRANSFER(Area.SETTLEMENT, Kind.RESPONSE),
  PROPOSE_FINISH(Area.SETTLEMENT),
  CONFIRM_FINISH(Area.SETTLEMENT, Kind.RESPONSE),
  NOT_YET_FINISH(Area.SETTLEMENT, Kind.RESPONSE),
  FINISH_ALONE(Area.SETTLEMENT),
  CLOSE_FINISH_REQUEST(Area.SETTLEMENT),
  AUTOCLOSE(Area.SETTLEMENT),
  // 사진
  HIDE_PHOTO(Area.PHOTO),
  SHOW_PHOTO(Area.PHOTO),
  MARK_PHOTO_WRONG(Area.PHOTO),
  UNMARK_PHOTO_WRONG(Area.PHOTO),
  ERASE_PHOTO(Area.PHOTO, Kind.RECORD, Allow.AFTER_SETTLED, Allow.ON_JOINED);

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

  /** 상대 기록에 대한 응답·확인인지, 내 기록을 남기는지. */
  public enum Kind {
    RESPONSE,
    RECORD
  }

  /** 호실이 막힌 상태에서도 되는 행동. */
  public enum Allow {
    AFTER_SETTLED,
    ON_JOINED
  }

  private final Area area;
  private final Kind kind;
  private final Set<Allow> allows;

  ActionType(Area area) {
    this(area, Kind.RECORD);
  }

  ActionType(Area area, Kind kind, Allow... allows) {
    this.area = area;
    this.kind = kind;
    this.allows = allows.length == 0 ? EnumSet.noneOf(Allow.class) : EnumSet.of(allows[0], allows);
  }

  public Area area() {
    return area;
  }

  public Kind kind() {
    return kind;
  }

  public boolean allows(Allow allow) {
    return allows.contains(allow);
  }

  /** 거주가 끝난 호실에서 할 수 없는 행동: 새 수리 건 등록(03 불변식 9, 06 REP-1c {@code ROOM_ENDED}). */
  public boolean blockedAfterEnded() {
    return this == REGISTER;
  }

  /** 공유를 그만둔 사람이 할 수 없는 행동: 응답·확인과 초대(03 B "공유 그만두기"). */
  public boolean blockedWhileSharingStopped() {
    return kind == Kind.RESPONSE || this == INVITE;
  }
}
