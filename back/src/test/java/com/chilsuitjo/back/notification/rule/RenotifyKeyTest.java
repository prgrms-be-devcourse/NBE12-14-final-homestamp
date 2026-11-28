package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionBatch;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 재통지 행동 키(#370 T-2). 응답 기회(요청 + 링크)마다 하나이고 행동 키 한도 안이다. */
class RenotifyKeyTest {

  private static final UUID REQUEST = UUID.fromString("01a22322-da0b-7eb0-97a5-e51c2458daf3");
  private static final UUID LINK = UUID.fromString("01a22322-d9bc-71ec-a107-d7fb7f62aa61");

  @Test
  void 요청과_링크로_48자_키를_만들고_한도와_구분자를_지킨다() {
    String key = RenotifyKey.of(REQUEST, LINK);

    assertThat(key).hasSize(48).startsWith("rn:").doesNotContain("#").doesNotContain("=");
    assertThat(key.length()).isLessThanOrEqualTo(ActionBatch.MAX_KEY_LENGTH);
    assertThat(RenotifyKey.of(REQUEST, LINK)).isEqualTo(key);
  }

  @Test
  void 링크가_다르면_다른_키이고_같은_링크면_요청이_달라도_다르다() {
    UUID otherLink = UUID.fromString("01a22322-d9bc-71ec-a107-d7fb7f62aa62");
    UUID otherRequest = UUID.fromString("01a22322-da0b-7eb0-97a5-e51c2458daf4");

    assertThat(RenotifyKey.of(REQUEST, otherLink)).isNotEqualTo(RenotifyKey.of(REQUEST, LINK));
    assertThat(RenotifyKey.of(otherRequest, LINK)).isNotEqualTo(RenotifyKey.of(REQUEST, LINK));
  }

  @Test
  void 링크_없는_통지면_링크_자리가_빠진_27자다() {
    assertThat(RenotifyKey.of(REQUEST, null)).hasSize(27).endsWith(":-");
    assertThatThrownBy(() -> RenotifyKey.of(null, LINK)).isInstanceOf(NullPointerException.class);
  }
}
