package com.chilsuitjo.back.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <토큰>}을 읽어 맞으면 회원 ID로 로그인 상태를 만든다. 틀린 토큰은 로그인하지 않은 것과 같게 두어, 보호된
 * API에서 401이 된다. 검증기가 없으면(프로필 없음) 아무도 로그인되지 않는다.
 *
 * <p>빈으로 두지 않는다. 빈이면 Spring Boot가 Security 밖의 서블릿 필터로도 한 번 더 등록한다.
 */
class BearerTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final AccessTokenVerifier verifier;

  /** {@code verifier}가 null이면 모든 토큰을 거절한다. */
  BearerTokenFilter(AccessTokenVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (verifier != null && header != null && header.startsWith(PREFIX)) {
      verifier
          .verify(header.substring(PREFIX.length()).strip())
          .ifPresent(BearerTokenFilter::logIn);
    }
    chain.doFilter(request, response);
  }

  private static void logIn(UUID memberId) {
    var authentication = new UsernamePasswordAuthenticationToken(memberId, null, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
