package com.chilsuitjo.back.global.security;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link LoginMember}를 MVC에 등록한다. {@link SecurityConfig}와 나눈 이유: 그쪽은 MVC의 예외 처리기를 받아 쓰는데, MVC 설정이
 * 다시 그쪽을 부르면 순환 참조가 된다.
 */
@Configuration
class LoginMemberWebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new LoginMemberArgumentResolver());
  }
}
