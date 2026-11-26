package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.dto.Recipient;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * test 가짜({@link InMemoryRecipientDirectory})가 포트 계약을 지키는지 본다. main 가짜는 room 실제 구현({@code
 * JpaRecipientDirectory})이 들어오며 지웠다(#163). 실제 구현의 계약은 {@code JpaRecipientDirectoryTest}가 본다.
 */
class RecipientDirectoryFakesTest {

  private static final String PHONE = "01012345678";

  /** 두 가짜를 같은 모양으로 다루려고 묶는다. */
  private record Subject(
      String name,
      RecipientDirectory directory,
      BiConsumer<UUID, Recipient> seedRecipient,
      Consumer<UUID> seedUnresolvable,
      BiConsumer<UUID, String> seedMember) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> fakes() {
    InMemoryRecipientDirectory test = new InMemoryRecipientDirectory();
    return Stream.of(
        Arguments.of(
            new Subject(
                "test", test, test::seedRecipient, test::seedUnresolvable, test::seedMember)));
  }

  @ParameterizedTest
  @MethodSource("fakes")
  void 정해_둔_다섯_결과를_그대로_돌려준다(Subject s) {
    UUID member = UUID.randomUUID();
    Recipient[] results = {
      new Recipient.VerifiedMember(member, PHONE),
      new Recipient.UnverifiedMember(member),
      new Recipient.Invitee(PHONE),
      new Recipient.InviteeWithoutPhone(),
      new Recipient.Unavailable()
    };
    for (Recipient result : results) {
      UUID party = UUID.randomUUID();
      s.seedRecipient().accept(party, result);

      assertThat(s.directory().resolve(party)).isEqualTo(result);
    }
  }

  @ParameterizedTest
  @MethodSource("fakes")
  void 정해_두지_않은_자리는_Unavailable이_아니라_예외다(Subject s) {
    UUID party = UUID.randomUUID();
    s.seedMember().accept(party, PHONE);

    assertThatThrownBy(() -> s.directory().resolve(party))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(PHONE);
  }

  @ParameterizedTest
  @MethodSource("fakes")
  void 판정할_수_없는_자리는_예외이고_다시_정하면_풀린다(Subject s) {
    UUID party = UUID.randomUUID();
    s.seedRecipient().accept(party, new Recipient.Invitee(PHONE));
    s.seedUnresolvable().accept(party);

    assertThatThrownBy(() -> s.directory().resolve(party))
        .isInstanceOf(IllegalStateException.class);

    s.seedRecipient().accept(party, new Recipient.InviteeWithoutPhone());
    assertThat(s.directory().resolve(party)).isEqualTo(new Recipient.InviteeWithoutPhone());
  }

  @ParameterizedTest
  @MethodSource("fakes")
  void 회원_번호는_정해_둔_것만_준다(Subject s) {
    UUID member = UUID.randomUUID();
    s.seedMember().accept(member, "01099998888");

    assertThat(s.directory().phoneOfMember(member)).contains("01099998888");
    assertThat(s.directory().phoneOfMember(UUID.randomUUID())).isEmpty();
  }

  @Test
  void test_가짜는_reset으로_정해_둔_것이_모두_사라진다() {
    InMemoryRecipientDirectory test = new InMemoryRecipientDirectory();
    UUID party = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    test.seedMember(member, PHONE);
    test.seedRecipient(party, new Recipient.Invitee(PHONE));
    test.seedUnresolvable(broken);

    test.reset();

    assertThat(test.phoneOfMember(member)).isEmpty();
    assertThatThrownBy(() -> test.resolve(party)).hasMessageContaining("정해 두지 않은");
    assertThatThrownBy(() -> test.resolve(broken)).hasMessageContaining("정해 두지 않은");
  }

  @Test
  void test_가짜는_빈이_아니다() {
    assertThat(InMemoryRecipientDirectory.class.getAnnotations())
        .noneMatch(a ->
            a.annotationType().getName().startsWith("org.springframework.stereotype")
                || a.annotationType()
                    .getName()
                    .equals("org.springframework.context.annotation.Configuration"));
  }
}
