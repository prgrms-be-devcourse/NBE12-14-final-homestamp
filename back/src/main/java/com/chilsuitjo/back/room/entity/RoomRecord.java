package com.chilsuitjo.back.room.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.RoomStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 호실 기록({@code state.room_record}, 05 2-2, C 테이블). 한 거주 기간의 기록을 모은다.
 *
 * <p>주소는 법정동 코드 하나와 별칭만 둔다(06 0-1 "주소 저장 범위"). 상태·기한·version 말고는 만들 때 한 번만 쓴다(05 0장). 예외: 비어 있던
 * 입주일은 입주 점검 시작이 한 번 채우고({@link #fillMoveInDate}), 상대가 처음 연결한 시각은 연결이 한 번 채운다({@link
 * #markCounterpartConnected}). 지금은 호실 만들기와 권한 검사에 쓰는 칸만 옮겼다. 기한 칸(자동 마감·삭제·무활동)은 그 기능을 만들 때 더한다.
 */
@Entity
@Table(schema = "state", name = "room_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomRecord {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  /** 법정동 코드(10자리). 이름은 {@code region}에서 읽는다. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "region_code", nullable = false, length = 10)
  private String regionCode;

  @Column(nullable = false, length = 50)
  private String alias;

  /** 입주일. 비워 둘 수 있고 입주 점검을 시작할 때 묻는다(06 ROOM-1a, INSP-2b). */
  @Column(name = "move_in_date")
  private LocalDate moveInDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private RoomStatus status;

  /** 만든 사람이 고른 측(03 A "호실 기록 만들기"). */
  @Enumerated(EnumType.STRING)
  @Column(name = "created_by_side", nullable = false, length = 10)
  private PartySide createdBySide;

  /** 합류 전 기록이면 합류한 호실 기록(03 B "같은 집 기록이 있을 때"). */
  @Column(name = "joined_into_id")
  private UUID joinedIntoId;

  /**
   * 상대가 처음 연결한 시각(06 ROOM-5, 05 {@code room_record}). 처음 연결할 때 한 번만 채운다. 비어 있으면 탈퇴 때 통째
   * 삭제한다(KEEP-4).
   */
  @Column(name = "counterpart_first_connected_at")
  private Instant counterpartFirstConnectedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /** 거주 중인 새 호실 기록. */
  public RoomRecord(
      String regionCode,
      String alias,
      LocalDate moveInDate,
      PartySide createdBySide,
      Instant createdAt) {
    this.regionCode = Objects.requireNonNull(regionCode);
    this.alias = Objects.requireNonNull(alias);
    this.moveInDate = moveInDate;
    this.createdBySide = Objects.requireNonNull(createdBySide);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.status = RoomStatus.LIVING;
  }

  /**
   * 비어 있는 입주일을 채운다(입주 점검 시작, 06 4-7·INSP-2b). 이미 있는 입주일을 바꾸는 것은 고치기라(03 G) 이 메서드로 하지 않는다: 이미 있으면
   * 부르는 쪽 버그라 {@code IllegalStateException}이다.
   */
  public void fillMoveInDate(LocalDate date) {
    if (moveInDate != null) {
      throw new IllegalStateException("이미 입주일이 있는 호실이에요: " + id);
    }
    this.moveInDate = Objects.requireNonNull(date, "date");
  }

  /**
   * 상대가 연결했다(06 ROOM-5). 처음 연결한 시각만 남기고, 이미 있으면 그대로 둔다(그 뒤 다시 연결해도 처음 시각이 기준).
   *
   * @param at 연결 행동의 시각
   */
  public void markCounterpartConnected(Instant at) {
    if (counterpartFirstConnectedAt == null) {
      this.counterpartFirstConnectedAt = Objects.requireNonNull(at, "at");
    }
  }

  /** 정산 완료(자동 마감 포함). 호실 단위 거절({@code SETTLED})과 링크용 조회가 같은 기준으로 본다. */
  public boolean isSettled() {
    return status == RoomStatus.SETTLED;
  }

  /** 합류 전 기록(03 B "같은 집 기록이 있을 때"). 호실 단위 거절({@code JOINED_RECORD})과 링크용 조회가 같은 기준으로 본다. */
  public boolean isJoinedAway() {
    return joinedIntoId != null;
  }
}
