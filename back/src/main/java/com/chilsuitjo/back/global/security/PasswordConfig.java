package com.chilsuitjo.back.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시. {@code DelegatingPasswordEncoder}로 새 해시는 BCrypt로 만들고 앞에 {@code {bcrypt}}를 붙인다. 나중에 방식을
 * 바꿔도 이미 저장한 해시를 그 표시로 계속 확인할 수 있다.
 *
 * <p>BCrypt는 비밀번호를 UTF-8로 바꾼 72바이트까지만 쓴다. 72바이트를 넘는 새 비밀번호는 {@code encode}가 {@code
 * IllegalArgumentException}을 던지므로, 부르는 쪽이 그 전에 길이를 막아야 한다(가입은 {@code SignupRequest}가 {@code
 * VALIDATION_FAILED}로 막는다, 06 0-1 {@code password.rule}).
 */
@Configuration
public class PasswordConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
