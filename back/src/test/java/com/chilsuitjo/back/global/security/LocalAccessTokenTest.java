package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 로컬 개발용 토큰(docs/setup.md 7장): local·test 키로만 맞고, 하루 뒤에는 만료된다. */
class LocalAccessTokenTest {

  private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
  private static final Duration ACCESS_TTL = Duration.ofMinutes(15);

  private final UUID memberId = UUID.randomUUID();

  private static AccessTokens verifier(String key, Instant now) {
    return new AccessTokens(key, Clock.fixed(now, ZoneOffset.UTC), ACCESS_TTL);
  }

  @Test
  void local_test_키를_쓰는_서버는_그_회원으로_받는다() {
    String token = LocalAccessToken.issue(memberId, NOW);

    assertThat(verifier(SecurityTestSupport.TEST_ACCESS_KEY, NOW).verify(token)).contains(memberId);
  }

  @Test
  void 하루가_지나면_받지_않는다() {
    String token = LocalAccessToken.issue(memberId, NOW);
    Instant later = NOW.plus(LocalAccessToken.TTL);

    assertThat(verifier(SecurityTestSupport.TEST_ACCESS_KEY, later.minusSeconds(1)).verify(token))
        .contains(memberId);
    assertThat(verifier(SecurityTestSupport.TEST_ACCESS_KEY, later.plusSeconds(1)).verify(token))
        .isEmpty();
  }

  @Test
  void 다른_키를_쓰는_서버는_받지_않는다() {
    String token = LocalAccessToken.issue(memberId, NOW);

    assertThat(verifier("prod-like-access-token-signing-key-32b", NOW).verify(token)).isEmpty();
  }
}
