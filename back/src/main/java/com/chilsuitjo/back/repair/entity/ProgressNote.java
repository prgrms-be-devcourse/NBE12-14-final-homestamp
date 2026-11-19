package com.chilsuitjo.back.repair.entity;

import com.chilsuitjo.back.global.common.PartySide;
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
 * 진행 상황({@code record.progress_note}, 05 2-5, B 테이블). 덧붙이기만 한다: {@code @Immutable} + DB 트리거.
 *
 * <p>{@code asksCheck}가 true면 "상황이 바뀌었어요" 확인 요청(SITUATION_CHECK)이 함께 열린다(06 REP-6). 그 답은 {@link
 * ProgressNoteResponse}.
 */
@Entity
@Immutable
@Table(schema = "record", name = "progress_note")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProgressNote {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", nullable = false, updatable = false)
  private UUID repairCaseId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private PartySide side;

  @Column(nullable = false, updatable = false)
  private String text;

  /** 진행 상황 묻기(PROGRESS_CHECK, D {@code response_request})에 대한 답이면 그 요청. */
  @Column(name = "answers_request_id", updatable = false)
  private UUID answersRequestId;

  @Column(name = "asks_check", nullable = false, updatable = false)
  private boolean asksCheck;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public ProgressNote(
      UUID roomRecordId,
      UUID repairCaseId,
      PartySide side,
      String text,
      UUID answersRequestId,
      boolean asksCheck,
      Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.side = side;
    this.text = text;
    this.answersRequestId = answersRequestId;
    this.asksCheck = asksCheck;
    this.createdAt = createdAt;
  }
}
