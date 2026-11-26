package com.chilsuitjo.back.global.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 일반 앱(웹 서버)의 프로필을 빈을 만들기 전에 검사한다(decisions 2026-11-25 운영 문자 모드). 다음이면 기동하지 않는다.
 *
 * <ul>
 *   <li>local·test·prod가 하나도 없음: 운영 이미지({@code back/Dockerfile})는 프로필을 정하지 않으므로, 프로필을 빠뜨린 실행이 개발용도
 *       운영용도 아닌 어중간한 설정으로 뜨지 않게 한다. 로컬 {@code bootRun}은 local이 기본이고(build.gradle), 운영은 {@code
 *       deploy.sh}가 {@code SPRING_PROFILES_ACTIVE=prod}를 넘긴다
 *   <li>prod와 local·test가 함께: 개발용 고정 키·API 문서·가짜 문자가 운영 설정에 섞이지 않게 한다
 * </ul>
 *
 * <p>{@code --migrate}(#173)는 이 컨텍스트를 띄우지 않고 끝나므로 이 검사와 상관없다. {@code --apply-grants}는 아직 구현이
 * 없어(#156) 일반 앱과 같이 검사한다.
 */
@Configuration(proxyBeanMethods = false)
public class ProfileGuard {

  /** 반환 타입을 {@link Check}로 두어야 Spring이 실행 전에 {@link PriorityOrdered}임을 안다. */
  @Bean
  static Check profileCheck(Environment environment) {
    return new Check(environment);
  }

  /** 다른 기동 검사(문자 방식 등)보다 먼저 돈다. 프로필이 틀렸으면 그 이유를 먼저 말한다. */
  static final class Check implements BeanFactoryPostProcessor, PriorityOrdered {

    private final Environment environment;

    Check(Environment environment) {
      this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
      ProfileGuard.check(environment);
    }

    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }
  }

  static void check(Environment environment) {
    boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
    boolean development = environment.acceptsProfiles(Profiles.of("local | test"));
    if (prod && development) {
      throw new IllegalStateException("prod는 local·test와 함께 켜지 않는다(개발용 설정이 운영에 섞임)");
    }
    if (!prod && !development) {
      throw new IllegalStateException(
          "활성 프로필(local·test·prod)이 없어 시작하지 않는다. 로컬은 bootRun(local), 운영은 SPRING_PROFILES_ACTIVE=prod");
    }
  }
}
