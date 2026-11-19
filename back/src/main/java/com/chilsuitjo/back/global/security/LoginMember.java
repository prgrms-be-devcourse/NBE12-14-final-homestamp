package com.chilsuitjo.back.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 회원 ID를 컨트롤러 인자로 받는다: {@code @LoginMember UUID memberId}. 토큰 검증 방식({@link
 * AccessTokenVerifier})이 바뀌어도 쓰는 법은 같다. 로그인하지 않았으면 {@code LOGIN_REQUIRED}(401)다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginMember {}
