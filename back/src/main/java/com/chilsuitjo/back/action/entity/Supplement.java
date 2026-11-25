package com.chilsuitjo.back.action.entity;

import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * 보충 한 번({@code record.supplement}, 05 2-3, B 테이블, V8). [보충하고 다시 확인 요청]에서 기록한 측이 덧붙인 설명이다(03 G). 수리
 * 건 하자·지불·점검 항목이 같이 쓰고, 대상 칸은 셋 중 하나만 채운다(DB CHECK).
 *
 * <p>고칠 수 없다: {@code @Immutable} + DB 트리거가 UPDATE·DELETE를 거절한다. 사진은 이 행에 칸이 없고 보충 행동의 payload
 * {@code photos}로 이어진다(#141). 다른 도메인에는 이 엔티티를 넘기지 않고 {@code SupplementWriter}로만 쓴다.
 */
@Entity
@Immutable
@Table(schema = "record", name = "supplement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplement implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  @Column(name = "inspection_item_id", updatable = false)
  private UUID inspectionItemId;

  /** 보충한 측(= 기록한 측, {@code AnswerSupport.rerequest}). */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private PartySide side;

  /** 덧붙인 설명. 사진만 보충했으면 null. */
  @Column(updatable = false)
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 저장하거나 읽어 온 뒤에는 false. ID를 앱이 정해서 {@code save}가 merge로 가지 않게 한다. */
  @Transient
  @Getter(AccessLevel.NONE)
  private boolean newRow = true;

  public Supplement(
      UUID id,
      UUID roomRecordId,
      UUID repairCaseId,
      UUID paymentId,
      UUID inspectionItemId,
      PartySide side,
      String description,
      Instant createdAt) {
    this.id = id;
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.paymentId = paymentId;
    this.inspectionItemId = inspectionItemId;
    this.side = side;
    this.description = description;
    this.createdAt = createdAt;
  }

  @Override
  public boolean isNew() {
    return newRow;
  }

  @PostPersist
  @PostLoad
  void markStored() {
    newRow = false;
  }
}
