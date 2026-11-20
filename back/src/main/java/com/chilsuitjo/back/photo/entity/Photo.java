package com.chilsuitjo.back.photo.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.rule.Stage;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

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

  @Column(name = "correction_request_id", updatable = false)
  private UUID correctionRequestId;

  @Column(name = "redacted_from_photo_id", updatable = false)
  private UUID redactedFromPhotoId;

  @Column(name = "room_part_id", updatable = false)
  private UUID roomPartId;

  @Column(updatable = false, length = 10)
  @Enumerated(EnumType.STRING)
  private Stage stage;

  @Column(name = "uploaded_by_side", length = 10, updatable = false)
  @Enumerated(EnumType.STRING)
  private PartySide uploadedBySide;

  @Column(name = "device_taken_at", updatable = false)
  private Instant deviceTakenAt;

  @Column(name = "original_key", nullable = false, unique = true, length = 300)
  private String originalKey;

  @Column(name = "thumb_key", nullable = false, length = 300)
  private String thumbKey;

  @Column(name = "original_sha256", length = 64, nullable = false)
  private String originalSha256;

  @Column(name = "thumb_sha256", length = 64, nullable = false)
  private String thumbSha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder = 0;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  private Photo(
      UUID roomRecordId,
      UUID repairCaseId,
      UUID inspectionItemId,
      UUID inspectionId,
      UUID paymentId,
      UUID causeNoteId,
      UUID settlementItemId,
      UUID progressNoteId,
      UUID correctionRequestId,
      UUID redactedFromPhotoId,
      UUID roomPartId,
      Stage stage,
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
    if ((inspectionId == null) != (roomPartId == null)) {
      throw new IllegalArgumentException("점검 ID와 부위 ID는 함께 지정해야 합니다");
    }

    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.inspectionItemId = inspectionItemId;
    this.inspectionId = inspectionId;
    this.paymentId = paymentId;
    this.causeNoteId = causeNoteId;
    this.settlementItemId = settlementItemId;
    this.progressNoteId = progressNoteId;
    this.correctionRequestId = correctionRequestId;
    this.redactedFromPhotoId = redactedFromPhotoId;
    this.roomPartId = roomPartId;
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

  public static Photo forTarget(
      UUID roomRecordId,
      PhotoTargetType photoTargetType,
      UUID targetId,
      UUID redactedFromPhotoId,
      Stage stage,
      PartySide uploadedBySide,
      Instant deviceTakenAt,
      String originalKey,
      String thumbKey,
      String originalSha256,
      String thumbSha256,
      long sizeBytes,
      int sortOrder,
      Instant createdAt) {
    if (photoTargetType == null) {
      throw new IllegalArgumentException("PhotoTargetType cannot be null");
    }

    if (targetId == null) {
      throw new IllegalArgumentException("targetId cannot be null");
    }

    Photo photo =
        new Photo(
            roomRecordId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            redactedFromPhotoId,
            null,
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

    switch (photoTargetType) {
      case REPAIR_CASE:
        photo.repairCaseId = targetId;
        break;
      case INSPECTION_ITEM:
        photo.inspectionItemId = targetId;
        break;
      case INSPECTION:
        throw new IllegalArgumentException("부위 사진은 forInspection으로 생성해야 합니다");
      case PAYMENT:
        photo.paymentId = targetId;
        break;
      case CAUSE_NOTE:
        photo.causeNoteId = targetId;
        break;
      case SETTLEMENT_ITEM:
        photo.settlementItemId = targetId;
        break;
      case PROGRESS_NOTE:
        photo.progressNoteId = targetId;
        break;
      case CORRECTION_REQUEST:
        photo.correctionRequestId = targetId;
        break;
    }

    return photo;
  }

  public static Photo forInspection(
      UUID roomRecordId,
      UUID inspectionId,
      UUID roomPartId,
      UUID redactedFromPhotoId,
      Stage stage,
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
    return new Photo(
        roomRecordId,
        null,
        null,
        inspectionId,
        null,
        null,
        null,
        null,
        null,
        redactedFromPhotoId,
        roomPartId,
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
  }
}
