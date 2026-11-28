package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.service.InspectionLinkQuery;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.payment.service.PaymentService;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import com.chilsuitjo.back.repair.service.RepairLinkQuery;
import com.chilsuitjo.back.room.dto.RoomForLink;
import com.chilsuitjo.back.room.dto.RoomRegion;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomLinkQuery;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 운영 링크 화면 값이 각 주인의 공개 조회를 부르는 방식(#303). */
class LinkContentsTest {

  private final RoomLinkQuery rooms = mock(RoomLinkQuery.class);
  private final InspectionLinkQuery inspections = mock(InspectionLinkQuery.class);
  private final RepairLinkQuery repairs = mock(RepairLinkQuery.class);
  private final PhotoService photos = mock(PhotoService.class);
  private final PaymentService payments = mock(PaymentService.class);
  private final LinkContents contents =
      new LinkContents(rooms, inspections, repairs, photos, payments, ZoneId.of("Asia/Seoul"));
  private final UUID room = UUID.randomUUID();
  private final UUID record = UUID.randomUUID();

  @Test
  void 호실은_room_조회의_시구동_받는_측_호실_상태로_옮긴다() {
    UUID party = UUID.randomUUID();
    when(rooms.forLink(room, party))
        .thenReturn(
            Optional.of(
                new RoomForLink(
                    PartySide.LANDLORD,
                    new RoomRegion("1168010100", "서울특별시", "강남구", "역삼동"),
                    PartyStatus.INVITED,
                    RoomStatus.LIVING,
                    false,
                    true)));

    assertThat(contents.room(room, party))
        .contains(
            new LinkRoom(
                PartySide.LANDLORD,
                "서울특별시",
                "강남구",
                "역삼동",
                false,
                true,
                PartyStatus.INVITED,
                RoomStatus.LIVING));
    assertThat(contents.room(room, UUID.randomUUID())).isEmpty();
  }

  @Test
  void 점검은_받는_측으로_읽고_보이는_항목의_사진_대상만_사진에_묻는다() {
    UUID item = UUID.randomUUID();
    PhotoTarget target = new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item);
    when(inspections.forLink(room, record, PartySide.LANDLORD))
        .thenReturn(
            Optional.of(
                new InspectionForLink(
                    InspectionKind.MOVE_IN,
                    InspectionStatus.REQUESTED,
                    PartySide.TENANT,
                    false,
                    null,
                    null,
                    Instant.EPOCH,
                    null,
                    List.of(
                        new InspectionForLink.Item(
                            item,
                            AckState.PENDING,
                            PartySide.TENANT,
                            false,
                            false,
                            Instant.EPOCH,
                            1,
                            "타일",
                            "금",
                            target)),
                    List.of())));
    when(photos.linkPhotos(room, List.of(target))).thenReturn(Map.of(target, List.of()));

    var shown = contents.record(room, TargetKind.INSPECTION, record, PartySide.LANDLORD);

    assertThat(shown).hasValueSatisfying(r -> assertThat(r.items()).hasSize(1));
    verify(photos).linkPhotos(room, List.of(target));
  }

  @Test
  void 받는_측에게_보이지_않는_점검은_빈_값이고_사진을_묻지_않는다() {
    when(inspections.forLink(room, record, PartySide.TENANT)).thenReturn(Optional.empty());

    assertThat(contents.record(room, TargetKind.INSPECTION, record, PartySide.TENANT)).isEmpty();
    verify(photos, never()).linkPhotos(any(), any());
  }

  @Test
  void 점검_공개_상태를_되살리지_못한_예외는_잡지_않는다() {
    when(inspections.forLink(room, record, PartySide.LANDLORD))
        .thenThrow(new IllegalStateException("기준 시각의 항목 상태를 되살리지 못함"));

    assertThatThrownBy(
            () -> contents.record(room, TargetKind.INSPECTION, record, PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("되살리지");
  }

  @Test
  void 수리_건은_repair_조회를_받는_측으로_부르고_보이지_않으면_빈_값이다() {
    when(repairs.forLink(room, record, PartySide.LANDLORD)).thenReturn(Optional.empty());

    assertThat(contents.record(room, TargetKind.REPAIR_CASE, record, PartySide.LANDLORD)).isEmpty();
    verify(repairs).forLink(room, record, PartySide.LANDLORD);
    verifyNoInteractions(photos);
  }

  @Test
  void 수리_건_사진은_보이는_대상으로만_photo에서_받는다() {
    PhotoTarget target = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, record);
    when(repairs.forLink(room, record, PartySide.LANDLORD))
        .thenReturn(
            Optional.of(
                new RepairLinkView(
                    PartySide.TENANT,
                    RepairStatus.NOT_STARTED,
                    null,
                    null,
                    AckState.PENDING,
                    Instant.parse("2026-11-20T01:00:00Z"),
                    null,
                    "세면대",
                    "물이 새요",
                    null,
                    target,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of())));
    when(photos.linkPhotos(room, List.of(target))).thenReturn(Map.of(target, List.of()));

    LinkRecordResponse response =
        contents.record(room, TargetKind.REPAIR_CASE, record, PartySide.LANDLORD).orElseThrow();

    assertThat(response.targetKind()).isEqualTo(TargetKind.REPAIR_CASE);
    assertThat(response.items()).isEmpty();
    verify(photos).linkPhotos(room, List.of(target));
  }

  @Test
  void 수리_건_공개_상태를_되살리지_못한_예외는_잡지_않는다() {
    when(repairs.forLink(room, record, PartySide.LANDLORD))
        .thenThrow(new IllegalStateException("보이는 판이 없어요"));

    assertThatThrownBy(
            () -> contents.record(room, TargetKind.REPAIR_CASE, record, PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("판");
  }
}
