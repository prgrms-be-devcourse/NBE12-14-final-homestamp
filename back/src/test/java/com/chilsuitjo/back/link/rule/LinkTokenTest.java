package com.chilsuitjo.back.link.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LinkTokenTest {

  @Test
  void 새_토큰은_URL에_그대로_쓸_수_있고_매번_다르다() {
    String first = LinkToken.newToken();
    String second = LinkToken.newToken();

    assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void 같은_토큰은_같은_해시이고_원문이_보이지_않는다() {
    String token = LinkToken.newToken();

    assertThat(LinkToken.hash(token))
        .isEqualTo(LinkToken.hash(token))
        .hasSize(64)
        .doesNotContain(token);
    assertThat(LinkToken.hash(token)).isNotEqualTo(LinkToken.hash(LinkToken.newToken()));
  }
}
