package com.chilsuitjo.back.global.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 컨트롤러 테스트에서 로그인한 회원으로 요청한다: {@code mvc.perform(get(...).with(loginAs(memberId)))}. 로그인 토큰 키가 켜지는
 * {@code @ActiveProfiles("test")}와 함께 쓴다.
 *
 * <p>local·test 고정 키({@code application.yml}의 {@code app.keys.access-token})로 서명한 실제 액세스 JWT를 만든다.
 * 묶음 ID는 부를 때마다 새로 만들고, 만료는 2100년이라 테스트가 시계를 옮겨도 만료되지 않는다. {@code @WebMvcTest}에서 쓰려면 {@link
 * AccessTokenConfig}와 {@code ClockConfig}를 {@code @Import}한다.
 */
public final class SecurityTestSupport {

  /** {@code application.yml}의 local·test {@code app.keys.access-token}과 같아야 한다. */
  static final String TEST_ACCESS_KEY = "local-test-only-access-token-signing-key";

  private static final Instant NOT_EXPIRING = Instant.parse("2100-01-01T00:00:00Z");
  private static final JwtEncoder ENCODER =
      AccessTokens.encoder(AccessTokens.secretKey(TEST_ACCESS_KEY));

  private SecurityTestSupport() {}

  public static RequestPostProcessor loginAs(UUID memberId) {
    String token =
        AccessTokens.encode(ENCODER, memberId, UUID.randomUUID(), Instant.EPOCH, NOT_EXPIRING);
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return request;
    };
  }
}
