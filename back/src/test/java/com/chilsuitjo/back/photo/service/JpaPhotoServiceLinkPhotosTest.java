package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.EraseReason;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 링크 열람 화면의 대상별 사진({@code JpaPhotoService.linkPhotos}). 링크에서 숨긴 사진은 빠지고(06 PHO-3a), 남은 사진은 썸네일·원본
 * 모두 요청마다 새 5분 주소에 {@code no-store}다(04 5-1 "보기"). 지운 사진은 주소 없이 흔적만 준다(불변식 16).
 *
 * <p>가짜 서명기라 주소에 서명 시각·만료·캐시가 그대로 적힌다. 시계는 {@link #NOW}(03:00:00.123456)로 고정이다.
 */
class JpaPhotoServiceLinkPhotosTest extends PhotoAttachTestSupport {

  private static final String FAKE = "https://fake-photo-storage.invalid/";
  private static final String LINK_URL_QUERY =
      "?signedAt=2026-11-26T03:00:00Z&expiresAt=2026-11-26T03:05:00Z&cacheControl=no-store";

  @Test
  void 썸네일과_원본_모두_지금_서명한_5분_주소에_no_store다() {
    UUID id = photo(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(60), 0);

    LinkPhoto photo = linkPhotos(List.of(caseTarget())).get(caseTarget()).getFirst();

    assertThat(photo.id()).isEqualTo(id);
    assertThat(photo.target()).isEqualTo(caseTarget());
    assertThat(photo.sortOrder()).isZero();
    assertThat(photo.thumbUrl())
        .isEqualTo(FAKE + "photos/" + room + "/" + id + "/thumb.jpg" + LINK_URL_QUERY);
    assertThat(photo.originalUrl())
        .isEqualTo(FAKE + "photos/" + room + "/" + id + "/original.jpg" + LINK_URL_QUERY);
    assertThat(photo.expiresAt()).isEqualTo(Instant.parse("2026-11-26T03:05:00Z"));
    assertThat(photo.erased()).isNull();
  }

  @Test
  void PHO_3a_링크에서_숨긴_사진은_링크에만_안_보이고_회원에게는_보인다() {
    UUID hidden = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 0);
    UUID shown = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(50), 1);
    jdbc.update(
        "UPDATE state.photo_visibility SET hidden_on_link = true WHERE photo_id = ?", hidden);

    List<LinkPhoto> link = linkPhotos(List.of(caseTarget())).get(caseTarget());
    List<PhotoCard> member = photos.cards(access(null), List.of(caseTarget())).get(caseTarget());

    assertThat(link).extracting(LinkPhoto::id).containsExactly(shown);
    assertThat(member).extracting(PhotoCard::id).containsExactly(hidden, shown);
  }

  @Test
  @Tag("invariant-16")
  void 지운_사진은_주소_없이_흔적만_준다() {
    Instant created = NOW.minusSeconds(60);
    UUID erased = existingPhoto(caseTarget(), PartySide.LANDLORD, created, true, 0);
    UUID thirdParty = existingPhoto(caseTarget(), PartySide.LANDLORD, created, true, 1);
    jdbc.update(
        "UPDATE state.photo_visibility SET erase_reason = 'THIRD_PARTY' WHERE photo_id = ?",
        thirdParty);

    List<LinkPhoto> link = linkPhotos(List.of(caseTarget())).get(caseTarget());

    assertThat(link).extracting(LinkPhoto::id).containsExactly(erased, thirdParty);
    LinkPhoto privacy = link.get(0);
    assertThat(privacy.thumbUrl()).isNull();
    assertThat(privacy.originalUrl()).isNull();
    assertThat(privacy.expiresAt()).isNull();
    assertThat(privacy.erased())
        .isEqualTo(
            new ErasedPhoto(PartySide.LANDLORD, created.plusSeconds(1), EraseReason.PRIVACY));
    assertThat(link.get(1).erased())
        .isEqualTo(new ErasedPhoto(null, created.plusSeconds(1), EraseReason.THIRD_PARTY));
  }

  @Test
  void 넘긴_대상마다_키가_있고_대상마다_표시_순서대로_준다() {
    UUID second = photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 1);
    UUID first = photo(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(50), 0);
    PhotoTarget.Ref note = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNote());
    PhotoTarget.Ref empty = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNote());
    UUID inNote = photo(note, PartySide.LANDLORD, NOW.minusSeconds(40), 0);

    Map<PhotoTarget, List<LinkPhoto>> link = linkPhotos(List.of(empty, note, caseTarget()));

    assertThat(link).containsOnlyKeys(caseTarget(), note, empty);
    assertThat(link.get(caseTarget())).extracting(LinkPhoto::id).containsExactly(first, second);
    assertThat(link.get(note)).extracting(LinkPhoto::id).containsExactly(inNote);
    assertThat(link.get(empty)).isEmpty();
  }

  @Test
  void 대상을_넘기지_않으면_빈_결과다() {
    photo(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), 0);

    assertThat(linkPhotos(List.of())).isEmpty();
  }

  @Test
  void 다른_호실의_사진은_읽지_않는다() {
    PhotoTarget.Ref firstRoomCase = caseTarget();
    photo(firstRoomCase, PartySide.TENANT, NOW.minusSeconds(60), 0);
    createsRoomWithRepairCase();

    assertThat(linkPhotos(List.of(firstRoomCase)).get(firstRoomCase)).isEmpty();
  }

  // ---- 준비 ----

  private Map<PhotoTarget, List<LinkPhoto>> linkPhotos(List<PhotoTarget> targets) {
    return photos.linkPhotos(room, targets);
  }

  private UUID photo(PhotoTarget.Ref target, PartySide side, Instant createdAt, int sortOrder) {
    return existingPhoto(target, side, createdAt, false, sortOrder);
  }
}
