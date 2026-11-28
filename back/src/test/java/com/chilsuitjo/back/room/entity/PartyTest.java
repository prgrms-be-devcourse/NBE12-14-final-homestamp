package com.chilsuitjo.back.room.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 상대 자리 행의 초대·연결·거절(03 B, 05 {@code party}). 규칙은 {@code PartyTransitions}가 먼저 보고, 엔티티는 잘못 부르면 막기만
 * 한다.
 */
class PartyTest {

  private static final Instant AT = Instant.parse("2026-11-25T00:00:00Z");
  private static final String PHONE = "01012345678";

  @Test
  void 빈_자리를_초대하면_초대됨이고_초대_번호가_남는다() {
    Party party = emptySeat();

    party.invite(PHONE);

    assertThat(party.getStatus()).isEqualTo(PartyStatus.INVITED);
    assertThat(party.isInvitedPhone(PHONE)).isTrue();
    assertThat(party.isInvitedPhone("01099998888")).isFalse();
    assertThat(party.getMemberId()).isNull();
  }

  @Test
  void 연결하면_회원과_연결_시각이_남고_초대_번호는_지운다() {
    Party party = invited();
    UUID member = UUID.randomUUID();

    party.connect(member, AT);

    assertThat(party.getStatus()).isEqualTo(PartyStatus.CONNECTED);
    assertThat(party.getMemberId()).isEqualTo(member);
    assertThat(party.getConnectedAt()).isEqualTo(AT);
    assertThat(party.isInvitedPhone(PHONE)).isFalse();
  }

  @Test
  void 거절하면_거절됨이고_초대_번호는_남는다() {
    Party party = invited();

    party.reject();

    assertThat(party.getStatus()).isEqualTo(PartyStatus.REJECTED);
    assertThat(party.isInvitedPhone(PHONE)).isTrue();
  }

  @Test
  void 초대할_때마다_초대_회차가_새로_정해진다() {
    Party party = emptySeat();
    assertThat(party.currentInviteId()).isEmpty();
    party.invite(PHONE);
    UUID first = party.currentInviteId().orElseThrow();
    assertThat(first).isNotEqualTo(party.getId());
    assertThat(party.isCurrentInvite(first)).isTrue();
    assertThat(party.isCurrentInvite(null)).isFalse();

    party.reject();
    party.reinvite("01099998888");

    assertThat(party.getStatus()).isEqualTo(PartyStatus.INVITED);
    assertThat(party.isInvitedPhone("01099998888")).isTrue();
    assertThat(party.isCurrentInvite(first)).isFalse();
    assertThat(party.isCurrentInvite(party.currentInviteId().orElseThrow())).isTrue();
  }

  @Test
  void 초대됨_거절됨이_아니면_번호를_고쳐_다시_초대할_수_없다() {
    assertThatThrownBy(() -> emptySeat().reinvite(PHONE)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 번호가_없으면_어떤_번호와도_같지_않다() {
    assertThat(emptySeat().isInvitedPhone(null)).isFalse();
    assertThat(emptySeat().isInvitedPhone(PHONE)).isFalse();
  }

  @Test
  void 규칙을_건너뛰고_잘못된_상태에서_부르면_IllegalStateException이다() {
    Party creator = Party.creator(UUID.randomUUID(), PartySide.TENANT, UUID.randomUUID(), AT);

    assertThatThrownBy(() -> creator.invite(PHONE)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> emptySeat().connect(UUID.randomUUID(), AT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> emptySeat().reject()).isInstanceOf(IllegalStateException.class);
  }

  private static Party emptySeat() {
    return Party.empty(UUID.randomUUID(), PartySide.LANDLORD, AT);
  }

  private static Party invited() {
    Party party = emptySeat();
    party.invite(PHONE);
    return party;
  }
}
