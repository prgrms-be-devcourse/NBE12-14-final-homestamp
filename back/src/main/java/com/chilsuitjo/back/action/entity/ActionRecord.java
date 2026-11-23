package com.chilsuitjo.back.action.entity;

import com.chilsuitjo.back.action.rule.ActionHash;
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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 행동 기록 한 줄({@code record.action}, 05 2-3, A 테이블). 고칠 수 없다: {@code @Immutable} + DB 트리거가 UPDATE를
 * 막는다.
 *
 * <p>이 엔티티는 pipeline 안쪽 부품(실제 {@code ActionWriter})만 쓴다. 다른 도메인은 {@code ActionReader}가 돌려주는 {@code
 * ActionEntry}로만 읽는다.
 *
 * <p>대상 칸은 최대 하나만 채운다(V1 {@code ck_action_one_target}). 모두 비면 호실 기록 자체가 대상이다.
 */
@Entity
@Immutable
@Table(schema = "record", name = "action")
public class ActionRecord {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 호실마다 1부터. {@code UNIQUE(room_record_id, room_seq)}. */
  @Column(name = "room_seq", nullable = false, updatable = false)
  private long roomSeq;

  /** 앞 행동의 hash. 첫 행동은 시작값. 32바이트. */
  @Column(name = "prev_hash", nullable = false, updatable = false)
  private byte[] prevHash;

  /** 이 행동의 hash(해시 체인). 32바이트. 입력 형식은 {@code ActionHash}(V4 {@code hash_version}의 규칙). */
  @Column(nullable = false, updatable = false)
  private byte[] hash;

  /** 그때 본 판의 내용 지문. 내용 대상이 없는 행동만 null. 32바이트. */
  @Column(name = "content_digest", updatable = false)
  private byte[] contentDigest;

  /** 서버·링크·운영 행동은 null. */
  @Column(name = "actor_member_id", updatable = false)
  private UUID actorMemberId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_side", length = 10, updatable = false)
  private PartySide actorSide;

  /** 회원 ID, {@code server}, {@code link:<ID>}, {@code ops:<담당>}. */
  @Column(name = "actor_key", nullable = false, length = 80, updatable = false)
  private String actorKey;

  /** {@code ActionType.name()}. {@code ck_action_type}(지금 V5)이 값을 막는다. */
  @Column(nullable = false, length = 40, updatable = false)
  private String type;

  /**
   * 요청 주체의 행동은 {@code <키>}, {@code <키>#2} …, 딸린 행동은 {@code <requestId>#n}. {@code UNIQUE(actor_key,
   * idempotency_key)}.
   */
  @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
  private String idempotencyKey;

  /** 한 요청으로 남긴 행동을 묶는 값. 한 요청의 모든 행동이 같은 값이다(V3, 05 2-3). 행동 해시 입력에 들어간다. */
  @Column(name = "request_id", nullable = false, updatable = false)
  private UUID requestId;

  @Column(name = "seen_version_no", updatable = false)
  private Integer seenVersionNo;

  @Column(updatable = false)
  private String reason;

  /** 작은 부가 값만(이름·번호 금지). JSON 글자로 둔다. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(updatable = false)
  private String payload;

  @Column(name = "party_id", updatable = false)
  private UUID partyId;

  @Column(name = "link_id", updatable = false)
  private UUID linkId;

  @Column(name = "room_part_id", updatable = false)
  private UUID roomPartId;

  @Column(name = "notice_id", updatable = false)
  private UUID noticeId;

  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  @Column(name = "inspection_id", updatable = false)
  private UUID inspectionId;

  @Column(name = "inspection_item_id", updatable = false)
  private UUID inspectionItemId;

  @Column(name = "settlement_id", updatable = false)
  private UUID settlementId;

  @Column(name = "settlement_item_id", updatable = false)
  private UUID settlementItemId;

  @Column(name = "settlement_transfer_id", updatable = false)
  private UUID settlementTransferId;

  @Column(name = "proposal_id", updatable = false)
  private UUID proposalId;

  @Column(name = "response_request_id", updatable = false)
  private UUID responseRequestId;

  @Column(name = "correction_request_id", updatable = false)
  private UUID correctionRequestId;

  @Column(name = "cause_note_id", updatable = false)
  private UUID causeNoteId;

  @Column(name = "progress_note_id", updatable = false)
  private UUID progressNoteId;

  @Column(name = "photo_id", updatable = false)
  private UUID photoId;

  /** 마이크로초로 자른 값. 해시를 계산한 값과 같아야 한다(DB가 더 작은 자리를 반올림하므로). */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 행동 해시를 계산한 입력 형식 버전({@code ActionHash.VERSION}, V4). */
  @Column(name = "hash_version", nullable = false, updatable = false)
  private short hashVersion;

  protected ActionRecord() {}

