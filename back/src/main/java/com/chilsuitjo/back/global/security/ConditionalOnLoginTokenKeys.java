package com.chilsuitjo.back.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 로그인 토큰 키({@code app.keys.access-token}, {@code app.keys.refresh-token}) 중 하나라도 있을 때만 빈을 만든다. 둘 다
 * 없으면(프로필 없이 띄운 서버) 토큰 기능이 꺼지고 보호된 API는 모두 401이다. {@code prod}는 이때 시작하지 않는다({@link
 * SecurityConfig}).
 *
 * <p>하나만 있으면 빈은 만들어지고, 빠진 키를 그 빈이 확인해 앱이 시작하지 않는다(설정 실수를 조용히 넘기지 않게).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnLoginTokenKeys.KeysPresent.class)
public @interface ConditionalOnLoginTokenKeys {

  String ACCESS_KEY = "app.keys.access-token";
  String REFRESH_KEY = "app.keys.refresh-token";

  /** 두 키 중 하나라도 비어 있지 않으면 맞다. */
  final class KeysPresent implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      var environment = context.getEnvironment();
      return !environment.getProperty(ACCESS_KEY, "").isBlank()
          || !environment.getProperty(REFRESH_KEY, "").isBlank();
    }
  }
}
