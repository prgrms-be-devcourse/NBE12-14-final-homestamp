package com.chilsuitjo.back.global.security;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@link LoginMember} {@code UUID} 인자에 로그인한 회원 ID를 넣는다. 로그인하지 않았으면 {@code LOGIN_REQUIRED}. */
class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(LoginMember.class)
        && parameter.getParameterType().equals(UUID.class);
  }

  @Override
  public UUID resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof UUID memberId) {
      return memberId;
    }
    // 공개 API에 @LoginMember를 붙인 경우. 보호된 API는 그 전에 Security가 401로 막는다.
    throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
  }
}
