package com.chilsuitjo.back.notification.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipientTest {

  private static final String PHONE = "01012345678";
  private final UUID member = UUID.randomUUID();

  @Test
  void 다섯_결과는_switch로_빠짐없이_나뉜다() {
    List<Recipient> all =
        List.of(
            new Recipient.VerifiedMember(member, PHONE),
            new Recipient.UnverifiedMember(member),
            new Recipient.Invitee(PHONE),
            new Recipient.InviteeWithoutPhone(),
            new Recipient.Unavailable());

    List<String> kinds =
        all.stream()
            .map(recipient ->
                switch (recipient) {
                  case Recipient.VerifiedMember v -> "verified";
                  case Recipient.UnverifiedMember u -> "unverified";
                  case Recipient.Invitee i -> "invitee";
                  case Recipient.InviteeWithoutPhone w -> "invitee-without-phone";
                  case Recipient.Unavailable n -> "unavailable";
                })
            .toList();

    assertThat(kinds).doesNotHaveDuplicates().hasSize(5);
  }

  @Test
  void 번호가_든_결과의_toString은_번호를_보여_주지_않는다() {
    assertThat(new Recipient.VerifiedMember(member, PHONE).toString())
        .doesNotContain(PHONE)
        .contains(member.toString());
    assertThat(new Recipient.Invitee(PHONE).toString()).doesNotContain(PHONE);
  }

  @Test
  void 번호가_있는_결과에_빈_번호를_넣으면_만들지_않는다() {
    assertThatThrownBy(() -> new Recipient.Invitee(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Recipient.Invitee(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Recipient.VerifiedMember(member, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Recipient.VerifiedMember(null, PHONE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Recipient.UnverifiedMember(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void 같은_값이면_같은_결과다() {
    assertThat(new Recipient.Invitee(PHONE)).isEqualTo(new Recipient.Invitee(PHONE));
    assertThat(new Recipient.InviteeWithoutPhone()).isEqualTo(new Recipient.InviteeWithoutPhone());
    assertThat(new Recipient.Unavailable()).isNotEqualTo(new Recipient.InviteeWithoutPhone());
  }
}
