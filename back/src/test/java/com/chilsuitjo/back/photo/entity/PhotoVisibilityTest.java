package com.chilsuitjo.back.photo.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PhotoVisibilityTest {
  private static final UUID PHOTO_ID = UUID.randomUUID();
  private static final UUID ROOM_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-11-20T00:00:00Z");

  @ParameterizedTest
  @MethodSource("missingRequiredFields")
  void 필수값이_null이면_생성을_거절한다(UUID photoId, UUID roomRecordId, Instant createdAt) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PhotoVisibility(photoId, roomRecordId, createdAt));
  }

  private static Stream<Arguments> missingRequiredFields() {
    return Stream.of(
        Arguments.of(null, ROOM_ID, CREATED_AT),
        Arguments.of(PHOTO_ID, null, CREATED_AT),
        Arguments.of(PHOTO_ID, ROOM_ID, null));
  }

  @Test
  void 새_공개_상태는_숨기지_않고_표시와_삭제도_비운_채로_시작한다() {
    PhotoVisibility visibility = new PhotoVisibility(PHOTO_ID, ROOM_ID, CREATED_AT);

    assertThat(visibility.getPhotoId()).isEqualTo(PHOTO_ID);
    assertThat(visibility.getRoomRecordId()).isEqualTo(ROOM_ID);
    assertThat(visibility.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(visibility.isHiddenOnLink()).isFalse();
    assertThat(visibility.getMarkedWrongAt()).isNull();
    assertThat(visibility.getErasedAt()).isNull();
    assertThat(visibility.getEraseReason()).isNull();
    assertThat(visibility.getVersion()).isNull();
  }
}
