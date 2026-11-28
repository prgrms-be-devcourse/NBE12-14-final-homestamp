package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class NoticeTextTest {

  /** 장문 한도(2,000바이트, EUC-KR 기준 한글 2바이트). */
  private static final int LMS_MAX_BYTES = 2000;

  private static final Charset EUC_KR = Charset.forName("EUC-KR");

  @Test
  void 링크를_붙인_문자는_다음_줄에_주소와_수신_거부_방법이_있고_장문_한도_안이다() {
    // 가장 긴 Origin을 가정해도 넉넉하다. 토큰은 43자
    String address = "https://" + "a".repeat(200) + ".cloud/l#" + "A".repeat(43);
    for (NoticeKind kind : NoticeKind.values()) {
      String text = NoticeText.of(kind, address);
      assertThat(text).isEqualTo(NoticeText.of(kind) + "\n" + address + " (수신 거부는 링크에서)");
      assertThat(text.getBytes(EUC_KR).length).isLessThanOrEqualTo(LMS_MAX_BYTES);
    }
  }

  @Test
  void 링크가_든_문자는_토큰만으로_단문_90바이트를_넘어_장문으로_나간다() {
    String address = "https://homestamp.cloud/l#" + "A".repeat(43);

    assertThat(NoticeText.of(NoticeKind.RENOTIFY, address).getBytes(EUC_KR).length)
        .isGreaterThan(90);
  }

  @Test
  void 빈_주소는_받지_않는다() {
    assertThatThrownBy(() -> NoticeText.of(NoticeKind.INVITE, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
