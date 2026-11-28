package com.chilsuitjo.back.link.service;

import java.util.Map;
import java.util.UUID;

/**
 * 테스트가 SQL로 넣는 초대 링크에 실제처럼 열리는 토큰 해시를 넣는다. 키는 테스트 설정(src/test/resources/config/application.yml,
 * main yml의 local 값보다 앞섬)의 {@code app.keys.link-token}과 같다(바꾸면 함께 바꾼다). 가짜 해시를 넣으면 문자 주소를 만들지
 * 않는다({@link InviteLinkAddressService}).
 */
public final class TestLinkTokens {

  private static final LinkTokenKeys KEYS =
      new LinkTokenKeys("test-only-link-token-hmac-key-32bytes", Map.of());

  private TestLinkTokens() {}

  public static String hash(UUID linkId) {
    return KEYS.tokenHash(linkId);
  }

  public static String token(UUID linkId) {
    return KEYS.token(linkId);
  }
}
