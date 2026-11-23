package com.chilsuitjo.back.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** 액세스 JWT(04 4-1, 06 ACC-2a): 서명·만료·담는 값과 받지 않는 토큰. */
class AccessTokensTest {

  private static final String KEY = "unit-test-access-token-signing-key-32b";
  private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);

  private final UUID memberId = UUID.randomUUID();
  private final UUID familyId = UUID.randomUUID();

  private static AccessTokens at(Instant now) {
    return new AccessTokens(KEY, Clock.fixed(now, ZoneOffset.UTC), TTL);
  }

  @Test
  void 만든_토큰에서_회원_ID와_묶음_ID를_읽는다() {
    String token = at(NOW).issue(memberId, familyId);

    assertThat(at(NOW).read(token)).contains(new AccessTokens.Claims(memberId, familyId));
    assertThat(at(NOW).verify(token)).contains(memberId);
  }

  @Test
  void 수명은_auth_access_ttl이다() {
    String token = at(NOW).issue(memberId, familyId);

    assertThat(at(NOW.plus(TTL).minusSeconds(1)).verify(token)).contains(memberId);
    assertThat(at(NOW.plus(TTL).plusSeconds(1)).verify(token)).isEmpty();
  }

  @Test
  void 다른_키로_서명한_토큰은_받지_않는다() {
    String token =
        new AccessTokens(
                "another-access-token-signing-key-32b", Clock.fixed(NOW, ZoneOffset.UTC), TTL)
            .issue(memberId, familyId);

    assertThat(at(NOW).verify(token)).isEmpty();
  }

  @Test
  void 내용을_바꾼_토큰은_받지_않는다() {
    String[] parts = at(NOW).issue(memberId, familyId).split("\\.");
    String otherPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"sub\":\""
                        + UUID.randomUUID()
                        + "\",\"fid\":\""
                        + familyId
                        + "\",\"exp\":"
                        + NOW.plus(TTL).getEpochSecond()
                        + "}")
                    .getBytes(StandardCharsets.UTF_8));

    assertThat(at(NOW).verify(parts[0] + "." + otherPayload + "." + parts[2])).isEmpty();
  }

  @Test
  void 서명이_없는_alg_none_토큰은_받지_않는다() {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        b64.encodeToString(
            ("{\"sub\":\""
                    + memberId
                    + "\",\"fid\":\""
                    + familyId
                    + "\",\"exp\":"
                    + NOW.plus(TTL).getEpochSecond()
                    + "}")
                .getBytes(StandardCharsets.UTF_8));

    assertThat(at(NOW).verify(header + "." + payload + ".")).isEmpty();
  }

  @Test
  void 묶음_ID나_만료가_없는_토큰은_받지_않는다() {
    var encoder = AccessTokens.encoder(AccessTokens.secretKey(KEY));
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String noFamily =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    header,
                    JwtClaimsSet.builder()
                        .subject(memberId.toString())
                        .expiresAt(NOW.plus(TTL))
                        .build()))
            .getTokenValue();
    String noExpiry =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    header,
                    JwtClaimsSet.builder()
                        .subject(memberId.toString())
                        .claim(AccessTokens.FAMILY_CLAIM, familyId.toString())
                        .build()))
            .getTokenValue();

    assertThat(at(NOW).verify(noFamily)).isEmpty();
    assertThat(at(NOW).verify(noExpiry)).isEmpty();
  }

  @Test
  void JWT가_아니거나_비어_있으면_받지_않는다() {
    assertThat(at(NOW).verify(memberId.toString())).isEmpty();
    assertThat(at(NOW).verify("")).isEmpty();
    assertThat(at(NOW).verify(null)).isEmpty();
  }

  @Test
  void 키가_없거나_32바이트보다_짧으면_만들지_않는다() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    assertThatThrownBy(() -> new AccessTokens("", clock, TTL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.keys.access-token");
    assertThatThrownBy(() -> new AccessTokens("x".repeat(31), clock, TTL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32바이트 이상");
  }
}
