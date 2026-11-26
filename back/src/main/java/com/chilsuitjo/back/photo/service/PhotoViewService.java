package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.PhotoUrlResponse;
import com.chilsuitjo.back.photo.dto.ViewUrlsRequest;
import com.chilsuitjo.back.photo.dto.ViewUrlsResponse;
import com.chilsuitjo.back.photo.entity.Photo;
import com.chilsuitjo.back.photo.entity.PhotoVisibility;
import com.chilsuitjo.back.photo.repository.PhotoRepository;
import com.chilsuitjo.back.photo.repository.PhotoUrlSigner;
import com.chilsuitjo.back.photo.repository.PhotoVisibilityRepository;
import com.chilsuitjo.back.photo.rule.PhotoSight;
import com.chilsuitjo.back.photo.rule.PhotoUrlRules;
import com.chilsuitjo.back.photo.rule.PhotoUrlRules.UrlPlan;
import com.chilsuitjo.back.photo.rule.PhotoVariant;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사진 주소 받기({@code POST /api/photos/view-urls}, 06 PHO-2·5·7). 화면만 부르는 입구라 다른 도메인의 창구({@link
 * PhotoService})에 두지 않는다. 사진 줄을 DB에서 직접 읽으므로 {@code photo.service=fake}로 붙인 사진(메모리)은 찾지 못한다.
 *
 * <pre>
 * 사진 읽기 → 한 호실인지 → 호실 회원인지(require) → 보이는지(PhotoSight) → 지운 사진은 흔적, 나머지는 서명
 * </pre>
 *
 * <p>볼 수 없는 사진(없는 ID, 다른 호실, 공유를 그만둔 뒤 상대가 올린 사진)이 한 장이라도 섞이면 요청 전체를 {@code NOT_FOUND}로 거절한다. 없는
 * 사진과 숨긴 사진이 같은 답이라 있는지 드러나지 않는다(ROOM-2a). 내려받기 묶음(#274)은 이보다 좁게 골라 이 거절에 걸리지 않는다(#246).
 */
@Service
public class PhotoViewService {

  private final PhotoRepository photos;
  private final PhotoVisibilityRepository visibilities;
  private final RoomAccessService access;
  private final PhotoUrlSigner signer;
  private final Clock clock;
  private final Duration memberTtl;
  private final Duration shortTtl;

  PhotoViewService(
      PhotoRepository photos,
      PhotoVisibilityRepository visibilities,
      RoomAccessService access,
      PhotoUrlSigner signer,
      Clock clock,
      @Value("${photo.url.member}") Duration memberTtl,
      @Value("${photo.url.short}") Duration shortTtl) {
    this.photos = photos;
    this.visibilities = visibilities;
    this.access = access;
    this.signer = signer;
    this.clock = clock;
    this.memberTtl = memberTtl;
    this.shortTtl = shortTtl;
  }

  /**
   * @return 요청한 순서대로 사진마다 주소 하나. 지운 사진은 주소 대신 흔적(불변식 16)
   */
  @Transactional(readOnly = true)
  public ViewUrlsResponse viewUrls(UUID memberId, ViewUrlsRequest request) {
    List<UUID> ids = request.photoIds();
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    Map<UUID, Photo> found =
        photos.findAllById(ids).stream()
            .collect(Collectors.toMap(Photo::getId, Function.identity()));
    if (found.size() != ids.size()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Set<UUID> rooms =
        found.values().stream().map(Photo::getRoomRecordId).collect(Collectors.toSet());
    if (rooms.size() != 1) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    RoomAccess viewer = access.require(rooms.iterator().next(), memberId);
    for (Photo photo : found.values()) {
      if (!PhotoSight.visibleTo(
          photo.getUploadedBySide(),
          photo.getCreatedAt(),
          viewer.side(),
          viewer.sharingStoppedAt())) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
    }

    Map<UUID, PhotoVisibility> states =
        visibilities.findAllById(ids).stream()
            .collect(Collectors.toMap(PhotoVisibility::getPhotoId, Function.identity()));
    UrlPlan plan = needsUrl(ids, states) ? plan(request.variant()) : null;
    return new ViewUrlsResponse(
        ids.stream()
            .map(id -> item(found.get(id), states.get(id), request.variant(), plan))
            .toList());
  }

  private static boolean needsUrl(List<UUID> ids, Map<UUID, PhotoVisibility> states) {
    return ids.stream()
        .anyMatch(id -> states.get(id) == null || states.get(id).getErasedAt() == null);
  }

  /** 한 요청의 주소는 모두 같은 서명 시각·만료다. 자격 증명 만료는 한 번만 읽는다. */
  private UrlPlan plan(PhotoVariant variant) {
    return PhotoUrlRules.forMember(
        variant, clock.instant(), memberTtl, shortTtl, signer.credentialsExpireAt().orElse(null));
  }

  private PhotoUrlResponse item(
      Photo photo, PhotoVisibility state, PhotoVariant variant, UrlPlan plan) {
    if (state == null) {
      // 사진 카드와 공개 상태는 같은 트랜잭션에서 함께 만든다(JpaPhotoService.save). 없으면 만드는 쪽 버그다
      throw new IllegalStateException("사진 공개 상태가 없어요: " + photo.getId());
    }
    if (state.getErasedAt() != null) {
      return new PhotoUrlResponse(
          photo.getId(),
          null,
          null,
          new ErasedPhoto(photo.getUploadedBySide(), state.getErasedAt(), state.getEraseReason()));
    }
    String key = variant == PhotoVariant.THUMB ? photo.getThumbKey() : photo.getOriginalKey();
    String url = signer.presignGet(key, plan.signedAt(), plan.expiresAt(), plan.cacheControl());
    return new PhotoUrlResponse(photo.getId(), url, plan.expiresAt(), null);
  }
}
