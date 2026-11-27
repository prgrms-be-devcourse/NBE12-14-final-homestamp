package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 수리 건 카드에 사진을 붙인다({@code PhotoService.attach}, 09 2장 "repair → photo"). 하자 등록·보충·진행 상황 남기기의 {@code
 * StateStore.save} 안에서, 그 행동과 같은 트랜잭션으로 부른다(06 4-5 "사진이 붙는 행동의 본문에 업로드 표").
 *
 * <p>하자 등록·보충 사진은 수리 건({@code REPAIR_CASE})에 붙고 단계는 {@code BEFORE}다(수리 전 모습, decisions 2026-11-24,
 * #167). 진행 상황 사진은 그 진행 상황({@code PROGRESS_NOTE})에 붙고 단계는 {@code PROGRESS}다(05 2-8). 한도는 수리 건 카드 전체
 * {@code photo.max.repair}(06 0-1)이고, 수리 건과 그 건의 진행 상황에 붙은 사진을 함께 센다. 원인 설명의 근거 사진은 그 설명({@code
 * CAUSE_NOTE})에 붙고 단계는 {@code BASIS}다(06 REP-8). 원인 설명 사진도 한도에 함께 센다. 고친 내용을 다시 보낼 때 더한 근거 사진은 새 고친
 * 내용({@code CORRECTION_REQUEST})에 붙고 단계는 보충과 같은 {@code BEFORE}다("고친 기록이 원래 쓰는 단계", #278). 이 사진도 한도에
 * 함께 센다(06 0-1 {@code photo.max.repair} "다시 요청 근거"). 지불 사진은 그 기능을 만들 때 {@link #quota}의 대상에 더한다.
 */
@Component
public class RepairPhotos {

  private final PhotoService photos;
  private final RoomAccessService access;
  private final ActionRequestContext requestContext;
  private final ProgressNoteRepository notes;
  private final CauseNoteRepository causeNotes;
  private final CorrectionReader corrections;
  private final int maxRepair;

  public RepairPhotos(
      PhotoService photos,
      RoomAccessService access,
      ActionRequestContext requestContext,
      ProgressNoteRepository notes,
      CauseNoteRepository causeNotes,
      CorrectionReader corrections,
      @Value("${photo.max.repair:30}") int maxRepair) {
    this.photos = Objects.requireNonNull(photos, "photos");
    this.access = Objects.requireNonNull(access, "access");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.notes = Objects.requireNonNull(notes, "notes");
    this.causeNotes = Objects.requireNonNull(causeNotes, "causeNotes");
    this.corrections = Objects.requireNonNull(corrections, "corrections");
    this.maxRepair = maxRepair;
  }

  /**
   * 업로드 표를 수리 건 사진으로 붙인다. 표가 없으면 아무것도 하지 않는다(사진 없는 보충). 실패하면 사진 번호와 함께 거절하고 그 행동 전체가 되돌려진다.
   *
   * @return 붙인 사진의 지문. {@code SavedState.withPhotos}로 넘긴다
   */
  List<PhotoDigest> attach(ActionCommand command, UUID repairCaseId, List<String> uploadTickets) {
    return attach(
        command,
        repairCaseId,
        new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, repairCaseId),
        PhotoStage.BEFORE,
        uploadTickets);
  }

  /**
   * 업로드 표를 진행 상황 사진으로 붙인다. 진행 상황 행은 먼저 DB에 내보내 있어야 한다(사진 행이 그 행을 가리킴). 그 밖에는 {@link #attach}와 같다.
   */
  List<PhotoDigest> attachToNote(
      ActionCommand command, UUID repairCaseId, UUID progressNoteId, List<String> uploadTickets) {
    return attach(
        command,
        repairCaseId,
        new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNoteId),
        PhotoStage.PROGRESS,
        uploadTickets);
  }

  /**
   * 업로드 표를 원인 설명의 근거 사진(소견서·견적서·현장 사진, 03 D-2 "근거 자료")으로 붙인다. 원인 설명 행은 먼저 DB에 내보내 있어야 한다. 그 밖에는
   * {@link #attach}와 같다.
   */
  List<PhotoDigest> attachToCause(
      ActionCommand command, UUID repairCaseId, UUID causeNoteId, List<String> uploadTickets) {
    return attach(
        command,
        repairCaseId,
        new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, causeNoteId),
        PhotoStage.BASIS,
        uploadTickets);
  }

  /**
   * 업로드 표를 다시 보낸 고친 내용의 근거 사진으로 붙인다({@code Correctable.attachEvidence}). 고친 내용 행은 먼저 DB에 내보내 있어야
   * 한다. 그 밖에는 {@link #attach}와 같다.
   */
  public List<PhotoDigest> attachToCorrection(
      ActionCommand command,
      UUID repairCaseId,
      UUID correctionRequestId,
      List<String> uploadTickets) {
    return attach(
        command,
        repairCaseId,
        new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, correctionRequestId),
        PhotoStage.BEFORE,
        uploadTickets);
  }

  private List<PhotoDigest> attach(
      ActionCommand command,
      UUID repairCaseId,
      PhotoTarget target,
      PhotoStage stage,
      List<String> uploadTickets) {
    if (uploadTickets.isEmpty()) {
      return List.of();
    }
    return photos.attach(
        new PhotoAttachCommand(
            access.require(command.roomRecordId(), command.actor().memberId()),
            target,
            stage,
            uploadTickets,
            quota(repairCaseId),
            requestContext.at()));
  }

  /** 수리 건 카드 전체: 수리 건과 그 건의 진행 상황·원인 설명(방금 만든 것 포함). */
  private PhotoQuota quota(UUID repairCaseId) {
    List<PhotoTarget> counted = new ArrayList<>();
    counted.add(new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, repairCaseId));
    for (UUID noteId : notes.findIdsByRepairCaseId(repairCaseId)) {
      counted.add(new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, noteId));
    }
    for (CauseNote note : causeNotes.findByRepairCaseIdOrderByCreatedAtAscIdAsc(repairCaseId)) {
      counted.add(new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, note.getId()));
    }
    for (UUID correctionId :
        corrections.idsByRecord(CorrectionTargetKind.REPAIR_CASE, repairCaseId)) {
      counted.add(new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, correctionId));
    }
    return new PhotoQuota(maxRepair, List.copyOf(counted));
  }
}
