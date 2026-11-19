package com.chilsuitjo.back.global.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 가짜 토큰 검증기(07 1주차 2일차). {@code Bearer <회원 UUID>}를 그대로 회원 ID로 읽는다. 누구로든 로그인할 수 있으므로 local·test
 * 프로필에서만 켜지고, {@code prod}가 함께 켜져 있으면 켜지지 않는다.
 *
 * <p>실제 JWT 검증기(ACC-2)가 들어오면 이 클래스를 지운다. 컨트롤러는 {@link LoginMember}만 쓰므로 바뀌지 않는다.
 */
@Component
@Profile(SecurityConfig.DEV_PROFILES)
public class FakeAccessTokenVerifier implements AccessTokenVerifier {

  @Override
  public Optional<UUID> verify(String token) {
    try {
      return Optional.of(UUID.fromString(token));
    } catch (IllegalArgumentException notUuid) {
      return Optional.empty();
    }
  }
}
