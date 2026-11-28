package com.chilsuitjo.back.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 비밀 키 세 개(인증번호 해시·인증 표 서명·번호 HMAC)는 따로 두고, 없으면 시작하지 않는다. */
class VerificationKeysTest {

  private final VerificationKeys keys = new VerificationKeys("code-key", "ticket-key", "phone-key");

  @Test
  void 키가_없으면_시작하지_않는다() {
    new ApplicationContextRunner()
        .withBean(VerificationKeys.class)
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.keys.verify-code"));
  }

  @Test
  void 빈_키도_시작하지_않는다() {
    assertThatThrownBy(() -> new VerificationKeys("code-key", " ", "phone-key"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.keys.verify-ticket");
  }

  @Test
  void 키_셋이_같으면_시작하지_않는다() {
    assertThatThrownBy(() -> new VerificationKeys("same", "same", "phone-key"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new VerificationKeys("code-key", "same", "same"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new VerificationKeys("same", "ticket-key", "same"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 키가_있으면_시작한다() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "app.keys.verify-code=a", "app.keys.verify-ticket=b", "app.keys.phone-hmac=c")
        .withBean(VerificationKeys.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 인증_표는_서명한_행_ID를_돌려준다() {
    UUID id = UUID.randomUUID();

    assertThat(keys.readTicket(keys.signTicket(id))).contains(id);
  }

  @Test
  void 행_ID만으로나_다른_서명으로는_표가_아니다() {
    UUID id = UUID.randomUUID();
    String other = keys.signTicket(UUID.randomUUID());
    String forged = id + other.substring(other.indexOf('.'));
    VerificationKeys otherKeys = new VerificationKeys("code-key", "other-ticket", "phone-key");

    assertThat(keys.readTicket(id.toString())).isEmpty();
    assertThat(keys.readTicket(forged)).isEmpty();
    assertThat(keys.readTicket(otherKeys.signTicket(id))).isEmpty();
    assertThat(keys.readTicket("not-a-uuid.abc")).isEmpty();
    assertThat(keys.readTicket(id + ".!!!")).isEmpty();
    assertThat(keys.readTicket(null)).isEmpty();
  }

  @Test
  void 같은_인증번호라도_행마다_해시가_다르다() {
    String a = keys.codeHash(UUID.randomUUID(), "123456");
    String b = keys.codeHash(UUID.randomUUID(), "123456");

    assertThat(a).isNotEqualTo(b).hasSize(64);
    assertThat(VerificationKeys.sameHash(a, a)).isTrue();
    assertThat(VerificationKeys.sameHash(a, b)).isFalse();
  }

  @Test
  void 번호_HMAC은_번호마다_같은_소문자_hex_64자다() {
    assertThat(keys.phoneHmac("01012345678"))
        .isEqualTo(keys.phoneHmac("01012345678"))
        .isNotEqualTo(keys.phoneHmac("01012345679"))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void 번호_잠금_키는_번호마다_같고_번호가_다르면_다르다() {
    assertThat(keys.phoneLockKey("01012345678")).isEqualTo(keys.phoneLockKey("01012345678"));
    assertThat(keys.phoneLockKey("01012345678")).isNotEqualTo(keys.phoneLockKey("01012345679"));
  }

  @Test
  void 번호_HMAC으로_만든_잠금_키는_같은_번호의_잠금_키와_같다() {
    boolean sawNegative = false;
    for (int i = 0; i < 64; i++) {
      String phone = String.format("010%08d", i);
      long lockKey = keys.phoneLockKey(phone);
      assertThat(keys.phoneLockKeyOfHmac(keys.phoneHmac(phone))).isEqualTo(lockKey);
      sawNegative |= lockKey < 0;
    }
    // 앞 바이트가 0x80 이상인 번호(부호 있는 long에서 음수)도 같은 값인지 확인했어야 한다
    assertThat(sawNegative).isTrue();
  }

  @Test
  void 소문자_hex_64자가_아닌_번호_HMAC으로는_잠금_키를_만들지_않는다() {
    String hmac = keys.phoneHmac("01012345678");
    for (String bad :
        Arrays.asList(
            null, "", hmac.substring(1), hmac + "0", hmac.toUpperCase(), "z" + hmac.substring(1))) {
      assertThatThrownBy(() -> keys.phoneLockKeyOfHmac(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
