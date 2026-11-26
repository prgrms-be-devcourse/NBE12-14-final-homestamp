package com.chilsuitjo.back.action.rule;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 행동 종류 전체. 기준은 05 2-3 {@code type} 목록이고, DB {@code ck_action_type}(가장 최근에 다시 만든 V 파일, 지금 V5)과 같다.
 * 새 행동은 05 표와 새 V 파일을 같은 PR에서 고치고 여기에 한 줄 더한다(B가 성질을 정함).
 *
 * <p>DB의 {@code action.type}에는 {@code name()}이 글자로 저장된다. 각 도메인의 {@code Transitions}는 자기 영역({@link
 * #area()})의 행동만 받는다.
 *
 * <p>성질은 pipeline이 호실 단위로 막을 때 쓴다(09 3-1 {@code ActionType}·순서 3, decisions 2026-11-18). 막힌 상태에서도
 * 되는 행동에만 {@link Allow} 표시를 단다. 표시가 없으면 막는다(허용 목록).
 *
 * <ul>
 *   <li>{@link Allow#AFTER_SETTLED}: 정산 완료 뒤에도 된다. 개인정보 사진 지우기와 다시 공유(보기만)(06 CONS-3i), 문자 그만 받기
 *   <li>{@link Allow#ON_JOINED}: 합류 전 기록에서도 된다. 개인정보 사진 지우기(decisions 2026-11-17)와 문자 그만 받기
 *   <li>{@link Allow#WHILE_SHARING_STOPPED}: 공유를 그만둔 사람도 된다. 나만 보는 기록과 거주 종료·다시 공유(03 B)
 *   <li>{@link Allow#BY_INVITEE}: 아직 연결하지 않은 초대받은 회원이 초대 자리로 할 수 있다. 연결·공유 동의(ROOM-5, CONS-2)와 초대
 *       거절(#249). 다른 표시와 달리 "막힌 호실에서도 된다"가 아니라 "당사자가 아닌 초대받은 회원에게 열린다"는 뜻이라, 호실의 다른 막힘은 그대로 받는다(정산
 *       완료 호실의 연결은 {@code SETTLED})
 * </ul>
 *
 * <p>사진을 어디에 붙이는지처럼 대상으로 갈리는 것은 표시로 정하지 않고 도메인 {@code Transitions}가 본다(예: 그만둔 동안 {@code ADD_PHOTO}는
 * 진행 상황과 그동안 만든 새 수리 건에만).
 *
 * <p>문자 그만 받기({@code OPT_OUT})는 번호 주인의 수신 거부라 호실 기록에 대한 행동이 아니다. 호실 상태로 거절하지 않도록 정산 완료·합류 전 기록·공유
 * 중단 표시를 모두 둔다(decisions 2026-11-19). 03 B "그만둔 사람이 할 수 있는 것"에는 없지만 이 이유로 예외다. 거주 종료로는 원래 막히지 않는다.
 */
public enum ActionType {
  // 호실·참여
  CREATE_ROOM(Area.ROOM),
  EDIT_ROOM_INFO(Area.ROOM),
  ADD_PART(Area.ROOM),
  HIDE_PART(Area.ROOM),
  /** 숨긴 부위를 다시 보이게 한다(#136). 설비·가전 켜고 끔({@link #TOGGLE_PART})과는 다른 일이다. */
  SHOW_PART(Area.ROOM),
  TOGGLE_PART(Area.ROOM),
  INVITE(Area.ROOM),
  CHANGE_INVITE_PHONE(Area.ROOM),
  REJECT_INVITE(Area.ROOM, Allow.BY_INVITEE),
  CONNECT(Area.ROOM, Allow.BY_INVITEE),
  JOIN(Area.ROOM),
  SHARE_CONSENT(Area.ROOM, Allow.AFTER_SETTLED, Allow.WHILE_SHARING_STOPPED, Allow.BY_INVITEE),
  STOP_SHARING(Area.ROOM),
  RESUME_SHARING(Area.ROOM, Allow.AFTER_SETTLED, Allow.WHILE_SHARING_STOPPED),
  MOVE_OUT(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  REQUEST_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  CONFIRM_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  REJECT_END(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  CONFIRM_LIVING(Area.ROOM, Allow.WHILE_SHARING_STOPPED),
  // 링크·통지
  LINK_VIEW(Area.LINK_NOTICE),
  OPT_OUT(Area.LINK_NOTICE, Allow.AFTER_SETTLED, Allow.ON_JOINED, Allow.WHILE_SHARING_STOPPED),
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

  /** 호실이 막힌 상태에서도 되는 행동, 그리고 초대받은 회원에게 열린 행동({@link #BY_INVITEE}). */
  public enum Allow {
    AFTER_SETTLED,
    ON_JOINED,
    WHILE_SHARING_STOPPED,
    BY_INVITEE
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

  /**
   * 거주가 끝난 호실에서 할 수 없는 행동: 새 수리 건 등록(03 불변식 9, 06 REP-1c), 수리 시작과 혼자 종료(03 A "상태별로 할 수 있는 일": 거주 종료
   * 뒤 수리 상태는 "거주 종료 시점"으로 멈춤, 혼자 종료는 ✕). 모두 {@code ROOM_ENDED}. 종료 요청은 종료 확인 요청을 열 수 없어 수리 규칙이 막는다.
   * 종료 확인·아직 안 됐어요는 요청이 닫혀도, 혼자 끝낸 건에도 정산 완료 전까지 받는다(늦은 답, decisions 2026-11-24·11-25).
   */
  public boolean blockedAfterEnded() {
    return this == REGISTER || this == START_REPAIR || this == CLOSE_ALONE;
  }
}
