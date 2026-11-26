package com.chilsuitjo.back.link.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 링크 토큰 만들기와 저장용 해시(03 C "토큰 128비트 이상, 해시 저장", 6-1 "링크 토큰"). 원래 토큰은 문자에만 들어가고 DB에는 해시만 남는다. 토큰은 로그에
 * 남기지 않는다(06 LINK-2b).
 *
 * <p>토큰이 256비트(초대 링크 토큰은 HMAC 출력, 이 클래스의 난수도 256비트)라 해시에 비밀 키를 섞지 않는다(짧은 값이 아니라 되돌려 찾을 수 없다). 정산 초대
 * 링크(LINK-3)의 토큰 만드는 법은 그 발급을 만들 때 정한다.
 */
public final class LinkToken {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private LinkToken() {}

  /**
   * URL에 그대로 넣을 수 있는 새 난수 토큰(Base64 URL, 패딩 없음, 43자). 발급에는 쓰지 않는다: 난수 토큰은 저장하지 않으면 다시 만들 수 없어 문자에
   * 넣을 수 없다. 발급은 링크 ID로 다시 만드는 HMAC 토큰을 쓴다({@code LinkTokenKeys}, #163). 열람 테스트가 행을 직접 넣을 때만 쓴다.
   */
  public static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** {@code link.token_hash}에 저장하고 찾을 때 쓰는 값(SHA-256 16진수 64자). */
  public static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 쓸 수 없어요", e);
    }
  }
}
