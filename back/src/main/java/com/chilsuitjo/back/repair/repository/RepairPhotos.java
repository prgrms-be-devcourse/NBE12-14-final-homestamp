package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.service.ActionRequestContext;
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
 * 수리 건에 사진을 붙인다({@code PhotoService.attach}, 09 2장 "repair → photo"). 하자 등록·보충의 {@code
 * StateStore.save} 안에서, 그 행동과 같은 트랜잭션으로 부른다(06 4-5 "사진이 붙는 행동의 본문에 업로드 표").
 *
 * <p>하자 등록·보충 사진은 수리 건({@code REPAIR_CASE})에 붙고 단계는 {@code BEFORE}다(수리 전 모습, decisions 2026-11-24,
 * #167). 한도는 수리 건 카드 전체 {@code photo.max.repair}(06 0-1)이고, 지금은 수리 건에 붙은 사진만 센다. 진행 상황·원인 설명·지불 사진은
 * 그 기능을 만들 때 {@link #quota}의 대상에 더한다.
 */
@Component
public class RepairPhotos {

  private final PhotoService photos;
  private final RoomAccessService access;
  private final ActionRequestContext requestContext;
  private final int maxRepair;

  public RepairPhotos(
      PhotoService photos,
      RoomAccessService access,
      ActionRequestContext requestContext,
      @Value("${photo.max.repair:30}") int maxRepair) {
    this.photos = Objects.requireNonNull(photos, "photos");
    this.access = Objects.requireNonNull(access, "access");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.maxRepair = maxRepair;
  }

  /**
   * 업로드 표를 수리 건 사진으로 붙인다. 표가 없으면 아무것도 하지 않는다(사진 없는 보충). 실패하면 사진 번호와 함께 거절하고 그 행동 전체가 되돌려진다.
   *
   * @return 붙인 사진의 지문. {@code SavedState.withPhotos}로 넘긴다
   */
  List<PhotoDigest> attach(ActionCommand command, UUID repairCaseId, List<String> uploadTickets) {
    if (uploadTickets.isEmpty()) {
      return List.of();
    }
    PhotoTarget target = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, repairCaseId);
    return photos.attach(
        new PhotoAttachCommand(
            access.require(command.roomRecordId(), command.actor().memberId()),
            target,
            PhotoStage.BEFORE,
            uploadTickets,
            quota(target),
            requestContext.at()));
  }

  private PhotoQuota quota(PhotoTarget repairCase) {
    return new PhotoQuota(maxRepair, List.of(repairCase));
  }
}
