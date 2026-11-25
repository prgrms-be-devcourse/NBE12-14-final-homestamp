package com.chilsuitjo.back.link.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkRecordResponseTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  @Test
  void 지운_사진은_주소_없이_지운_시각을_담는다() {
    // 03 6-1: "올린 사람이 개인정보 때문에 지운 사진 · 시각"이 링크 화면에도 남는다
    LinkRecordResponse.Photo erased =
        LinkRecordResponse.Photo.erased(UUID.randomUUID(), NOW.minusSeconds(3600));

    assertThat(erased.erased()).isTrue();
    assertThat(erased.erasedAt()).isEqualTo(NOW.minusSeconds(3600));
    assertThat(erased.thumbUrl()).isNull();
    assertThat(erased.originalUrl()).isNull();
  }

  @Test
  void 지우지_않은_사진은_지운_시각이_없다() {
    LinkRecordResponse.Photo shown =
        new LinkRecordResponse.Photo(
            UUID.randomUUID(), "thumb", "original", NOW.plusSeconds(300), false, null);

    assertThat(shown.erasedAt()).isNull();
  }

  @Test
  void 지웠다는_표시와_지운_시각은_함께_있어야_한다() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> new LinkRecordResponse.Photo(id, null, null, null, true, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LinkRecordResponse.Photo(id, "thumb", "original", NOW, false, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지운_사진에는_주소를_담지_않는다() {
    assertThatThrownBy(() ->
            new LinkRecordResponse.Photo(UUID.randomUUID(), "thumb", "original", NOW, true, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
