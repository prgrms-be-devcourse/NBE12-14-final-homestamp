package com.chilsuitjo.back.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SolapiAuthTest {

  @Test
  void 공식_SDK와_같은_모양이고_서명은_비밀로_date와_salt를_이어_HMAC한_hex다() {
    String header =
        SolapiAuth.header(
            "test-key", "test-secret", Instant.parse("2026-11-28T03:04:05.678Z"), "A".repeat(32));

    // 기대값은 이 코드와 따로 python hmac으로 계산했다
    assertThat(header)
        .isEqualTo(
            "HMAC-SHA256 apiKey=test-key, date=2026-11-28T03:04:05Z, salt="
                + "A".repeat(32)
                + ", signature=04ddbf8e7c13370e2303c6172a7785dd2ed9f0840d8b78e453a207d95f1e41c5");
  }

  @Test
  void salt는_요청마다_새로_만든_영숫자_32자이고_비밀은_헤더에_나오지_않는다() {
    SolapiAuth auth = new SolapiAuth();
    Instant now = Instant.parse("2026-11-28T00:00:00Z");

    String first = auth.header("k", "never-shown-secret", now);
    String second = auth.header("k", "never-shown-secret", now);

    assertThat(first).matches(".*, salt=[0-9A-Za-z]{32}, signature=[0-9a-f]{64}");
    assertThat(first).isNotEqualTo(second).doesNotContain("never-shown-secret");
  }
}
