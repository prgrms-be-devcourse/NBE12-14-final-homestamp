package com.chilsuitjo.back.link.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.rule.EraseReason;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkRecordResponseTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  @Test
  void 올린_사람이_지운_사진은_주소_없이_측_시각_사유를_담는다() {
    // 03 6-1·불변식 16: "올린 사람이 개인정보 때문에 지운 사진 · 측 · 시각"이 링크 화면에도 남는다
    LinkRecordResponse.Photo erased =
        LinkRecordResponse.Photo.erased(
            UUID.randomUUID(),
            new ErasedPhoto(PartySide.LANDLORD, NOW.minusSeconds(3600), EraseReason.PRIVACY));

    assertThat(erased.erased()).isTrue();
    assertThat(erased.erasedAt()).isEqualTo(NOW.minusSeconds(3600));
    assertThat(erased.erasedSide()).isEqualTo(PartySide.LANDLORD);
    assertThat(erased.erasedReason()).isEqualTo(EraseReason.PRIVACY);
    assertThat(erased.thumbUrl()).isNull();
    assertThat(erased.originalUrl()).isNull();
  }

  @Test
  void 다른_사람의_요청으로_지운_사진은_측_없이_사유와_시각만_담는다() {
    LinkRecordResponse.Photo erased =
        LinkRecordResponse.Photo.erased(
            UUID.randomUUID(), new ErasedPhoto(PartySide.TENANT, NOW, EraseReason.THIRD_PARTY));

    assertThat(erased.erasedSide()).isNull();
    assertThat(erased.erasedReason()).isEqualTo(EraseReason.THIRD_PARTY);
    assertThat(erased.erasedAt()).isEqualTo(NOW);
    assertThatThrownBy(() ->
            new LinkRecordResponse.Photo(
                UUID.randomUUID(),
                null,
                null,
                null,
                true,
                NOW,
                PartySide.TENANT,
                EraseReason.THIRD_PARTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지우지_않은_사진은_흔적_칸이_없다() {
    LinkRecordResponse.Photo shown =
        LinkRecordResponse.Photo.shown(
            UUID.randomUUID(), "thumb", "original", NOW.plusSeconds(300));

    assertThat(shown.erasedAt()).isNull();
    assertThat(shown.erasedSide()).isNull();
    assertThat(shown.erasedReason()).isNull();
  }

  @Test
  void 지웠다는_표시와_지운_시각_사유는_함께_있어야_한다() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () -> new LinkRecordResponse.Photo(id, null, null, null, true, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new LinkRecordResponse.Photo(id, null, null, null, true, NOW, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new LinkRecordResponse.Photo(
                id, "thumb", "original", NOW, false, null, PartySide.TENANT, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지운_사진에는_주소를_담지_않는다() {
    assertThatThrownBy(() ->
            new LinkRecordResponse.Photo(
                UUID.randomUUID(),
                "thumb",
                "original",
                NOW,
                true,
                NOW,
                PartySide.TENANT,
                EraseReason.PRIVACY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
