package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 해시: DelegatingPasswordEncoder + BCrypt, BCrypt의 UTF-8 72바이트 제한. */
class PasswordConfigTest {

  private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

  @Test
  void 새_해시는_bcrypt_표시와_BCrypt_해시로_만든다() {
    String hash = encoder.encode("abc12345");

    assertThat(hash).startsWith("{bcrypt}$2a$10$");
    assertThat(encoder.matches("abc12345", hash)).isTrue();
    assertThat(encoder.matches("abc12346", hash)).isFalse();
  }

  @Test
  void 같은_비밀번호도_해시는_매번_다르다() {
    assertThat(encoder.encode("abc12345")).isNotEqualTo(encoder.encode("abc12345"));
  }

  @Test
  void 표시가_없거나_모르는_해시는_맞지_않는다고_답하지_않고_오류다() {
    // 저장된 해시가 깨졌을 때 조용히 로그인 실패로 보이지 않게, 그대로 둔다(로그인을 만들 때 처리를 정한다)
    assertThatThrownBy(() -> encoder.matches("abc12345", "plain"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ASCII는_72자까지_해시할_수_있고_73자는_오류다() {
    String at72 = "a1".repeat(36);
    assertThat(at72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
    assertThat(encoder.matches(at72, encoder.encode(at72))).isTrue();

    assertThatThrownBy(() -> encoder.encode(at72 + "b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("72 bytes");
  }

  @Test
  void 제한은_글자_수가_아니라_UTF_8_바이트다_한글_한_글자는_3바이트다() {
    String hangul24 = "가".repeat(23) + "1"; // 23 × 3 + 1 = 70바이트
    String hangul25 = "가".repeat(24) + "1"; // 24 × 3 + 1 = 73바이트, 글자 수는 25
    assertThat(hangul24.getBytes(StandardCharsets.UTF_8)).hasSize(70);
    assertThat(hangul25.getBytes(StandardCharsets.UTF_8)).hasSize(73);

    assertThat(encoder.matches(hangul24, encoder.encode(hangul24))).isTrue();
    assertThatThrownBy(() -> encoder.encode(hangul25)).isInstanceOf(IllegalArgumentException.class);
  }
}
