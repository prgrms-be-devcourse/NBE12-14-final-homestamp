package com.chilsuitjo.back.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각은 서버 시각만, UTC로 쓴다(원칙 5). 테스트는 이 빈을 바꿔 시간을 옮긴다. */
@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
