package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 링크를 받은 측에게 보이는 점검 하나(#303, #395, 06 LINK-1a). 링크 열람자는 회원이 아니라서 회원 권한 검사 없이 읽는다.
 *
 * <p>회원 상세({@link InspectionDetail})와 따로 둔다: 누를 수 있는 행동·재요청 시각·나란히 보기(다른 기록인 수리 건이 섞임)·호실 ID·부위
 * ID·회원 식별값은 담지 않는다(LINK-1a "그 기록 하나만", 2a). 화면 문구는 만들지 않고 enum으로 주며, 문구는 링크 쪽이 만든다(08). 사진 주소도 만들지
 * 않고 대상만 준다. 주소는 링크 쪽이 {@code PhotoService.linkPhotos}로 받는다.
 *
 * @param kind 입주·퇴거 점검
 * @param status 이 측에게 보이는 점검 상태. 작성 중이면 이 값을 만들지 않으므로 확인 요청 또는 잠김이다
 * @param authorSide 점검 기준 측
 * @param late 입주일보다 늦게 시작한 점검(INSP-2a)
 * @param lockedAt 잠긴 시각. 보이는 상태가 잠김일 때만, 아니면 null(불변식 18)
 * @param lockReason 잠긴 이유. 보이는 상태가 잠김일 때만, 아니면 null
 * @param createdAt 점검을 시작한 시각
 * @param asOf 공유를 그만둔 뒤면 그 시각("○월 ○일 기준"), 아니면 null
 * @param items 이 측에게 보이는 항목. 더한 순서
 * @param history 이 측에게 보이는 이 점검과 항목의 행동. 시각 순({@code LinkRecordResponse.history}로 옮긴다)
 */
public record InspectionForLink(
    InspectionKind kind,
    InspectionStatus status,
    PartySide authorSide,
    boolean late,
    Instant lockedAt,
    LockReason lockReason,
    Instant createdAt,
    Instant asOf,
    List<Item> items,
    List<History> history) {

  public InspectionForLink {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(authorSide, "authorSide");
    Objects.requireNonNull(createdAt, "createdAt");
    if (status == InspectionStatus.DRAFT) {
      throw new IllegalArgumentException("작성 중 점검은 링크로 보이지 않아요");
    }
    items = List.copyOf(items);
    history = List.copyOf(history);
  }

  /**
   * 항목 하나. 상태와 판은 공유를 그만둔 시각 뒤 상대가 바꾼 것을 드러내지 않는다(불변식 18).
   *
   * @param id 항목 ID. 경과·사진을 항목에 잇는 데만 쓰고 링크 응답에는 내보내지 않는다
   * @param status 이 측에게 보이는 항목 응답
   * @param addedBySide 항목을 더한 측
   * @param addedAfterLock 잠긴 뒤 추가(INSP-1e)
   * @param addedAfterEnd 거주 종료 뒤 추가(03 H)
   * @param createdAt 항목을 더한 시각
   * @param versionNo 이 측에게 보이는 판 번호
   * @param partName 부위 이름(숨긴 부위도 지난 기록이면 보임)
   * @param description 이 측에게 보이는 판의 설명
   * @param photoTarget 이 항목의 사진 대상({@code INSPECTION_ITEM})
   */
  public record Item(
      UUID id,
      AckState status,
      PartySide addedBySide,
      boolean addedAfterLock,
      boolean addedAfterEnd,
      Instant createdAt,
      int versionNo,
      String partName,
      String description,
      PhotoTarget photoTarget) {}

  /**
   * 경과 한 줄({@code LinkRecordResponse.HistoryItem}의 {@code label}·{@code side}·{@code at}). 행동
   * 번호·해시·사유·회원 식별값은 담지 않는다(03 3-1).
   *
   * @param type 행동 종류. 링크 쪽이 문구({@code label})로 바꾼다
   * @param side 남긴 측. 서버 행동(잠금)이면 null
   * @param at 서버 시각
   * @param itemId 항목 행동이면 그 항목 ID(위 {@link Item#id}), 점검 행동이면 null
   */
  public record History(ActionType type, PartySide side, Instant at, UUID itemId) {}
}
