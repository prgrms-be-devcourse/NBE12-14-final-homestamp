package com.chilsuitjo.back.repair.entity;

import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 상황 변화 확인에 대한 지금 답({@code state.progress_note_response}, 05 2-5, C 테이블). 진행 상황 하나에 답 하나({@code
 * UNIQUE(progress_note_id)})이고, 바뀐 이력은 {@code RESPOND_SITUATION} 행동에 남는다.
 *
 * <p>보류·이견은 사유 필수(V1 CHECK). 보류·이견 → 맞아요는 정산 완료 전까지, 맞아요 뒤는 거절({@code ACK_FINAL}).
 */
@Entity
@Table(schema = "state", name = "progress_note_response")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProgressNoteResponse {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "progress_note_id", nullable = false, unique = true, updatable = false)
  private UUID progressNoteId;

  /** 응답한 측. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private PartySide side;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Answer answer;

  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  public ProgressNoteResponse(
      UUID roomRecordId,
      UUID progressNoteId,
      PartySide side,
      Answer answer,
      String reason,
      Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.progressNoteId = progressNoteId;
    this.side = side;
    this.answer = answer;
    this.reason = reason;
    this.createdAt = createdAt;
  }
}
