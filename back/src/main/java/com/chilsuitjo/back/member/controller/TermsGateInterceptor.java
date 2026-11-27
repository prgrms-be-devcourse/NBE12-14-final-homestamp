package com.chilsuitjo.back.member.controller;

import com.chilsuitjo.back.member.service.TermsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 바뀐 약관·확인 항목을 확인하기 전에는 보기만 하게 한다(06 CONS-4a·b, 03 6-2 "문구가 바뀌면", decisions 2026-11-27).
 *
 * <p>로그인한 회원의 GET이 아닌 요청을 모두 본다. 아래 {@link #EXEMPT}에 없는 주소는 막는다(새 API가 빠뜨려도 막히는 쪽으로). 회원 단위 조건이라 호실
 * 권한 검사(06 4-0 "권한")와 따로 둔다.
 */
@RequiredArgsConstructor
class TermsGateInterceptor implements HandlerInterceptor {

  /**
   * 확인 전에도 받는 상태 변경 주소. 로그인·재발급·로그아웃·가입·인증({@code /api/auth/**}), 비회원 링크({@code /api/public/**}),
   * 다시 확인 자신, 권리 행사(탈퇴·공유 그만두기·개인정보 사진 지우기, 내려받기의 원본 사진 주소), 계정 보안(비밀번호 변경). {@code POST /api/me}는
   * 문자 끄기만일 때 받는데 본문을 봐야 해서 {@code MeService}가 판단한다. 가린 사본 올리기(업로드 표)는 새 기록이라 막는다.
   */
  static final List<String> EXEMPT =
      List.of(
          "/api/auth/**",
          "/api/public/**",
          "/api/me",
          "/api/me/agreements",
          "/api/me/password",
          "/api/me/withdraw",
          "/api/rooms/*/stop-sharing",
          "/api/photos/*/erase",
          "/api/photos/view-urls");

  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final TermsService terms;

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (READ_METHODS.contains(request.getMethod())) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof UUID memberId) {
      terms.requireUpToDate(memberId);
    }
    return true;
  }
}