  /** 대상 칸은 {@link #target}으로 하나만 채운다. */
  public ActionRecord(
      int hashVersion,
      UUID roomRecordId,
      long roomSeq,
      byte[] prevHash,
      byte[] hash,
      byte[] contentDigest,
      UUID actorMemberId,
      PartySide actorSide,
      String actorKey,
      String type,
      String idempotencyKey,
      UUID requestId,
      Integer seenVersionNo,
      String reason,
      String payload,
      Instant createdAt) {
    if (hashVersion < 1 || hashVersion > Short.MAX_VALUE) {
      throw new IllegalArgumentException("hash_version은 1부터 smallint 범위예요: " + hashVersion);
    }
    this.hashVersion = (short) hashVersion;
    this.roomRecordId = roomRecordId;
    this.roomSeq = roomSeq;
    // 밖에서 원래 배열을 바꿔도 기록이 바뀌지 않게 복사해 둔다(getter도 복사본을 돌려준다).
    this.prevHash = prevHash == null ? null : prevHash.clone();
    this.hash = hash == null ? null : hash.clone();
    this.contentDigest = contentDigest == null ? null : contentDigest.clone();
    this.actorMemberId = actorMemberId;
    this.actorSide = actorSide;
    this.actorKey = actorKey;
    this.type = type;
    this.idempotencyKey = idempotencyKey;
    this.requestId = requestId;
    this.seenVersionNo = seenVersionNo;
    this.reason = reason;
    this.payload = payload;
    this.createdAt = createdAt;
  }

  /** 대상 칸 하나를 채운다. 저장 전에 한 번만 부른다. 칸 이름은 05 {@code action}의 대상 칸과 같다. */
  public ActionRecord target(String column, UUID targetId) {
    if (id != null) {
      throw new IllegalStateException("저장한 행동은 고칠 수 없어요");
    }
    if (hasTarget()) {
      throw new IllegalStateException("대상 칸은 하나만 채워요(ck_action_one_target)");
    }
    switch (column) {
      case "party_id" -> partyId = targetId;
      case "link_id" -> linkId = targetId;
      case "room_part_id" -> roomPartId = targetId;
      case "notice_id" -> noticeId = targetId;
      case "repair_case_id" -> repairCaseId = targetId;
      case "payment_id" -> paymentId = targetId;
      case "inspection_id" -> inspectionId = targetId;
      case "inspection_item_id" -> inspectionItemId = targetId;
      case "settlement_id" -> settlementId = targetId;
      case "settlement_item_id" -> settlementItemId = targetId;
      case "settlement_transfer_id" -> settlementTransferId = targetId;
      case "proposal_id" -> proposalId = targetId;
      case "response_request_id" -> responseRequestId = targetId;
      case "correction_request_id" -> correctionRequestId = targetId;
      case "cause_note_id" -> causeNoteId = targetId;
      case "progress_note_id" -> progressNoteId = targetId;
      case "photo_id" -> photoId = targetId;
      default -> throw new IllegalArgumentException("action에 없는 대상 칸이에요: " + column);
    }
    return this;
  }

  /** 채운 대상 칸 이름({@code ActionHash.TARGET_COLUMNS} 중 하나). 호실 기록 자체가 대상이면 null. */
  public String targetColumn() {
    UUID[] values = targetValues();
    for (int i = 0; i < values.length; i++) {
      if (values[i] != null) {
        return ActionHash.TARGET_COLUMNS.get(i);
      }
    }
    return null;
  }

  /** 채운 대상 칸의 ID. 호실 기록 자체가 대상이면 null. */
  public UUID targetId() {
    for (UUID value : targetValues()) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private boolean hasTarget() {
    return targetId() != null;
  }

  /** 대상 칸 값. 순서는 {@code ActionHash.TARGET_COLUMNS}(V1 칸 순서)와 같다. */
  private UUID[] targetValues() {
    return new UUID[] {
      partyId,
      linkId,
      roomPartId,
      noticeId,
      repairCaseId,
      paymentId,
      inspectionId,
      inspectionItemId,
      settlementId,
      settlementItemId,
      settlementTransferId,
      proposalId,
      responseRequestId,
      correctionRequestId,
      causeNoteId,
      progressNoteId,
      photoId
    };
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomRecordId() {
    return roomRecordId;
  }

  public long getRoomSeq() {
    return roomSeq;
  }

  public byte[] getPrevHash() {
    return prevHash == null ? null : prevHash.clone();
  }

  public byte[] getHash() {
    return hash == null ? null : hash.clone();
  }

  public byte[] getContentDigest() {
    return contentDigest == null ? null : contentDigest.clone();
  }

  public UUID getActorMemberId() {
    return actorMemberId;
  }

  public PartySide getActorSide() {
    return actorSide;
  }

  public String getActorKey() {
    return actorKey;
  }

  public String getType() {
    return type;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getRequestId() {
    return requestId;
  }

  public Integer getSeenVersionNo() {
    return seenVersionNo;
  }

  public String getReason() {
    return reason;
  }

  public String getPayload() {
    return payload;
  }

  public UUID getPartyId() {
    return partyId;
  }

  public UUID getLinkId() {
    return linkId;
  }

  public UUID getRoomPartId() {
    return roomPartId;
  }

  public UUID getNoticeId() {
    return noticeId;
  }

  public UUID getRepairCaseId() {
    return repairCaseId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getInspectionId() {
    return inspectionId;
  }

  public UUID getInspectionItemId() {
    return inspectionItemId;
  }

  public UUID getSettlementId() {
    return settlementId;
  }

  public UUID getSettlementItemId() {
    return settlementItemId;
  }

  public UUID getSettlementTransferId() {
    return settlementTransferId;
  }

  public UUID getProposalId() {
    return proposalId;
  }

  public UUID getResponseRequestId() {
    return responseRequestId;
  }

  public UUID getCorrectionRequestId() {
    return correctionRequestId;
  }

  public UUID getCauseNoteId() {
    return causeNoteId;
  }

  public UUID getProgressNoteId() {
    return progressNoteId;
  }

  public UUID getPhotoId() {
    return photoId;
  }

  public int getHashVersion() {
    return hashVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
