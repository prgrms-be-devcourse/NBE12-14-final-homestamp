package com.chilsuitjo.back.action.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 호실 하나의 행동 체인 머리({@code state.action_chain_head}, 05 2-3, C 테이블, V10). 지금까지 쓴 가장 큰 {@code
 * room_seq}와 그 행동의 해시다. 그 행동이 탈퇴로 지워져도 그대로 두어, 다음 행동이 지운 번호를 다시 쓰지 않게 한다(05 2-10, #128).
 *
 * <p>행동 기록 저장({@code JpaActionWriter})만 읽고 쓴다. 바뀌는 칸은 {@code last_seq}·{@code last_hash}·{@code
 * version}뿐이다.
 */
@Entity
@Table(schema = "state", name = "action_chain_head")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionChainHead {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 지금까지 쓴 가장 큰 번호. 그 행동이 지워져도 내려가지 않는다. */
  @Column(name = "last_seq", nullable = false)
  private long lastSeq;

  /** {@code last_seq} 행동의 hash. 다음 행동의 {@code prev_hash}. 32바이트. */
  @Column(name = "last_hash", nullable = false)
  private byte[] lastHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /** 호실의 첫 행동을 쓸 때 만든다. */
  public ActionChainHead(UUID roomRecordId, long lastSeq, byte[] lastHash, Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.lastSeq = lastSeq;
    this.lastHash = lastHash.clone();
    this.createdAt = createdAt;
  }

  /** 다음 행동을 쓴 뒤 머리를 그 행동으로 옮긴다. 번호는 바로 다음 번호만 받는다. */
  public void advance(long seq, byte[] hash) {
    if (seq != lastSeq + 1) {
      throw new IllegalArgumentException("체인 머리는 다음 번호로만 옮겨요: " + lastSeq + " → " + seq);
    }
    this.lastSeq = seq;
    this.lastHash = hash.clone();
  }

  public byte[] getLastHash() {
    return lastHash.clone();
  }
}
