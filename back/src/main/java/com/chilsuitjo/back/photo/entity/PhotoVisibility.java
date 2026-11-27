package com.chilsuitjo.back.photo.entity;

import com.chilsuitjo.back.photo.rule.EraseReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사진 공개 상태({@code state.photo_visibility}, 05 2-8, C 테이블). 사진 하나에 한 행이고, 링크에서 숨김·잘못 올린 사진 표시·파일 지움을
 * 담는다. 사진 카드({@link Photo})는 고칠 수 없어서 바뀌는 칸을 여기에 둔다.
 *
 * <p>행동으로만 바뀐다: {@code HIDE_PHOTO}·{@code SHOW_PHOTO}(06 PHO-3), {@code MARK_PHOTO_WRONG}·{@code
 * UNMARK_PHOTO_WRONG}(PHO-9), {@code ERASE_PHOTO}(PHO-5·7), 그리고 점검 확인 요청({@code
 * REQUEST_INSPECTION_CONFIRM})이 작성 중에 붙인 사진의 {@code released_at}을 채운다(#295, {@code
 * PhotoService.release}). 바꾸는 메서드는 각 기능 PR에서 더한다. 그래서 새 행은 숨기지 않고 표시·지움도 없는 빈 상태로 시작한다. 숨김은 올린 뒤
 * {@code hide-on-link}로만 한다(06 API 표).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photo_visibility", schema = "state")
public class PhotoVisibility {
  /** {@link Photo#getId()}와 같다(PK이자 같은 호실의 사진을 가리키는 FK). */
  @Id
  @Column(name = "photo_id", updatable = false, nullable = false)
  private UUID photoId;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 링크(가입 전 사람) 화면에서만 숨긴다. 회원 화면에는 그대로 보인다(06 PHO-3a). */
  @Column(name = "hidden_on_link", nullable = false)
  private boolean hiddenOnLink;

  /** 잘못 올린 사진 표시. 지우지 않고 흐리게 뒤로 보내며, 올린 사람이 뗄 수 있다(06 PHO-9). */
  @Column(name = "marked_wrong_at")
  private Instant markedWrongAt;

  /**
   * 파일을 지운 시각. 한 번 채우면 되돌리지 않고, 채워진 사진에는 어떤 주소도 발급하지 않는다(불변식 16). {@link #eraseReason}과 함께 채운다(V1
   * CHECK).
   */
  @Column(name = "erased_at")
  private Instant erasedAt;

  @Column(name = "erase_reason", length = 20)
  @Enumerated(EnumType.STRING)
  private EraseReason eraseReason;

  /**
   * 상대에게 보이기 시작한 시각(#295). 보통 사진은 붙인 때, 기준 측이 작성 중 점검에 붙인 사진은 비워 두고 확인 요청 때 채운다. 비어 있으면 올린 측에게만
   * 보인다({@code PhotoSight}). 한 번 채우면 바꾸지 않는다.
   */
  @Column(name = "released_at")
  private Instant releasedAt;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private Long version;

  /** 붙인 때부터 상대에게 보이는 보통 사진. */
  public PhotoVisibility(UUID photoId, UUID roomRecordId, Instant createdAt) {
    this(photoId, roomRecordId, createdAt, createdAt);
  }

  /**
   * @param releasedAt 상대에게 보이기 시작한 시각. 아직 상대에게 보이지 않는 사진(작성 중 점검)이면 null
   */
  public PhotoVisibility(UUID photoId, UUID roomRecordId, Instant createdAt, Instant releasedAt) {
    if (photoId == null || roomRecordId == null || createdAt == null) {
      throw new IllegalArgumentException("필수 값이 누락되었습니다");
    }
    if (releasedAt != null && releasedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("붙이기 전에 보일 수는 없어요");
    }

    this.photoId = photoId;
    this.roomRecordId = roomRecordId;
    this.createdAt = createdAt;
    this.releasedAt = releasedAt;
  }
}
