package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 원문을 만들고 해시한다(04 4-1).
 *
 * <ul>
 *   <li>로그인 때 첫 토큰은 256비트 난수다.
 *   <li>재발급한 토큰은 받은 토큰과 비밀 키로 계산한다: {@code R' = HMAC-SHA256(키, "refresh-rotate:v1:" + R)}. 원문을 저장하지
 *       않아도 유예 시간 안에 같은 R이 다시 오면 같은 R'를 다시 만들어 돌려줄 수 있다(06 ACC-2b).
 *   <li>DB에는 SHA-256 해시만 둔다. DB만 새어도 키와 R 없이는 R'를 만들 수 없다.
 * </ul>
 *
 * <p>키({@code app.keys.refresh-token})는 액세스 JWT 서명 키와 달라야 하고 32바이트 이상이어야 한다. 아니면 앱이 시작하지 않는다. 키를
 * 바꾸면 그 순간 유예 중이던 재발급만 실패한다(다시 로그인).
 */
@Component
@ConditionalOnLoginTokenKeys
class RefreshTokenKeys {

  private static final String HMAC = "HmacSHA256";
  private static final String ROTATE_PREFIX = "refresh-rotate:v1:";
  private static final int MIN_KEY_BYTES = 32;
  private static final int TOKEN_BYTES = 32;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  RefreshTokenKeys(
      @Value("${" + ConditionalOnLoginTokenKeys.REFRESH_KEY + ":}") String refreshKey,
      @Value("${" + ConditionalOnLoginTokenKeys.ACCESS_KEY + ":}") String accessKey) {
    if (refreshKey == null || refreshKey.isBlank()) {
      throw new IllegalStateException(
          ConditionalOnLoginTokenKeys.REFRESH_KEY + " 비밀 키가 없다. local·test가 아니면 설정으로 넣어야 시작한다");
    }
    byte[] bytes = refreshKey.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          ConditionalOnLoginTokenKeys.REFRESH_KEY + "는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
    }
    if (refreshKey.equals(accessKey)) {
      throw new IllegalStateException(
          ConditionalOnLoginTokenKeys.REFRESH_KEY
              + "와 "
              + ConditionalOnLoginTokenKeys.ACCESS_KEY
              + "는 서로 달라야 한다");
    }
    this.key = new SecretKeySpec(bytes, HMAC);
  }

  /** 로그인 때 새 묶음의 첫 토큰. */
  String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** 이 토큰을 재발급하면 받는 다음 토큰. 같은 토큰과 같은 키면 언제나 같다. */
  String next(String token) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return ENCODER.encodeToString(
          mac.doFinal((ROTATE_PREFIX + token).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256을 쓸 수 없다", e);
    }
  }

  /** DB에 두는 해시(SHA-256, 16진수 64자). */
  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
    }
  }

  /** 두 해시가 같은지 시간 차이 없이 비교한다. */
  static boolean sameHash(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 묶음 잠금 키({@code pg_advisory_xact_lock(bigint)}). 번호 잠금({@code VerificationKeys.phoneLockKey})처럼
   * 앱이 64비트 키를 만들어 넘긴다. 묶음 ID는 무작위 UUID라 위·아래 64비트를 XOR해도 고르게 퍼진다. 다른 잠금 키와 우연히 겹치면 잠시 기다릴 뿐 결과는
   * 같다.
   */
  static long familyLockKey(UUID familyId) {
    return familyId.getMostSignificantBits() ^ familyId.getLeastSignificantBits();
  }
}
