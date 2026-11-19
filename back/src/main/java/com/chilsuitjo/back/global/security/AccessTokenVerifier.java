package com.chilsuitjo.back.global.security;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code Authorization: Bearer <토큰>}의 토큰을 회원 ID로 바꾼다. 실제 구현은 액세스 JWT 검증(04 4-1, ACC-2)이고, 그 전에는
 * local·test에서만 {@link FakeAccessTokenVerifier}를 쓴다.
 *
 * <p>빈이 없으면 보호된 API는 모두 401이고, {@code prod}는 빈이 없으면 시작하지 않는다({@link SecurityConfig}).
 */
public interface AccessTokenVerifier {

  /** 맞는 토큰이면 회원 ID, 틀리거나 만료됐으면 빈 값. */
  Optional<UUID> verify(String token);
}
