package com.chilsuitjo.back.photo.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhotoTest {
  private static final UUID ROOM_ID = UUID.randomUUID();
  private static final UUID TARGET_ID = UUID.randomUUID();
  private static final UUID PART_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "roomRecordId",
        "stage",
        "uploadedBySide",
        "originalKey",
        "thumbKey",
        "originalSha256",
        "thumbSha256",
        "createdAt"
      })
  void 공통_필수값이_null이면_생성을_거절한다(String missingField) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> repairPhoto(missingField, 1))
        .withMessageContaining(missingField);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void 파일_크기는_양수여야_한다(long sizeBytes) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> repairPhoto("", sizeBytes))
        .withMessageContaining("sizeBytes");
  }

  @ParameterizedTest
  @MethodSource("malformedSha256")
  void 원본_지문이_64자_소문자_hex가_아니면_생성을_거절한다(String sha256) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photoWithSha256(sha256, "b".repeat(64)))
        .withMessageContaining("originalSha256");
  }

  @ParameterizedTest
  @MethodSource("malformedSha256")
  void 썸네일_지문이_64자_소문자_hex가_아니면_생성을_거절한다(String sha256) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photoWithSha256("a".repeat(64), sha256))
        .withMessageContaining("thumbSha256");
  }

  private static Stream<String> malformedSha256() {
    return Stream.of(
        "", // 빈 값
        "a".repeat(63), // 짧음
        "a".repeat(65), // 김
        "A".repeat(64), // 대문자
        "g".repeat(64), // hex 아님
        " " + "a".repeat(63)); // 공백
  }

  @Test
  void 지문은_숫자와_소문자_hex가_섞인_64자를_받는다() {
    String sha256 = "0123456789abcdef".repeat(4);

    Photo photo = photoWithSha256(sha256, sha256);

    assertThat(photo.getOriginalSha256()).isEqualTo(sha256);
    assertThat(photo.getThumbSha256()).isEqualTo(sha256);
  }

  @Test
  void 일반_사진은_점검_ID와_부위_ID_없이_생성한다() {
    Photo photo = repairPhoto("", 1);

    assertThat(photo.getRepairCaseId()).isEqualTo(TARGET_ID);
    assertThat(photo.getInspectionId()).isNull();
    assertThat(photo.getRoomPartId()).isNull();
    assertThat(photo.getDeviceTakenAt()).isNull();
    assertThat(photo.getRedactedFromPhotoId()).isNull();
  }

  @Test
  void 부위_사진은_점검_ID와_부위_ID가_모두_필요하다() {
    assertThatIllegalArgumentException().isThrownBy(() -> inspectionPhoto(null, PART_ID));
    assertThatIllegalArgumentException().isThrownBy(() -> inspectionPhoto(TARGET_ID, null));
    assertThatIllegalArgumentException().isThrownBy(() -> inspectionPhoto(null, null));

    Photo photo = inspectionPhoto(TARGET_ID, PART_ID);
    assertThat(photo.getInspectionId()).isEqualTo(TARGET_ID);
    assertThat(photo.getRoomPartId()).isEqualTo(PART_ID);
  }

  private Photo repairPhoto(String missingField, long sizeBytes) {
    return Photo.forTarget(
        "roomRecordId".equals(missingField) ? null : ROOM_ID,
        PhotoTargetType.REPAIR_CASE,
        TARGET_ID,
        null,
        "stage".equals(missingField) ? null : PhotoStage.BEFORE,
        "uploadedBySide".equals(missingField) ? null : PartySide.TENANT,
        null,
        "originalKey".equals(missingField) ? null : "photos/original.jpg",
        "thumbKey".equals(missingField) ? null : "photos/thumb.jpg",
        "originalSha256".equals(missingField) ? null : "a".repeat(64),
        "thumbSha256".equals(missingField) ? null : "b".repeat(64),
        sizeBytes,
        0,
        "createdAt".equals(missingField) ? null : NOW);
  }

  private Photo photoWithSha256(String originalSha256, String thumbSha256) {
    return Photo.forTarget(
        ROOM_ID,
        PhotoTargetType.REPAIR_CASE,
        TARGET_ID,
        null,
        PhotoStage.BEFORE,
        PartySide.TENANT,
        null,
        "photos/original.jpg",
        "photos/thumb.jpg",
        originalSha256,
        thumbSha256,
        1,
        0,
        NOW);
  }

  private Photo inspectionPhoto(UUID inspectionId, UUID roomPartId) {
    return Photo.forInspection(
        ROOM_ID,
        inspectionId,
        roomPartId,
        null,
        PhotoStage.MOVE_IN,
        PartySide.TENANT,
        null,
        "photos/original.jpg",
        "photos/thumb.jpg",
        "a".repeat(64),
        "b".repeat(64),
        1,
        0,
        NOW);
  }
}
