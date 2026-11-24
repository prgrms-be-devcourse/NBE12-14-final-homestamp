package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.EraseReason;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 이슈 110: 다른 도메인 테스트가 가짜로 사진을 붙일 수 있다. */
class FakePhotoServiceTest {

  private final Instant at = Instant.parse("2026-11-24T01:00:00Z");
  private final FakePhotoService service = new FakePhotoService(Clock.fixed(at, ZoneOffset.UTC));
  private final RoomAccess tenant = livingTenant();
  private final PhotoTarget repairCase =
      new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, UUID.randomUUID());

  @Test
  void 표_순서대로_0부터_순서를_받는다() {
    List<PhotoDigest> attached = service.attach(command(List.of("t1", "t2", "t3")));

    assertThat(attached).extracting(PhotoDigest::sortOrder).containsExactly(0, 1, 2);
    assertThat(attached).extracting(PhotoDigest::photoId).doesNotHaveDuplicates();
    assertThat(attached)
        .extracting(PhotoDigest::originalSha256)
        .allMatch(sha -> sha.matches("[0-9a-f]{64}"));
  }

  @Test
  void 같은_대상에_다시_붙이면_순서가_이어진다() {
    service.attach(command(List.of("t1", "t2")));

    List<PhotoDigest> more = service.attach(command(List.of("t3")));

    assertThat(more).extracting(PhotoDigest::sortOrder).containsExactly(2);
  }

  @Test
  void 행동_트랜잭션이_롤백되면_그때_붙인_사진만_지운다() {
    List<PhotoDigest> before = service.attach(command(List.of("before")));
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      service.attach(command(List.of("rolled-back")));
      assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase)).hasSize(2);

      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      }
    } finally {
      TransactionSynchronizationManager.clear();
    }

    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase))
        .extracting(PhotoCard::id)
        .containsExactly(before.getFirst().photoId());
    assertThat(service.attach(command(List.of("next"))))
        .extracting(PhotoDigest::sortOrder)
        .containsExactly(1);
  }

  @Test
  void 같은_표는_같은_지문이_나온다() {
    String first = service.attach(command(List.of("same"))).getFirst().originalSha256();
    String second = service.attach(command(List.of("same"))).getFirst().originalSha256();

    assertThat(first).isEqualTo(second);
  }

  @Test
  void 실패로_적어_둔_표가_있으면_그_코드로_거절하고_한_장도_붙이지_않는다() {
    service.failTicket("bad", ErrorCode.UPLOAD_TICKET_EXPIRED);

    assertThatThrownBy(() -> service.attach(command(List.of("ok", "bad"))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.UPLOAD_TICKET_EXPIRED);
              assertThat(e.photoIndex()).contains(1);
            });

    List<PhotoDigest> next = service.attach(command(List.of("ok")));
    assertThat(next).extracting(PhotoDigest::sortOrder).containsExactly(0);
  }

  @Test
  void 세_사진_오류_모두_첫_실패_사진의_목록_위치를_전달한다() {
    for (ErrorCode code :
        List.of(
            ErrorCode.UPLOAD_TICKET_INVALID,
            ErrorCode.UPLOAD_TICKET_EXPIRED,
            ErrorCode.PHOTO_CHECK_FAILED)) {
      service.failTicket("bad", code);

      assertThatThrownBy(() -> service.attach(command(List.of("bad", "bad"))))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(code);
                assertThat(e.photoIndex()).contains(0);
              });
      assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase)).isEmpty();
    }
  }

  @Test
  void 붙인_사진을_회원_카드와_링크로_읽는다() {
    List<PhotoDigest> attached = service.attach(command(List.of("t1", "t2")));

    List<PhotoCard> cards = service.cards(tenant, List.of(repairCase)).get(repairCase);
    assertThat(cards)
        .extracting(PhotoCard::id)
        .containsExactlyElementsOf(attached.stream().map(PhotoDigest::photoId).toList());
    assertThat(cards)
        .allSatisfy(card -> {
          assertThat(card.target()).isEqualTo(repairCase);
          assertThat(card.stage()).isEqualTo(PhotoStage.BEFORE);
          assertThat(card.uploadedBy()).isEqualTo(PartySide.TENANT);
          assertThat(card.createdAt()).isEqualTo(at);
        });

    List<LinkPhoto> links =
        service.linkPhotos(tenant.roomRecordId(), List.of(repairCase)).get(repairCase);
    assertThat(links)
        .extracting(LinkPhoto::id)
        .containsExactlyElementsOf(attached.stream().map(PhotoDigest::photoId).toList());
    assertThat(links)
        .allSatisfy(link -> {
          assertThat(link.target()).isEqualTo(repairCase);
          assertThat(link.thumbUrl()).contains(link.id().toString(), "/thumb");
          assertThat(link.originalUrl()).contains(link.id().toString(), "/original");
          assertThat(link.expiresAt()).isEqualTo(at.plusSeconds(300));
          assertThat(link.erased()).isNull();
        });
  }

  @Test
  void 사진이_없는_대상도_빈_목록으로_돌려준다() {
    PhotoTarget empty = new PhotoTarget.InspectionPart(UUID.randomUUID(), UUID.randomUUID());
    service.attach(command(List.of("t1")));
    List<PhotoTarget> targets = List.of(repairCase, empty, empty);

    assertThat(service.cards(tenant, targets))
        .containsOnlyKeys(repairCase, empty)
        .containsEntry(empty, List.of());
    assertThat(service.linkPhotos(tenant.roomRecordId(), targets))
        .containsOnlyKeys(repairCase, empty)
        .containsEntry(empty, List.of());
    assertThat(service.cards(tenant, List.of())).isEmpty();
    assertThat(service.linkPhotos(tenant.roomRecordId(), List.of())).isEmpty();
  }

  @Test
  void 다른_호실의_사진은_보이지_않고_순서도_섞이지_않는다() {
    RoomAccess other = livingTenant();
    service.attach(command(List.of("t1", "t2")));

    assertThat(service.cards(other, List.of(repairCase))).containsEntry(repairCase, List.of());
    assertThat(service.linkPhotos(other.roomRecordId(), List.of(repairCase)))
        .containsEntry(repairCase, List.of());

    List<PhotoDigest> attached =
        service.attach(
            new PhotoAttachCommand(
                other, repairCase, PhotoStage.BEFORE, List.of("other"), PhotoQuota.none(), at));
    assertThat(attached).extracting(PhotoDigest::sortOrder).containsExactly(0);
    assertThat(service.cards(other, List.of(repairCase)).get(repairCase))
        .extracting(PhotoCard::id)
        .containsExactly(attached.getFirst().photoId());
    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase)).hasSize(2);
  }

  @Test
  void 공유_중단_뒤_상대_사진만_양방향으로_제외한다() {
    PhotoCard before = card(0, PartySide.LANDLORD, at.minusSeconds(1), false, null);
    PhotoCard boundary = card(1, PartySide.LANDLORD, at, false, null);
    PhotoCard after = card(2, PartySide.LANDLORD, at.plusSeconds(1), false, null);
    PhotoCard mine = card(3, PartySide.TENANT, at.plusSeconds(1), false, null);
    for (PhotoCard card : List.of(before, boundary, after, mine)) {
      service.put(tenant.roomRecordId(), card);
    }

    assertThat(service.cards(viewer(PartySide.TENANT, at), List.of(repairCase)).get(repairCase))
        .containsExactly(before, boundary, mine);
    assertThat(service.cards(viewer(PartySide.LANDLORD, at), List.of(repairCase)).get(repairCase))
        .containsExactly(before, boundary, after);
    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase))
        .containsExactly(before, boundary, after, mine);
  }

  @Test
  void 링크에서_숨긴_사진도_회원에게는_보인다() {
    PhotoCard hidden = card(0, PartySide.TENANT, at, true, null);
    service.put(tenant.roomRecordId(), hidden);

    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase)).containsExactly(hidden);
    assertThat(service.linkPhotos(tenant.roomRecordId(), List.of(repairCase)))
        .containsEntry(repairCase, List.of());
  }

  @Test
  void 지운_사진은_흔적만_남고_주소와_만료는_없다() {
    ErasedPhoto erased = new ErasedPhoto(PartySide.TENANT, at, EraseReason.PRIVACY);
    PhotoCard card = card(0, PartySide.TENANT, at, false, erased);
    service.put(tenant.roomRecordId(), card);

    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase)).containsExactly(card);
    assertThat(service.linkPhotos(tenant.roomRecordId(), List.of(repairCase)).get(repairCase))
        .containsExactly(new LinkPhoto(card.id(), repairCase, 0, null, null, null, erased));
  }

  @Test
  void 다른_사람의_요청으로_지운_사진은_흔적에서_올린_측을_숨긴다() {
    ErasedPhoto erased = new ErasedPhoto(PartySide.TENANT, at, EraseReason.THIRD_PARTY);
    service.put(tenant.roomRecordId(), card(0, PartySide.TENANT, at, false, erased));

    assertThat(
            service.cards(tenant, List.of(repairCase)).get(repairCase).getFirst().erased().side())
        .isNull();
    assertThat(service.linkPhotos(tenant.roomRecordId(), List.of(repairCase)).get(repairCase))
        .singleElement()
        .satisfies(link -> assertThat(link.erased().side()).isNull());
  }

  @Test
  void 순서와_ID로_정렬하고_부위_대상을_구분한다() {
    UUID inspectionId = UUID.randomUUID();
    PhotoTarget part = new PhotoTarget.InspectionPart(inspectionId, UUID.randomUUID());
    PhotoTarget otherPart = new PhotoTarget.InspectionPart(inspectionId, UUID.randomUUID());
    PhotoCard first = card(4, PartySide.TENANT, at, false, null);
    PhotoCard second = card(4, PartySide.TENANT, at, false, null);
    PhotoCard last = card(8, PartySide.TENANT, at, false, null);
    for (PhotoCard card : List.of(last, second, first)) {
      service.put(tenant.roomRecordId(), card);
    }
    List<UUID> sortedIds = List.of(first.id(), second.id()).stream().sorted().toList();

    assertThat(service.cards(tenant, List.of(repairCase)).get(repairCase))
        .extracting(PhotoCard::id)
        .containsExactly(sortedIds.getFirst(), sortedIds.getLast(), last.id());
    assertThat(service.linkPhotos(tenant.roomRecordId(), List.of(repairCase)).get(repairCase))
        .extracting(LinkPhoto::id)
        .containsExactly(sortedIds.getFirst(), sortedIds.getLast(), last.id());
    assertThat(service.attach(command(List.of("next"))))
        .extracting(PhotoDigest::sortOrder)
        .containsExactly(9);

    service.attach(
        new PhotoAttachCommand(
            tenant, part, PhotoStage.BEFORE, List.of("part"), PhotoQuota.none(), at));
    assertThat(service.cards(tenant, List.of(part, otherPart)).get(part)).hasSize(1);
    assertThat(service.cards(tenant, List.of(part, otherPart))).containsEntry(otherPart, List.of());
  }

  @Test
  void 조회_결과를_바꿔_저장된_사진을_변경할_수_없다() {
    service.attach(command(List.of("t1")));
    Map<PhotoTarget, List<PhotoCard>> cards = service.cards(tenant, List.of(repairCase));
    Map<PhotoTarget, List<LinkPhoto>> links =
        service.linkPhotos(tenant.roomRecordId(), List.of(repairCase));

    assertThatThrownBy(cards::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> cards.get(repairCase).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(links::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> links.get(repairCase).clear())
        .isInstanceOf(UnsupportedOperationException.class);
    service.attach(command(List.of("t2")));
    assertThat(cards.get(repairCase)).hasSize(1);
    assertThat(links.get(repairCase)).hasSize(1);
  }

  private PhotoCard card(
      int order, PartySide side, Instant createdAt, boolean hidden, ErasedPhoto erased) {
    return new PhotoCard(
        UUID.randomUUID(),
        repairCase,
        PhotoStage.BEFORE,
        side,
        order,
        createdAt,
        null,
        hidden,
        null,
        null,
        erased,
        List.of());
  }

  /** 새 호실 기록에서 양쪽이 연결되어 거주 중인 세입자. */
  private static RoomAccess livingTenant() {
    return new RoomAccess(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        PartySide.TENANT,
        PartyStatus.CONNECTED,
        RoomStatus.LIVING,
        null,
        PartyStatus.CONNECTED,
        null);
  }

  private RoomAccess viewer(PartySide side, Instant stoppedAt) {
    return new RoomAccess(
        tenant.roomRecordId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        side,
        tenant.myStatus(),
        tenant.roomStatus(),
        null,
        tenant.counterpartStatus(),
        stoppedAt);
  }

  private PhotoAttachCommand command(List<String> tickets) {
    return new PhotoAttachCommand(
        tenant, repairCase, PhotoStage.BEFORE, tickets, PhotoQuota.none(), at);
  }
}
