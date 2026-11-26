package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.link.rule.LinkToken;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 링크 토큰 = HMAC(키, 링크 ID)(#163). 같은 링크면 다시 만들 수 있고, 키가 잘못되면 시작하지 않는다. */
class LinkTokenKeysTest {

  private static final String KEY = "unit-test-link-token-key-32-bytes!!";
  private final LinkTokenKeys keys = new LinkTokenKeys(KEY, Map.of());

  @Test
  void 같은_링크면_언제나_같은_토큰이고_URL에_넣을_수_있는_43자다() {
    UUID link = UUID.randomUUID();

    String token = keys.token(link);

    assertThat(keys.token(link)).isEqualTo(token);
    assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(keys.tokenHash(link)).isEqualTo(LinkToken.hash(token));
  }

  @Test
  void 링크나_키가_다르면_토큰이_다르다() {
    UUID link = UUID.randomUUID();

    assertThat(keys.token(UUID.randomUUID())).isNotEqualTo(keys.token(link));
    assertThat(new LinkTokenKeys("another-link-token-key-32-bytes!!!!", Map.of()).token(link))
        .isNotEqualTo(keys.token(link));
  }

  /** local 프로필은 {@code .env}가 아니라 {@code application.yml}의 고정 값으로 키를 받는다. 빠지면 모두의 bootRun이 멈춘다. */
  @Test
  void local_test_프로필에는_고정_키가_있고_기본값은_비어_있다() throws IOException {
    var docs =
        new YamlPropertySourceLoader().load("main", new ClassPathResource("application.yml"));

    PropertySource<?> base = docs.getFirst();
    PropertySource<?> localTest =
        docs.stream()
            .filter(d ->
                "(local | test) & !prod"
                    .equals(String.valueOf(d.getProperty("spring.config.activate.on-profile"))))
            .findFirst()
            .orElseThrow();

    assertThat(String.valueOf(base.getProperty(LinkTokenKeys.KEY))).isBlank();
    String localKey = String.valueOf(localTest.getProperty(LinkTokenKeys.KEY));
    new LinkTokenKeys(localKey, Map.of());
  }

  @Test
  void 키가_없거나_짧거나_다른_비밀_키와_같으면_시작하지_않는다() {
    assertThatThrownBy(() -> new LinkTokenKeys("", Map.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new LinkTokenKeys("short-key", Map.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new LinkTokenKeys(KEY, Map.of("app.keys.upload-ticket", KEY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.keys.upload-ticket");
  }
}
