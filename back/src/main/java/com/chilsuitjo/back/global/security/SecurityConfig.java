package com.chilsuitjo.back.global.security;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 보안 뼈대. 세션 없이 {@code Authorization: Bearer} 토큰만 본다(06 4-0 인증, 04 4-1). 폼 로그인·HTTP Basic·CSRF는 쓰지
 * 않는다 (쿠키 대신 헤더라 CSRF와 무관하고, 쿠키를 쓰는 재발급은 Origin 확인으로 막는다, 04 4-1).
 *
 * <p>로그인 없이 여는 것은 06 4장에서 "누가"가 회원이 아닌 API와 아래 운영 확인 주소뿐이다. 나머지는 모두 로그인이 필요하고, 없거나 틀린 토큰은 {@code
 * LOGIN_REQUIRED}(401)로 다른 오류와 같은 모양(06 4-10)으로 돌려준다.
 */
@Configuration
public class SecurityConfig {

  /** 가짜 인증·API 문서를 여는 프로필. {@code prod}가 함께 켜지면 열지 않는다. */
  static final String DEV_PROFILES = "(local | test) & !prod";

  /** 06 4-1 "누구나"·"쿠키"·"인증 표". */
  private static final String[] PUBLIC_POSTS = {
    "/api/auth/phone-verifications",
    "/api/auth/phone-verifications/*/verify",
    "/api/auth/signup",
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/find-id",
    "/api/auth/reset-password",
  };

  /** 06 4-4 가입 전 링크. 링크 토큰은 요청 본문으로 받아 링크 도메인이 확인한다. */
  private static final String PUBLIC_LINKS = "/api/public/**";

  /** 배포 스크립트가 앱이 떴는지 확인한다(04 블루/그린 전환). */
  private static final String[] HEALTH = {"/actuator/health", "/actuator/health/**"};

  /** springdoc 주소(04 API 문서). springdoc 자체도 local·test에서만 켜진다. */
  private static final String[] API_DOCS = {
    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"
  };

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<AccessTokenVerifier> verifiers,
      Environment environment,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
    AccessTokenVerifier verifier = verifiers.getIfAvailable();
    if (verifier == null && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "prod에는 실제 토큰 검증기(AccessTokenVerifier)가 있어야 한다. 가짜 검증기는 local·test에서만 켜진다");
    }
    boolean devProfile = environment.acceptsProfiles(Profiles.of(DEV_PROFILES));

    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(new BearerTokenFilter(verifier), AnonymousAuthenticationFilter.class)
        .exceptionHandling(e -> e.authenticationEntryPoint(loginRequired(exceptionResolver)))
        .authorizeHttpRequests(auth -> {
          auth.requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll();
          auth.requestMatchers(PUBLIC_LINKS).permitAll();
          auth.requestMatchers(HttpMethod.GET, HEALTH).permitAll();
          if (devProfile) {
            auth.requestMatchers(HttpMethod.GET, API_DOCS).permitAll();
          }
          auth.anyRequest().authenticated();
        });
    return http.build();
  }

  /** 401 본문도 {@code GlobalExceptionHandler}가 만들게 넘겨, 다른 오류와 모양이 같다. */
  private static AuthenticationEntryPoint loginRequired(HandlerExceptionResolver resolver) {
    return (request, response, e) ->
        resolver.resolveException(
            request, response, null, new BusinessException(ErrorCode.LOGIN_REQUIRED));
  }
}
