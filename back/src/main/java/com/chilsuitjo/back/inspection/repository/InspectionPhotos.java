package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 점검 항목에 사진을 붙인다({@code PhotoService.attach}, 09 2장 "inspection → photo"). 항목 더하기의 {@code
 * StateStore.save} 안에서, 그 행동과 같은 트랜잭션으로 부른다.
 *
 * <p>단계는 점검 종류를 따른다(입주 {@code MOVE_IN}, 퇴거 {@code MOVE_OUT}). 한도는 점검 하나 전체 {@code
 * photo.max.inspection}(06 0-1)이고, 지금은 그 점검의 항목 사진만 센다. 부위 사진({@code /part-photos})은 그 기능을 만들 때 셀
 * 대상에 더한다.
 */
@Component
public class InspectionPhotos {

  private final PhotoService photos;
  private final RoomAccessService access;
  private final ActionRequestContext requestContext;
  private final InspectionItemRepository items;
  private final int maxInspection;

  public InspectionPhotos(
      PhotoService photos,
      RoomAccessService access,
      ActionRequestContext requestContext,
      InspectionItemRepository items,
      @Value("${photo.max.inspection:40}") int maxInspection) {
    this.photos = Objects.requireNonNull(photos, "photos");
    this.access = Objects.requireNonNull(access, "access");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.items = Objects.requireNonNull(items, "items");
    this.maxInspection = maxInspection;
  }

  /**
   * 업로드 표를 항목 사진으로 붙인다. 표가 없으면 아무것도 하지 않는다. 실패하면 사진 번호와 함께 거절하고 그 행동 전체가 되돌려진다.
   *
   * @return 붙인 사진의 지문. {@code SavedState.withPhotos}로 넘긴다
   */
  List<PhotoDigest> attach(
      ActionCommand command,
      UUID inspectionId,
      InspectionKind kind,
      UUID itemId,
      List<String> uploadTickets) {
    if (uploadTickets.isEmpty()) {
      return List.of();
    }
    return photos.attach(
        new PhotoAttachCommand(
            access.require(command.roomRecordId(), command.actor().memberId()),
            new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, itemId),
            kind == InspectionKind.MOVE_IN ? PhotoStage.MOVE_IN : PhotoStage.MOVE_OUT,
            uploadTickets,
            quota(inspectionId),
            requestContext.at()));
  }

  /** 그 점검의 모든 항목 사진(방금 만든 항목 포함)을 센다. */
  private PhotoQuota quota(UUID inspectionId) {
    List<PhotoTarget> counted =
        items.findByInspectionIdOrderByIdAsc(inspectionId).stream()
            .<PhotoTarget>map(
                item -> new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()))
            .toList();
    return new PhotoQuota(maxInspection, counted);
  }
}
