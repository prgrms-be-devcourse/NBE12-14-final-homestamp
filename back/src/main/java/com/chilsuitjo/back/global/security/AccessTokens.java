package com.chilsuitjo.back.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 액세스 JWT를 만들고 확인한다(04 4-1, 06 ACC-2a). HS256으로 서명하고 서버에 저장하지 않는다. 담는 것은 회원 ID({@code sub}), 리프레시
 * 토큰 묶음 ID({@code fid}), 발급·만료 시각뿐이다. 묶음 ID는 로그아웃할 때 그 기기의 리프레시 토큰 묶음을 찾는 데 쓴다(리프레시 쿠키는 {@code
 * Path=/api/auth/refresh}라 로그아웃 요청에 붙지 않는다).
 *
 * <p>만료는 주입한 {@link Clock}으로 보고 여유 시간을 두지 않는다. HS256 말고 다른 알고리즘(예: {@code none})의 토큰은 받지 않는다.
 */
public class AccessTokens implements AccessTokenVerifier {

  static final String FAMILY_CLAIM = "fid";

  /** HS256 키는 256비트 이상이어야 한다(RFC 7518 3.2). */
  private static final int MIN_KEY_BYTES = 32;

  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final Clock clock;
  private final Duration ttl;

  AccessTokens(String key, Clock clock, Duration ttl) {
    SecretKey secret = secretKey(key);
    this.encoder = encoder(secret);
    this.clock = Objects.requireNonNull(clock);
    this.ttl = Objects.requireNonNull(ttl);

    JwtTimestampValidator expiry = new JwtTimestampValidator(Duration.ZERO);
    expiry.setClock(clock);
    expiry.setAllowEmptyExpiryClaim(false);
    NimbusJwtDecoder nimbus =
        NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
    nimbus.setJwtValidator(expiry);
    this.decoder = nimbus;
  }

  /** 서명 키. 비어 있거나 32바이트보다 짧으면 시작하지 않는다. */
  static SecretKey secretKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          ConditionalOnLoginTokenKeys.ACCESS_KEY + " 비밀 키가 없다. local·test가 아니면 설정으로 넣어야 시작한다");
    }
    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          ConditionalOnLoginTokenKeys.ACCESS_KEY + "는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  static JwtEncoder encoder(SecretKey secret) {
    return NimbusJwtEncoder.withSecretKey(secret).algorithm(MacAlgorithm.HS256).build();
  }

  /** 정해진 시각으로 토큰을 만든다. 테스트 지원({@code SecurityTestSupport})도 같은 모양을 쓰려고 이 메서드를 부른다. */
  static String encode(
      JwtEncoder encoder, UUID memberId, UUID familyId, Instant issuedAt, Instant expiresAt) {
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(memberId.toString())
            .claim(FAMILY_CLAIM, familyId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /** 지금부터 {@code auth.access.ttl} 동안 쓰는 액세스 토큰. */
  public String issue(UUID memberId, UUID familyId) {
    Instant now = clock.instant();
    return encode(
        encoder,
        Objects.requireNonNull(memberId),
        Objects.requireNonNull(familyId),
        now,
        now.plus(ttl));
  }

  /** 서명과 만료가 맞으면 회원 ID와 묶음 ID. 틀리거나 만료됐거나 모양이 다르면 빈 값. */
  public Optional<Claims> read(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Jwt jwt = decoder.decode(token);
      String subject = jwt.getSubject();
      String family = jwt.getClaimAsString(FAMILY_CLAIM);
      if (subject == null || family == null) {
        return Optional.empty();
      }
      return Optional.of(new Claims(UUID.fromString(subject), UUID.fromString(family)));
    } catch (JwtException | IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<UUID> verify(String token) {
    return read(token).map(Claims::memberId);
  }

  /** 액세스 토큰에 담긴 것. */
  public record Claims(UUID memberId, UUID familyId) {}
}
