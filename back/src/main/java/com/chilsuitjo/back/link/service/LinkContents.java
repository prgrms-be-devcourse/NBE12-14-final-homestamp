package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.service.InspectionLinkQuery;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.link.rule.InspectionLinkRecord;
import com.chilsuitjo.back.payment.service.PaymentService;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.room.dto.RoomForLink;
import com.chilsuitjo.back.room.service.RoomLinkQuery;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 링크 화면 값의 운영 구현. 칸마다 그 주인의 공개 조회를 부른다(#303 칸별 전환 7210814659).
 *
 * <ul>
 *   <li>호실: room {@link RoomLinkQuery#forLink}(#358). 받는 자리·호실 상태를 그대로 옮기고, 링크 종류에 따라 열지는 {@link
 *       LinkViewService}가 정한다
 *   <li>점검: inspection {@link InspectionLinkQuery#forLink}(#397)와 사진 {@link
 *       PhotoService#linkPhotos}. 보이는 범위(작성 중 제외, 공유 중단 기준 시각, 연결 전 열람자)는 inspection이 정하고, 숨긴 사진은
 *       photo가 뺀다. link는 다시 판단하지 않는다
 *   <li>수리 건: repair의 링크용 조회(#303 합의 7206508708)가 아직 없다. 빈 값으로 덮으면 "그런 기록 없음"과 구별되지 않아 {@code
 *       IllegalStateException}이다. 운영에는 아직 수리 건 링크를 발급하는 곳이 없다
 *   <li>돈 기록이 있는지: payment {@link PaymentService#hasMoney}. 운영 빈은 아직 가짜라 늘 {@code false}다. 링크 열람을
 *       켜기 전에 #340이 필요하다(#303 7210814659)
 * </ul>
 *
 * <p>주인 조회가 던진 예외(공개 상태를 되살리지 못함 등)는 잡지 않는다. {@link LinkViewService}는 업무 오류만 {@code LINK_INVALID}로
 * 덮어서 이런 예외는 5xx가 되고, 지금 행 값으로 대신 보여 주지 않는다.
 */
@Service
public class LinkContents implements LinkContentSource {

  private final RoomLinkQuery rooms;
  private final InspectionLinkQuery inspections;
  private final PhotoService photos;
  private final PaymentService payments;
  private final ZoneId zone;

  public LinkContents(
      RoomLinkQuery rooms,
      InspectionLinkQuery inspections,
      PhotoService photos,
      PaymentService payments,
      @Value("${app.zone}") ZoneId zone) {
    this.rooms = rooms;
    this.inspections = inspections;
    this.photos = photos;
    this.payments = payments;
    this.zone = zone;
  }

  @Override
  public Optional<LinkRoom> room(UUID roomRecordId, UUID partyId) {
    return rooms.forLink(roomRecordId, partyId).map(LinkContents::linkRoom);
  }

  @Override
  public Optional<LinkRecordResponse> record(
      UUID roomRecordId, TargetKind kind, UUID recordId, PartySide viewerSide) {
    Objects.requireNonNull(viewerSide, "viewerSide");
    return switch (kind) {
      case INSPECTION ->
          inspections
              .forLink(roomRecordId, recordId, viewerSide)
              .map(inspection -> inspection(roomRecordId, inspection, viewerSide));
      case REPAIR_CASE -> throw new IllegalStateException("수리 건 링크 조회(repair)가 아직 없어요(#303)");
      default -> throw new IllegalArgumentException("링크로 공유하는 기록이 아니에요: " + kind);
    };
  }

  @Override
  public boolean hasMoney(UUID roomRecordId, UUID repairCaseId) {
    return payments.hasMoney(roomRecordId, repairCaseId);
  }

  private LinkRecordResponse inspection(
      UUID roomRecordId, InspectionForLink inspection, PartySide viewerSide) {
    return InspectionLinkRecord.of(
        inspection,
        photos.linkPhotos(roomRecordId, InspectionLinkRecord.photoTargets(inspection)),
        zone,
        viewerSide);
  }

  private static LinkRoom linkRoom(RoomForLink room) {
    return new LinkRoom(
        room.recipientSide(),
        room.region().sido(),
        room.region().sigungu(),
        room.region().dong(),
        room.settled(),
        room.joinedAway(),
        room.recipientStatus(),
        room.roomStatus());
  }
}
