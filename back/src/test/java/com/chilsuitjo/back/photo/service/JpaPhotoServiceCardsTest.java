package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.EraseReason;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 회원 화면의 대상별 사진 카드({@code JpaPhotoService.cards}). 보이는 범위는 {@code PhotoSight}와 같고(03 3-1), 지운 사진은
 * 흔적으로 남으며(불변식 16), 링크에서 숨긴 사진도 회원에게는 보인다(06 PHO-3a).
 */
class JpaPhotoServiceCardsTest extends PhotoAttachTestSupport {

  private static final Instant STOPPED = Instant.parse("2026-11-20T00:00:00Z");

  @Test
  void 붙인_사진을_그대로_카드로_돌려준다() {
    List<PhotoDigest> attached = attach(upload(), upload());

    List<PhotoCard> cards = photos.cards(tenantAccess(), List.of(caseTarget())).get(caseTarget());

    assertThat(cards).extracting(PhotoCard::id).containsExactly(ids(attached));
    PhotoCard first = cards.getFirst();
    assertThat(first.target()).isEqualTo(caseTarget());
    assertThat(first.stage()).isEqualTo(PhotoStage.BEFORE);
    assertThat(first.uploadedBy()).isEqualTo(PartySide.TENANT);
    assertThat(first.sortOrder()).isZero();
    assertThat(first.createdAt()).isEqualTo(NOW);
    assertThat(first.deviceTakenAt()).isEqualTo(DEVICE_TIME);
    assertThat(first.hiddenOnLink()).isFalse();
    assertThat(first.markedWrongAt()).isNull();
    assertThat(first.redactedFromPhotoId()).isNull();
    assertThat(first.erased()).isNull();
    assertThat(first.availableActions()).isEmpty();
  }

  @Test
  void 넘긴_대상마다_키가_있고_사진이_없는_대상은_빈_목록이다() {
    UUID inCase = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 0);
    PhotoTarget.Ref empty = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNote());

    Map<PhotoTarget, List<PhotoCard>> cards =
        photos.cards(tenantAccess(), List.of(caseTarget(), empty));

    assertThat(cards).containsOnlyKeys(caseTarget(), empty);
    assertThat(cards.get(caseTarget())).extracting(PhotoCard::id).containsExactly(inCase);
    assertThat(cards.get(empty)).isEmpty();
  }

  @Test
  void 대상마다_표시_순서대로_주고_대상끼리_섞지_않는다() {
    UUID third = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 2);
    UUID first = photo(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(50), 0);
    UUID second = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(40), 1);
    PhotoTarget.Ref note = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNote());
    UUID inNote = photo(note, PartySide.LANDLORD, NOW.minusSeconds(30), 0);

    Map<PhotoTarget, List<PhotoCard>> cards =
        photos.cards(tenantAccess(), List.of(note, caseTarget()));

    assertThat(cards.get(caseTarget()))
        .extracting(PhotoCard::id)
        .containsExactly(first, second, third);
    assertThat(cards.get(note)).extracting(PhotoCard::id).containsExactly(inNote);
    assertThat(cards.get(note).getFirst().target()).isEqualTo(note);
  }

  @Test
  void 대상을_넘기지_않으면_빈_결과다() {
    photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 0);

    assertThat(photos.cards(tenantAccess(), List.of())).isEmpty();
  }

  @Test
  void 다른_호실의_사진은_읽지_않는다() {
    PhotoTarget.Ref firstRoomCase = caseTarget();
    photo(firstRoomCase, PartySide.TENANT, NOW.minusSeconds(60), 0);
    createsRoomWithRepairCase();

    Map<PhotoTarget, List<PhotoCard>> cards = photos.cards(tenantAccess(), List.of(firstRoomCase));

    assertThat(cards.get(firstRoomCase)).isEmpty();
  }

  @Test
  @Tag("invariant-18")
  void 공유를_그만둔_뒤_상대가_올린_사진만_빠진다() {
    UUID before = photo(caseTarget(), PartySide.LANDLORD, STOPPED.minusSeconds(1), 0);
    UUID atStop = photo(caseTarget(), PartySide.LANDLORD, STOPPED, 1);
    photo(caseTarget(), PartySide.LANDLORD, STOPPED.plusSeconds(1), 2);
    UUID mine = photo(caseTarget(), PartySide.TENANT, STOPPED.plusSeconds(2), 3);
    RoomAccess stopped = access(STOPPED);

    List<PhotoCard> cards = photos.cards(stopped, List.of(caseTarget())).get(caseTarget());

    assertThat(cards).extracting(PhotoCard::id).containsExactly(before, atStop, mine);
  }

  @Test
  @Tag("invariant-16")
  void 지운_사진은_빠지지_않고_지운_측과_시각을_흔적으로_준다() {
    Instant created = NOW.minusSeconds(60);
    UUID erased = existingPhoto(caseTarget(), PartySide.LANDLORD, created, true, 0);
    UUID thirdParty = existingPhoto(caseTarget(), PartySide.LANDLORD, created, true, 1);
    jdbc.update(
        "UPDATE state.photo_visibility SET erase_reason = 'THIRD_PARTY' WHERE photo_id = ?",
        thirdParty);

    List<PhotoCard> cards = photos.cards(tenantAccess(), List.of(caseTarget())).get(caseTarget());

    assertThat(cards).extracting(PhotoCard::id).containsExactly(erased, thirdParty);
    assertThat(cards.get(0).erased())
        .isEqualTo(
            new ErasedPhoto(PartySide.LANDLORD, created.plusSeconds(1), EraseReason.PRIVACY));
    assertThat(cards.get(1).erased())
        .isEqualTo(new ErasedPhoto(null, created.plusSeconds(1), EraseReason.THIRD_PARTY));
  }

  @Test
  void 링크에서_숨긴_사진과_잘못_올린_사진도_회원에게는_그대로_보인다() {
    UUID hidden = photo(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(60), 0);
    UUID wrong = photo(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(50), 1);
    Instant markedAt = NOW.minusSeconds(10);
    jdbc.update(
        "UPDATE state.photo_visibility SET hidden_on_link = true WHERE photo_id = ?", hidden);
    jdbc.update(
        "UPDATE state.photo_visibility SET marked_wrong_at = ? WHERE photo_id = ?",
        Timestamp.from(markedAt),
        wrong);

    List<PhotoCard> cards = photos.cards(tenantAccess(), List.of(caseTarget())).get(caseTarget());

    assertThat(cards).extracting(PhotoCard::id).containsExactly(hidden, wrong);
    assertThat(cards.get(0).hiddenOnLink()).isTrue();
    assertThat(cards.get(1).markedWrongAt()).isEqualTo(markedAt);
  }

  // ---- 준비 ----

  private UUID photo(PhotoTarget.Ref target, PartySide side, Instant createdAt, int sortOrder) {
    return existingPhoto(target, side, createdAt, false, sortOrder);
  }

  private static UUID[] ids(List<PhotoDigest> digests) {
    return digests.stream().map(PhotoDigest::photoId).toArray(UUID[]::new);
  }
}
