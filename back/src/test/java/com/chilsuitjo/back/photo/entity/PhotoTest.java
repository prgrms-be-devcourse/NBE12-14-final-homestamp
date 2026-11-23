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
  private static final String ORIGINAL_KEY = "photos/" + ROOM_ID + "/original.jpg";
  private static final String THUMB_KEY = "photos/" + ROOM_ID + "/thumb.jpg";
  private static final String ORIGINAL_SHA = "a".repeat(64);
  private static final String THUMB_SHA = "b".repeat(64);

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

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void 원본_키가_비어_있으면_생성을_거절한다(String key) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photo(key, THUMB_KEY, 0))
        .withMessageContaining("originalKey");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void 썸네일_키가_비어_있으면_생성을_거절한다(String key) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photo(ORIGINAL_KEY, key, 0))
        .withMessageContaining("thumbKey");
  }

  @ParameterizedTest
  @MethodSource("keysOutsideRoomFolder")
  void 원본_키가_그_호실_사진_폴더_아래가_아니면_생성을_거절한다(String key) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photo(key, THUMB_KEY, 0))
        .withMessageContaining("originalKey");
  }

  @ParameterizedTest
  @MethodSource("keysOutsideRoomFolder")
  void 썸네일_키가_그_호실_사진_폴더_아래가_아니면_생성을_거절한다(String key) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photo(ORIGINAL_KEY, key, 0))
        .withMessageContaining("thumbKey");
  }

  private static Stream<String> keysOutsideRoomFolder() {
    return Stream.of(
        "photos/" + UUID.randomUUID() + "/a.jpg", // 다른 호실 폴더
        "pending/" + ROOM_ID + "/a.jpg", // 확정 전 경로
        "photos/" + ROOM_ID + "a.jpg", // 폴더 구분자 없음
        "photos/" + ROOM_ID + "/", // 파일 이름 없음
        "x/photos/" + ROOM_ID + "/a.jpg", // 앞에 다른 경로
        "a.jpg"); // 폴더 없음
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  void 표시_순서가_음수면_생성을_거절한다(int sortOrder) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> photo(ORIGINAL_KEY, THUMB_KEY, sortOrder))
        .withMessageContaining("sortOrder");
  }

  @Test
  void 그_호실_폴더의_키와_0_이상의_표시_순서는_받는다() {
    Photo first = photo(ORIGINAL_KEY, THUMB_KEY, 0);
    Photo second = photo("photos/" + ROOM_ID + "/sub/b.jpg", THUMB_KEY, 3);

    assertThat(first.getOriginalKey()).isEqualTo(ORIGINAL_KEY);
    assertThat(first.getThumbKey()).isEqualTo(THUMB_KEY);
    assertThat(first.getSortOrder()).isZero();
    assertThat(second.getSortOrder()).isEqualTo(3);
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
        "originalKey".equals(missingField) ? null : ORIGINAL_KEY,
        "thumbKey".equals(missingField) ? null : THUMB_KEY,
        "originalSha256".equals(missingField) ? null : ORIGINAL_SHA,
        "thumbSha256".equals(missingField) ? null : THUMB_SHA,
        sizeBytes,
        0,
        "createdAt".equals(missingField) ? null : NOW);
  }

  private Photo photoWithSha256(String originalSha256, String thumbSha256) {
    return photo(ORIGINAL_KEY, THUMB_KEY, originalSha256, thumbSha256, 0);
  }

  private Photo photo(String originalKey, String thumbKey, int sortOrder) {
    return photo(originalKey, thumbKey, ORIGINAL_SHA, THUMB_SHA, sortOrder);
  }

  private Photo photo(
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      int sortOrder) {
    return Photo.forTarget(
        ROOM_ID,
        PhotoTargetType.REPAIR_CASE,
        TARGET_ID,
        null,
        PhotoStage.BEFORE,
        PartySide.TENANT,
        null,
        originalKey,
        thumbKey,
        originalSha256,
        thumbSha256,
        1,
        sortOrder,
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
        ORIGINAL_KEY,
        THUMB_KEY,
        "a".repeat(64),
        "b".repeat(64),
        1,
        0,
        NOW);
  }
}
