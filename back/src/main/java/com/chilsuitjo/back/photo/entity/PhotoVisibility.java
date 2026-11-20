package com.chilsuitjo.back.photo.entity;

import com.chilsuitjo.back.photo.rule.EraseReason;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photo_visibility", schema = "state")
public class PhotoVisibility {
  @Id
  @Column(name = "photo_id", updatable = false, nullable = false)
  private UUID photoId;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "hidden_on_link", nullable = false)
  private boolean hiddenOnLink;

  @Column(name = "marked_wrong_at")
  private Instant markedWrongAt;

  @Column(name = "erased_at")
  private Instant erasedAt;

  @Column(name = "erase_reason", length = 20)
  @Enumerated(EnumType.STRING)
  private EraseReason eraseReason;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private Long version;

  public PhotoVisibility(
      UUID photoId,
      UUID roomRecordId,
      boolean hiddenOnLink,
      Instant markedWrongAt,
      Instant erasedAt,
      EraseReason eraseReason,
      Instant createdAt) {
    if (photoId == null || roomRecordId == null || createdAt == null) {
      throw new IllegalArgumentException("필수 값이 누락되었습니다");
    }
    if ((erasedAt == null) != (eraseReason == null)) {
      throw new IllegalArgumentException("삭제 시각과 사유는 함께 지정해야 합니다");
    }

    this.photoId = photoId;
    this.roomRecordId = roomRecordId;
    this.hiddenOnLink = hiddenOnLink;
    this.markedWrongAt = markedWrongAt;
    this.erasedAt = erasedAt;
    this.eraseReason = eraseReason;
    this.createdAt = createdAt;
  }
}
