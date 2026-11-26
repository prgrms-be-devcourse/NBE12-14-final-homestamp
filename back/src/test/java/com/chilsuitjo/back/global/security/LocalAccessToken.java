package com.chilsuitjo.back.global.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 로컬 서버에서 로그인이 필요한 API를 부를 때 쓰는 액세스 토큰을 만든다: {@code ./gradlew localAccessToken --args=<회원
 * UUID>}(docs/setup.md 7장). 로그인 API가 생기면 그것을 쓴다.
 *
 * <p>test 소스에 두어 운영 jar에 들어가지 않는다. local·test 고정 키({@link SecurityTestSupport#TEST_ACCESS_KEY})로
 * 서명하므로, 그 키를 쓰지 않는 운영 서버는 이 토큰을 받지 않는다. 회원이 DB에 있는지는 보지 않는다.
 */
public final class LocalAccessToken {

  /** 로컬에서 하루 동안 다시 만들지 않고 쓸 수 있게. */
  static final Duration TTL = Duration.ofHours(24);

  private LocalAccessToken() {}

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("사용법: ./gradlew localAccessToken --args=<회원 UUID>");
      System.exit(2);
    }
    UUID memberId;
    try {
      memberId = UUID.fromString(args[0].strip());
    } catch (IllegalArgumentException e) {
      System.err.println("회원 UUID가 아니에요: " + args[0]);
      System.exit(2);
      return;
    }
    System.out.println(issue(memberId, Instant.now()));
  }

  /** {@code now}부터 {@link #TTL} 동안 쓰는 토큰. 묶음 ID는 새로 만든다(로그아웃해도 폐기할 리프레시 토큰이 없다). */
  static String issue(UUID memberId, Instant now) {
    return AccessTokens.encode(
        AccessTokens.encoder(AccessTokens.secretKey(SecurityTestSupport.TEST_ACCESS_KEY)),
        memberId,
        UUID.randomUUID(),
        now,
        now.plus(TTL));
  }
}
