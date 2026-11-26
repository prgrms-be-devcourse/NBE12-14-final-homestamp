package com.chilsuitjo.back.auth.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰 설정값(06 0-1). 이름은 06 표와 같다. 액세스 토큰 수명({@code auth.access.ttl})은 {@code AccessTokens}가 읽는다.
 */
@Component
record AuthTokenSettings(
    @Value("${auth.refresh.ttl}") Duration refreshTtl,
    @Value("${auth.refresh.grace}") Duration refreshGrace) {}
