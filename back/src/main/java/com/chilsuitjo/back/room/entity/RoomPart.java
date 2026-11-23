package com.chilsuitjo.back.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 호실 부위({@code state.room_part}, 05 2-2, C 테이블). 지우지 않고 숨기기만 한다(03 H "부위").
 *
 * <p>기본 부위는 호실을 만들 때 템플릿에서 복사한다. 템플릿을 나중에 바꿔도 이미 만든 호실의 부위는 그대로다.
 */
@Entity
@Table(schema = "state", name = "room_part")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomPart {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 복사해 온 템플릿. 직접 더한 부위면 null. */
  @Column(name = "template_id", updatable = false)
  private UUID templateId;

  @Column(nullable = false, length = 30, updatable = false)
  private String zone;

  @Column(nullable = false, length = 50, updatable = false)
  private String name;

  @Column(name = "sort_order", nullable = false, updatable = false)
  private int sortOrder;

  /** 설비·가전처럼 호실마다 켜고 끄는 부위의 켬 여부. 숨김과는 다르다. */
  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "hidden_at")
  private Instant hiddenAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /** 템플릿을 이 호실의 부위로 복사한다. 켬 상태로 시작한다(V1 기본값과 같음). */
  public static RoomPart copyOf(UUID roomRecordId, PartTemplate template, Instant at) {
    RoomPart part = new RoomPart();
    part.roomRecordId = Objects.requireNonNull(roomRecordId);
    part.templateId = template.getId();
    part.zone = template.getZone();
    part.name = template.getName();
    part.sortOrder = template.getSortOrder();
    part.enabled = true;
    part.createdAt = Objects.requireNonNull(at);
    return part;
  }
}
