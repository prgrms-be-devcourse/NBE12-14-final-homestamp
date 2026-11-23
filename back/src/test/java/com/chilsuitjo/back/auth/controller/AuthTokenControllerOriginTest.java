package com.chilsuitjo.back.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code app.origin} 설정(04 4-1 재발급 요청 보호): 브라우저 Origin 모양이 아니면 앱이 시작하지 않는다. */
class AuthTokenControllerOriginTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:5173",
        "https://homestamp.cloud",
        "https://homestamp.cloud:8443"
      })
  void 브라우저_Origin_모양은_받는다(String origin) {
    assertThat(AuthTokenController.requireOrigin(origin)).isEqualTo(origin);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://homestamp.cloud/",
        "http://localhost:5173/",
        "https://homestamp.cloud/app",
        "https://homestamp.cloud?x=1",
        "https://homestamp.cloud#top",
        "https://user@homestamp.cloud",
        "homestamp.cloud",
        "ftp://homestamp.cloud",
        "https://homestamp cloud"
      })
  void 끝의_슬래시나_경로가_붙은_설정은_시작하지_않는다(String origin) {
    assertThatThrownBy(() -> AuthTokenController.requireOrigin(origin))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.origin은 http(s)://host[:port] 모양이어야 한다");
  }

  @Test
  void 컨트롤러를_만들_때_확인해_앱이_시작하지_않는다() {
    assertThatThrownBy(() -> new AuthTokenController(null, null, "https://homestamp.cloud/"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("https://homestamp.cloud/");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void 비어_있으면_시작하지_않는다(String origin) {
    assertThatThrownBy(() -> AuthTokenController.requireOrigin(origin))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.origin이 없다");
  }
}
