package com.chilsuitjo.back.photo.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * 사진 카드({@code record.photo}, 05 2-8, B 테이블). 덧붙이기만 한다: {@code @Immutable} + DB 트리거. 행까지 지우는 것은 호실
 * 기록 통째 삭제뿐이고, 개인정보·탈퇴·다른 사람 요청으로 지울 때는 파일만 지우고 이 행은 흔적으로 남긴다({@link PhotoVisibility}).
 *
 * <p>대상 칸은 여덟 개 중 하나만 채운다(V1 {@code num_nonnulls(...) = 1}). 그래서 생성자는 공통 칸만 받고, 대상은 팩토리가 채운다: 일반
 * 사진은 {@link #forTarget}, 점검의 부위 사진은 {@link #forInspection}.
 *
 * <p>검사를 통과한 뒤, 등록·추가 행동과 같은 트랜잭션에서만 만든다(05 2-8, 불변식 14).
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photo", schema = "record")
public class Photo {
  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "inspection_item_id", updatable = false)
  private UUID inspectionItemId;

  /** 점검의 부위 사진(하자 아님)이면 그 점검. 채우면 {@link #roomPartId}도 채운다(V1 CHECK). */
  @Column(name = "inspection_id", updatable = false)
  private UUID inspectionId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  @Column(name = "cause_note_id", updatable = false)
  private UUID causeNoteId;

  @Column(name = "settlement_item_id", updatable = false)
  private UUID settlementItemId;

  @Column(name = "progress_note_id", updatable = false)
  private UUID progressNoteId;

  /** 고친 내용을 [사진 더해서 다시 요청]할 때 더한 근거. */
  @Column(name = "correction_request_id", updatable = false)
  private UUID correctionRequestId;

  /** 개인정보 때문에 지운 원본 자리에 올린 가린 사본이면 그 원본 사진. 같은 사진이라는 증명은 아니다(06 PHO-5d). */
  @Column(name = "redacted_from_photo_id", updatable = false)
  private UUID redactedFromPhotoId;

  /**
   * 부위 사진만 채운다. 다른 사진의 부위는 복사하지 않고 조회 때 대상에서 읽는다(대상의 부위를 고쳐도 옛 부위로 남지 않게, 05 2-8 "부위는 사진에 복사하지
   * 않는다").
   */
  @Column(name = "room_part_id", updatable = false)
  private UUID roomPartId;

  /** 서버가 제출 행동과 대상에서 정한다. 화면이 보내지 않는다(05 2-8). */
  @Column(updatable = false, length = 10, nullable = false)
  @Enumerated(EnumType.STRING)
  private PhotoStage stage;

  @Column(name = "uploaded_by_side", length = 10, updatable = false, nullable = false)
  @Enumerated(EnumType.STRING)
  private PartySide uploadedBySide;

  /** 기기가 적은 촬영 시각. 참고값이고 기록 시각이 되지 않는다(06 PHO-1f, 불변식 10). */
  @Column(name = "device_taken_at", updatable = false)
  private Instant deviceTakenAt;

  /** S3 키 {@code photos/{room_record_id}/…}. 같은 업로드 표로 두 번 확정하지 못하게 UNIQUE다. */
  @Column(name = "original_key", nullable = false, unique = true, length = 300)
  private String originalKey;

  @Column(name = "thumb_key", nullable = false, length = 300)
  private String thumbKey;

  /** S3 체크섬(지문값). 파일을 지워도 남는다. */
  @Column(name = "original_sha256", length = 64, nullable = false)
  private String originalSha256;

  @Column(name = "thumb_sha256", length = 64, nullable = false)
  private String thumbSha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  /** 같은 대상 안의 표시 순서. 만들 때 정하고 바꾸지 않는다(05 2-8). */
  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 공통 칸만 받는다. 대상 칸은 팩토리가 하나만 채운다. */
  private Photo(
      UUID roomRecordId,
      UUID redactedFromPhotoId,
      PhotoStage stage,
      PartySide uploadedBySide,
      Instant deviceTakenAt,
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      long sizeBytes,
      int sortOrder,
      Instant createdAt) {
    if (roomRecordId == null) {
      throw new IllegalArgumentException("roomRecordId는 필수입니다");
    }
    if (stage == null) {
      throw new IllegalArgumentException("stage는 필수입니다");
    }
    if (uploadedBySide == null) {
      throw new IllegalArgumentException("uploadedBySide는 필수입니다");
    }
    if (originalKey == null) {
      throw new IllegalArgumentException("originalKey는 필수입니다");
    }
    if (thumbKey == null) {
      throw new IllegalArgumentException("thumbKey는 필수입니다");
    }
    if (originalSha256 == null) {
      throw new IllegalArgumentException("originalSha256는 필수입니다");
    }
    if (thumbSha256 == null) {
      throw new IllegalArgumentException("thumbSha256는 필수입니다");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("createdAt은 필수입니다");
    }
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("sizeBytes는 0보다 커야 합니다");
    }

    this.roomRecordId = roomRecordId;
    this.redactedFromPhotoId = redactedFromPhotoId;
    this.stage = stage;
    this.uploadedBySide = uploadedBySide;
    this.deviceTakenAt = deviceTakenAt;
    this.originalKey = originalKey;
    this.thumbKey = thumbKey;
    this.originalSha256 = originalSha256;
    this.thumbSha256 = thumbSha256;
    this.sizeBytes = sizeBytes;
    this.sortOrder = sortOrder;
    this.createdAt = createdAt;
  }

  /** 부위 사진이 아닌 사진. {@code targetType}이 가리키는 대상 칸 하나만 채운다. */
  public static Photo forTarget(
      UUID roomRecordId,
      PhotoTargetType targetType,
      UUID targetId,
      UUID redactedFromPhotoId,
      PhotoStage stage,
      PartySide uploadedBySide,
      Instant deviceTakenAt,
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      long sizeBytes,
      int sortOrder,
      Instant createdAt) {
    if (targetId == null) {
      throw new IllegalArgumentException("targetId는 필수입니다");
    }
    Photo photo =
        new Photo(
            roomRecordId,
            redactedFromPhotoId,
            stage,
            uploadedBySide,
            deviceTakenAt,
            originalKey,
            thumbKey,
            originalSha256,
            thumbSha256,
            sizeBytes,
            sortOrder,
            createdAt);

    // case null이 있으면 컴파일러가 모든 값을 다뤘는지 검사한다. PhotoTargetType에 값을 더하고 여기를 빠뜨리면 컴파일 오류가 난다.
    switch (targetType) {
      case null -> throw new IllegalArgumentException("targetType은 필수입니다");
      case REPAIR_CASE -> photo.repairCaseId = targetId;
      case INSPECTION_ITEM -> photo.inspectionItemId = targetId;
      case PAYMENT -> photo.paymentId = targetId;
      case CAUSE_NOTE -> photo.causeNoteId = targetId;
      case SETTLEMENT_ITEM -> photo.settlementItemId = targetId;
      case PROGRESS_NOTE -> photo.progressNoteId = targetId;
      case CORRECTION_REQUEST -> photo.correctionRequestId = targetId;
    }
    return photo;
  }

  /** 입주·퇴거 점검의 부위 사진(하자 아님). 점검에는 부위가 없어서 요청의 부위를 함께 남긴다(05 2-8). */
  public static Photo forInspection(
      UUID roomRecordId,
      UUID inspectionId,
      UUID roomPartId,
      UUID redactedFromPhotoId,
      PhotoStage stage,
      PartySide uploadedBySide,
      Instant deviceTakenAt,
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      long sizeBytes,
      int sortOrder,
      Instant createdAt) {
    if (inspectionId == null || roomPartId == null) {
      throw new IllegalArgumentException("점검 ID와 부위 ID는 필수입니다");
    }
    Photo photo =
        new Photo(
            roomRecordId,
            redactedFromPhotoId,
            stage,
            uploadedBySide,
            deviceTakenAt,
            originalKey,
            thumbKey,
            originalSha256,
            thumbSha256,
            sizeBytes,
            sortOrder,
            createdAt);
    photo.inspectionId = inspectionId;
    photo.roomPartId = roomPartId;
    return photo;
  }
}
