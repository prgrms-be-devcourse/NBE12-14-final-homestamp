package com.chilsuitjo.back.notification.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.rule.PartyPresence;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 공유 중단 제한은 상대 탈퇴가 앞선다(03 B, 06 CONS-3p·3q). 두 방향을 모두 본다. */
class RoomPartiesTest {

  private static final Instant STOPPED = Instant.parse("2026-11-19T00:00:00Z");

  @Test
  void 내가_그만뒀고_상대가_있으면_내_제한이_살아_있다() {
    RoomParties parties = new RoomParties(stopped(PartyPresence.CONNECTED), connected());

    assertThat(parties.mySharingStoppedApplies()).isTrue();
  }

  @Test
  void 내가_그만뒀어도_상대가_탈퇴하면_내_제한이_풀린다() {
    RoomParties parties = new RoomParties(stopped(PartyPresence.CONNECTED), withdrawn(null));

    assertThat(parties.mySharingStoppedApplies()).isFalse();
  }

  @Test
  void 상대가_그만뒀으면_상대_제한이_살아_있다() {
    RoomParties parties = new RoomParties(connected(), stopped(PartyPresence.CONNECTED));

    assertThat(parties.counterpartSharingStoppedApplies()).isTrue();
  }

  @Test
  void 상대가_그만둔_뒤_탈퇴하면_남은_공유_중단_시각은_보지_않는다() {
    RoomParties parties = new RoomParties(connected(), withdrawn(STOPPED));

    assertThat(parties.counterpartSharingStoppedApplies()).isFalse();
  }

  @Test
  void 아무도_그만두지_않았으면_제한이_없다() {
    RoomParties parties = new RoomParties(connected(), PartyFacts.empty());

    assertThat(parties.mySharingStoppedApplies()).isFalse();
    assertThat(parties.counterpartSharingStoppedApplies()).isFalse();
  }

  private static PartyFacts connected() {
    return new PartyFacts(
        UUID.randomUUID(), UUID.randomUUID(), PartyPresence.CONNECTED, null, true, false, "정*윤");
  }

  private static PartyFacts stopped(PartyPresence presence) {
    return new PartyFacts(
        UUID.randomUUID(), UUID.randomUUID(), presence, STOPPED, true, false, "정*윤");
  }

  private static PartyFacts withdrawn(Instant sharingStoppedAt) {
    return new PartyFacts(
        UUID.randomUUID(), null, PartyPresence.WITHDRAWN, sharingStoppedAt, false, false, null);
  }
}
