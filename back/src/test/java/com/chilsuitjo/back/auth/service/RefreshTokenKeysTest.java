package com.chilsuitjo.back.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 리프레시 토큰 만들기(04 4-1): 재발급 토큰은 받은 토큰과 키로 다시 계산되고, DB에는 해시만 둔다(06 ACC-2b). */
class RefreshTokenKeysTest {

  private static final String ACCESS = "unit-test-access-token-signing-key-32b";
  private static final String REFRESH = "unit-test-refresh-token-rotation-key-32b";

  private final RefreshTokenKeys keys = new RefreshTokenKeys(REFRESH, ACCESS);

  @Test
  void 같은_토큰의_다음_토큰은_언제나_같다() {
    String token = keys.newToken();

    assertThat(keys.next(token)).isEqualTo(keys.next(token)).isNotEqualTo(token);
    assertThat(new RefreshTokenKeys(REFRESH, ACCESS).next(token)).isEqualTo(keys.next(token));
  }

  @Test
  void 키가_다르면_다음_토큰도_다르다() {
    String token = keys.newToken();
    RefreshTokenKeys other = new RefreshTokenKeys("another-refresh-token-rotation-key-32b", ACCESS);

    assertThat(other.next(token)).isNotEqualTo(keys.next(token));
  }

  @Test
  void 새_토큰은_256비트_난수다() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      tokens.add(keys.newToken());
    }

    assertThat(tokens).hasSize(100).allSatisfy(t -> assertThat(t).hasSize(43));
    assertThat(keys.next(keys.newToken())).hasSize(43);
  }

  @Test
  void 해시는_원문과_다른_SHA_256_16진수다() {
    String token = keys.newToken();
    String hash = RefreshTokenKeys.hash(token);

    assertThat(hash).hasSize(64).matches("[0-9a-f]+").isNotEqualTo(token);
    assertThat(RefreshTokenKeys.sameHash(hash, RefreshTokenKeys.hash(token))).isTrue();
    assertThat(RefreshTokenKeys.sameHash(hash, RefreshTokenKeys.hash(keys.newToken()))).isFalse();
  }

  @Test
  void 묶음_잠금_키는_같은_묶음이면_같고_다른_묶음이면_다르다() {
    UUID family = UUID.randomUUID();

    assertThat(RefreshTokenKeys.familyLockKey(family))
        .isEqualTo(RefreshTokenKeys.familyLockKey(UUID.fromString(family.toString())))
        .isNotEqualTo(RefreshTokenKeys.familyLockKey(UUID.randomUUID()));
  }

  @Test
  void 키가_없거나_짧거나_액세스_키와_같으면_만들지_않는다() {
    assertThatThrownBy(() -> new RefreshTokenKeys("", ACCESS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.keys.refresh-token");
    assertThatThrownBy(() -> new RefreshTokenKeys("x".repeat(31), ACCESS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32바이트 이상");
    assertThatThrownBy(() -> new RefreshTokenKeys(ACCESS, ACCESS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("서로 달라야");
  }
}
