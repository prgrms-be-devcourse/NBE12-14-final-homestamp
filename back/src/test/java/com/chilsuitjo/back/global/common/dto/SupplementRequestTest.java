package com.chilsuitjo.back.global.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SupplementRequestTest {

  @Test
  void 사진_목록이_없으면_빈_목록이다() {
    assertThat(new SupplementRequest("설명", null).uploadTickets()).isEmpty();
  }

  @Test
  void 설명이나_사진이_하나라도_있으면_근거가_있다() {
    assertThat(new SupplementRequest("물이 다시 새요", null).hasEvidence()).isTrue();
    assertThat(new SupplementRequest(null, List.of("ticket")).hasEvidence()).isTrue();
  }

  @Test
  void 설명이_비었거나_공백이고_사진이_없으면_근거가_없다() {
    assertThat(new SupplementRequest(null, null).hasEvidence()).isFalse();
    assertThat(new SupplementRequest("   ", List.of()).hasEvidence()).isFalse();
  }
}
