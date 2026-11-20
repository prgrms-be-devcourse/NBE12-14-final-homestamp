package com.chilsuitjo.back.global.security;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 컨트롤러 테스트에서 로그인한 회원으로 요청한다: {@code mvc.perform(get(...).with(loginAs(memberId)))}. 가짜 검증기가 켜지는
 * {@code @ActiveProfiles("test")}와 함께 쓴다.
 *
 * <p>실제 JWT 검증기가 들어오면 이 안에서 테스트용 토큰을 만들도록 바꾸고, 쓰는 테스트는 그대로 둔다.
 */
public final class SecurityTestSupport {

  private SecurityTestSupport() {}

  public static RequestPostProcessor loginAs(UUID memberId) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + memberId);
      return request;
    };
  }
}
