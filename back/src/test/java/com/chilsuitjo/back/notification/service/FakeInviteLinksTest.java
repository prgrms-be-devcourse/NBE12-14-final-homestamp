package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 가짜가 실제 구현과 같은 약속(다시 쓰기, 처음 이유 유지)을 지키는지. */
class FakeInviteLinksTest {

  private final FakeInviteLinks links = new FakeInviteLinks();
  private final UUID room = UUID.randomUUID();
  private final UUID party = UUID.randomUUID();
  private final UUID issuer = UUID.randomUUID();

  @Test
  void 같은_자리_같은_기록이면_다시_쓰고_기록이_다르면_새로_만든다() {
    RequestTarget record = RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID());

    UUID first = links.issueInvite(room, party, record, issuer);

    assertThat(links.issueInvite(room, party, record, issuer)).isEqualTo(first);
    assertThat(links.issueInvite(room, party, null, issuer)).isNotEqualTo(first);
    assertThat(links.issued()).hasSize(2);
  }

  @Test
  void 무효로_하면_새로_만들고_처음_이유는_바뀌지_않는다() {
    UUID first = links.issueInvite(room, party, null, issuer);

    assertThat(links.invalidateInvites(room, party, LinkInvalidReason.REINVITED))
        .containsExactly(first);
    UUID second = links.issueInvite(room, party, null, issuer);
    assertThat(links.invalidateInvites(room, LinkInvalidReason.RESIDENCE_ENDED)).isEqualTo(1);

    assertThat(second).isNotEqualTo(first);
    assertThat(links.issued())
        .extracting(FakeInviteLinks.Issued::invalidReason)
        .containsExactly(LinkInvalidReason.REINVITED, LinkInvalidReason.RESIDENCE_ENDED);
  }

  @Test
  void 자리_단위_무효는_이미_무효였던_것까지_그_자리의_링크_ID를_전부_돌려준다() {
    UUID otherParty = UUID.randomUUID();
    UUID otherRoom = UUID.randomUUID();
    UUID closedBefore = links.issueInvite(room, party, null, issuer);
    links.invalidateInvites(room, party, LinkInvalidReason.CONNECTED);
    UUID fresh = links.issueInvite(room, party, null, issuer);
    UUID otherSeat = links.issueInvite(room, otherParty, null, issuer);
    UUID otherRoomLink = links.issueInvite(otherRoom, party, null, issuer);

    var returned = links.invalidateInvites(room, party, LinkInvalidReason.REINVITED);

    assertThat(returned).doesNotHaveDuplicates().containsExactlyInAnyOrder(closedBefore, fresh);
    assertThat(links.issued())
        .extracting(FakeInviteLinks.Issued::id, FakeInviteLinks.Issued::invalidReason)
        .containsExactly(
            tuple(closedBefore, LinkInvalidReason.CONNECTED),
            tuple(fresh, LinkInvalidReason.REINVITED),
            tuple(otherSeat, null),
            tuple(otherRoomLink, null));
    assertThat(links.invalidateInvites(room, party, LinkInvalidReason.REINVITED))
        .containsExactlyInAnyOrderElementsOf(returned);
    assertThat(links.invalidateInvites(room, otherParty, LinkInvalidReason.REINVITED))
        .containsExactly(otherSeat);
    assertThat(links.invalidateInvites(otherRoom, otherParty, LinkInvalidReason.REINVITED))
        .isEmpty();
  }
}
