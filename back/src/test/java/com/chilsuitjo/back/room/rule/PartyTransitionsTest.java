package com.chilsuitjo.back.room.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 03 B "당사자 연결" 상대 자리 상태 전이(06 ROOM-4a·5a·5b, #163). */
class PartyTransitionsTest {

  private final PartyTransitions rules = new PartyTransitions();

  // ---- 초대 ----

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"EMPTY", "NUMBER_ONLY"})
  void 만든_측이_거주_중에_비어_있음이나_번호만_자리를_초대하면_초대됨이다(PartyStatus status) {
    PartyState next = rules.apply(inviting(status), entry(ActionType.INVITE));

    assertThat(next.status()).isEqualTo(PartyStatus.INVITED);
  }

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"EMPTY", "NUMBER_ONLY"},
      mode = EnumSource.Mode.EXCLUDE)
  void 비어_있음이나_번호만이_아닌_자리는_초대할_수_없다(PartyStatus status) {
    expect(ErrorCode.INVALID_STATE, inviting(status), ActionType.INVITE);
  }

  @Test
  void 호실을_만든_측이_아니면_초대할_수_없다() {
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.EMPTY, false, true, false, false),
        ActionType.INVITE);
  }

  @Test
  void 거주가_끝난_호실에서는_초대할_수_없다() {
    // #82·#163 3-1: 거주 종료 뒤에는 정산 초대만 쓴다
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.EMPTY, true, false, false, false),
        ActionType.INVITE);
  }

  @Test
  void ROOM_4a_내_인증_번호로는_초대할_수_없다() {
    expect(
        ErrorCode.SELF_INVITE,
        new PartyState(PartyStatus.EMPTY, true, true, true, false),
        ActionType.INVITE);
  }

  @Test
  void 수신_거부_목록에_있는_번호로는_초대할_수_없다() {
    expect(
        ErrorCode.PHONE_OPTED_OUT,
        new PartyState(PartyStatus.NUMBER_ONLY, true, true, false, null, null, true),
        ActionType.INVITE);
  }

  @Test
  void 내_번호이면서_수신_거부여도_SELF_INVITE가_먼저다() {
    expect(
        ErrorCode.SELF_INVITE,
        new PartyState(PartyStatus.EMPTY, true, true, true, null, null, true),
        ActionType.INVITE);
  }

  @Test
  void 수신_거부가_아니면_초대됨이다() {
    PartyState next =
        rules.apply(
            new PartyState(PartyStatus.EMPTY, true, true, false, null, null, false),
            entry(ActionType.INVITE));
    assertThat(next.status()).isEqualTo(PartyStatus.INVITED);
    assertThat(next.optedOut()).isFalse();
  }

  @Test
  void 자리_상태가_틀리면_내_번호보다_먼저_INVALID_STATE다() {
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.INVITED, true, true, true, false),
        ActionType.INVITE);
  }

  // ---- 연결·거절 ----

  @Test
  void ROOM_5a_초대_번호와_같은_번호로_인증한_회원이_연결하면_연결됨이다() {
    PartyState next = rules.apply(invitee(PartyStatus.INVITED, true), entry(ActionType.CONNECT));

    assertThat(next.status()).isEqualTo(PartyStatus.CONNECTED);
  }

  @Test
  void ROOM_5b_번호가_다르면_연결되지_않는다() {
    expect(ErrorCode.PHONE_MISMATCH, invitee(PartyStatus.INVITED, false), ActionType.CONNECT);
  }

  @Test
  void 초대받은_사람이_제_집이_아니에요를_누르면_거절됨이다() {
    PartyState next =
        rules.apply(invitee(PartyStatus.INVITED, true), entry(ActionType.REJECT_INVITE));

    assertThat(next.status()).isEqualTo(PartyStatus.REJECTED);
  }

  @Test
  void 번호가_다르면_거절할_수_없다() {
    expect(ErrorCode.PHONE_MISMATCH, invitee(PartyStatus.INVITED, false), ActionType.REJECT_INVITE);
  }

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"INVITED"},
      mode = EnumSource.Mode.EXCLUDE)
  void 초대됨이_아닌_자리는_연결도_거절도_할_수_없다(PartyStatus status) {
    expect(ErrorCode.INVALID_STATE, invitee(status, true), ActionType.CONNECT);
    expect(ErrorCode.INVALID_STATE, invitee(status, true), ActionType.REJECT_INVITE);
  }

  @Test
  void 초대됨이_아니면_번호보다_먼저_INVALID_STATE다() {
    expect(ErrorCode.INVALID_STATE, invitee(PartyStatus.REJECTED, false), ActionType.CONNECT);
  }

  @Test
  void 자리_행동이_아니면_INVALID_STATE다() {
    expect(ErrorCode.INVALID_STATE, inviting(PartyStatus.EMPTY), ActionType.CREATE_ROOM);
  }

  private static PartyState inviting(PartyStatus status) {
    return new PartyState(status, true, true, false, false);
  }

  private static PartyState invitee(PartyStatus status, boolean phoneMatches) {
    return new PartyState(status, false, true, false, phoneMatches);
  }

  @Test
  void 거주가_끝난_호실이어도_초대받은_사람은_연결할_수_있다() {
    // 임시 정책(#163 3-1, 팀 최종 합의 전): 정산 완료 전까지 연결. 정산 완료는 pipeline이 SETTLED로 막는다
    PartyState endedRoom = new PartyState(PartyStatus.INVITED, false, false, false, true);
    assertThat(rules.apply(endedRoom, entry(ActionType.CONNECT)).status())
        .isEqualTo(PartyStatus.CONNECTED);
  }

  @Test
  void 이_호실의_다른_자리에_이미_있으면_거절할_수_없다() {
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.INVITED, null, null, null, true, true),
        ActionType.REJECT_INVITE);
  }

  @Test
  void 이_호실의_다른_자리에_이미_있으면_연결할_수_없다() {
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.INVITED, null, null, null, true, true),
        ActionType.CONNECT);
    assertThat(
            rules
                .apply(
                    new PartyState(PartyStatus.INVITED, null, null, null, true, false),
                    entry(ActionType.CONNECT))
                .status())
        .isEqualTo(PartyStatus.CONNECTED);
  }

  @Test
  void 행동_기록만으로_다시_계산하면_요청할_때만_아는_값은_검사하지_않는다() {
    // initial()은 요청할 때만 아는 값을 비워 둔다(RepairState·InspectionState와 같은 방식, #252 리뷰)
    PartyState invited = rules.apply(rules.initial(), entry(ActionType.INVITE));
    assertThat(invited.status()).isEqualTo(PartyStatus.INVITED);
    assertThat(rules.apply(invited, entry(ActionType.CONNECT)).status())
        .isEqualTo(PartyStatus.CONNECTED);
    assertThat(rules.apply(invited, entry(ActionType.REJECT_INVITE)).status())
        .isEqualTo(PartyStatus.REJECTED);
  }

  @Test
  void 다시_계산해도_자리_상태는_검사한다() {
    PartyState invited = rules.apply(rules.initial(), entry(ActionType.INVITE));
    expect(ErrorCode.INVALID_STATE, invited, ActionType.INVITE);
    expect(ErrorCode.INVALID_STATE, rules.initial(), ActionType.CONNECT);
  }

  // ---- 번호 고쳐 다시 초대(#437) ----

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"INVITED", "REJECTED"})
  void 만든_측이_거주_중에_초대됨이나_거절됨_자리의_번호를_고치면_초대됨이다(PartyStatus status) {
    PartyState next = rules.apply(inviting(status), entry(ActionType.CHANGE_INVITE_PHONE));

    assertThat(next.status()).isEqualTo(PartyStatus.INVITED);
  }

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"INVITED", "REJECTED"},
      mode = EnumSource.Mode.EXCLUDE)
  void 초대됨이나_거절됨이_아닌_자리는_번호를_고칠_수_없다(PartyStatus status) {
    expect(ErrorCode.INVALID_STATE, inviting(status), ActionType.CHANGE_INVITE_PHONE);
  }

  @Test
  void 번호_고치기도_만든_측_거주_중_내_번호_수신_거부를_초대와_같이_본다() {
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.INVITED, false, true, false, null, null, false),
        ActionType.CHANGE_INVITE_PHONE);
    expect(
        ErrorCode.INVALID_STATE,
        new PartyState(PartyStatus.INVITED, true, false, false, null, null, false),
        ActionType.CHANGE_INVITE_PHONE);
    expect(
        ErrorCode.SELF_INVITE,
        new PartyState(PartyStatus.REJECTED, true, true, true, null, null, true),
        ActionType.CHANGE_INVITE_PHONE);
    expect(
        ErrorCode.PHONE_OPTED_OUT,
        new PartyState(PartyStatus.REJECTED, true, true, false, null, null, true),
        ActionType.CHANGE_INVITE_PHONE);
  }

  @Test
  void 예전_초대_회차로_온_연결과_거절은_번호가_같아도_INVALID_STATE다() {
    // X → Y → X로 다시 초대했을 때 예전 초대 화면의 늦은 요청(#437, #370 ①)
    PartyState stale =
        new PartyState(PartyStatus.INVITED, null, null, null, true, false, null, false);
    expect(ErrorCode.INVALID_STATE, stale, ActionType.CONNECT);
    expect(ErrorCode.INVALID_STATE, stale, ActionType.REJECT_INVITE);
    PartyState staleOtherPhone =
        new PartyState(PartyStatus.INVITED, null, null, null, false, false, null, false);
    expect(ErrorCode.INVALID_STATE, staleOtherPhone, ActionType.CONNECT);
  }

  @Test
  void 다시_계산하면_번호_고치기도_초대됨이다() {
    PartyState invited = rules.apply(rules.initial(), entry(ActionType.INVITE));
    PartyState rejected = rules.apply(invited, entry(ActionType.REJECT_INVITE));
    assertThat(rules.apply(rejected, entry(ActionType.CHANGE_INVITE_PHONE)).status())
        .isEqualTo(PartyStatus.INVITED);
  }

  private void expect(ErrorCode code, PartyState now, ActionType type) {
    assertThatThrownBy(() -> rules.apply(now, entry(type)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  private static ActionEntry entry(ActionType type) {
    return new ActionEntry(
        null,
        null,
        0,
        type,
        PartySide.TENANT,
        "member",
        "k1",
        null,
        null,
        null,
        null,
        null,
        new HashMap<>(),
        Instant.parse("2026-11-25T00:00:00Z"));
  }
}
