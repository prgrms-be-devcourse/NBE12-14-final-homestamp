package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.service.OrphanCheckOutbox;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.entity.Photo;
import com.chilsuitjo.back.photo.entity.PhotoVisibility;
import com.chilsuitjo.back.photo.repository.PhotoCountRepository;
import com.chilsuitjo.back.photo.repository.PhotoObjectStore;
import com.chilsuitjo.back.photo.repository.PhotoObjectStore.ObjectChangedException;
import com.chilsuitjo.back.photo.repository.PhotoObjectStore.ObjectHead;
import com.chilsuitjo.back.photo.repository.PhotoRepository;
import com.chilsuitjo.back.photo.repository.PhotoTargetRepository;
import com.chilsuitjo.back.photo.repository.PhotoUrlSigner;
import com.chilsuitjo.back.photo.repository.PhotoVisibilityRepository;
import com.chilsuitjo.back.photo.rule.PhotoContentRules;
import com.chilsuitjo.back.photo.rule.PhotoSight;
import com.chilsuitjo.back.photo.rule.PhotoUploadFileRules;
import com.chilsuitjo.back.photo.rule.PhotoUrlRules;
import com.chilsuitjo.back.photo.rule.PhotoUrlRules.UrlPlan;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사진 창구의 실제 구현(#112). {@code attach}는 04 5-1 "제출 처리" 순서를 따른다.
 *
 * <pre>
 * 한도 → 준비(표 검사 → 파일 검사 → 확인 줄 커밋 → 조건부 복사) → 저장(카드 + 확인 줄 삭제) → 커밋 뒤 pending/ 삭제
 * </pre>
 *
 * <p><b>알려진 한계</b>: 지금은 모든 단계가 부르는 쪽(pipeline) 트랜잭션 안에서 돈다. 그래서 S3를 부르는 동안 호실 잠금과 DB 연결을 쥐고, 확인 줄은
 * 연결을 하나 더 써서({@code REQUIRES_NEW}) 먼저 커밋한다. 기본값을 켜기 전에 배포 환경에서 재 본 뒤 동시 검사와 트랜잭션 밖 준비로 고친다. 그때 옮기기
 * 쉽게 준비와 저장을 메서드로 나눠 두었다.
 *
 * <p>{@code photo.service=jpa}일 때만 뜬다. 기본값은 #275(트랜잭션 밖 준비)와 실제 S3 확인 뒤에 바꾼다.
 */
@Service
@ConditionalOnProperty(name = "photo.service", havingValue = "jpa")
public class JpaPhotoService implements PhotoService {

  private static final Logger log = LoggerFactory.getLogger(JpaPhotoService.class);
  private static final String PENDING = "pending/";
  private static final String PHOTOS = "photos/";
  private static final String JPEG = "image/jpeg";

  /**
   * 같은 대상 안의 표시 순서. {@code FakePhotoService}와 같다. 같은 {@code sortOrder}끼리는 ID로 정하는데, Postgres의 uuid
   * 비교(바이트 순)와 Java {@code UUID.compareTo}(부호 있는 long)가 달라서 SQL이 아니라 여기서 정렬한다.
   */
  private static final Comparator<Photo> PHOTO_ORDER =
      Comparator.comparingInt(Photo::getSortOrder).thenComparing(Photo::getId);

  private final PhotoUploadTicketSigner tickets;
  private final PhotoObjectStore files;
  private final PhotoRepository photos;
  private final PhotoVisibilityRepository visibilities;
  private final PhotoCountRepository counts;
  private final PhotoTargetRepository targetPhotos;
  private final PhotoUrlSigner urlSigner;
  private final OrphanCheckOutbox orphanChecks;
  private final Clock clock;
  private final Duration checkAfter;
  private final Duration linkUrlTtl;

  JpaPhotoService(
      PhotoUploadTicketSigner tickets,
      PhotoObjectStore files,
      PhotoRepository photos,
      PhotoVisibilityRepository visibilities,
      PhotoCountRepository counts,
      PhotoTargetRepository targetPhotos,
      PhotoUrlSigner urlSigner,
      OrphanCheckOutbox orphanChecks,
      Clock clock,
      @Value("${orphan.check-after}") Duration checkAfter,
      @Value("${photo.url.short}") Duration linkUrlTtl) {
    this.tickets = tickets;
    this.files = files;
    this.photos = photos;
    this.visibilities = visibilities;
    this.counts = counts;
    this.targetPhotos = targetPhotos;
    this.urlSigner = urlSigner;
    this.orphanChecks = orphanChecks;
    this.clock = clock;
    this.checkAfter = checkAfter;
    this.linkUrlTtl = linkUrlTtl;
  }

  /** 표에서 꺼낸 사진 한 장. {@code index}는 업로드 표 목록의 위치(0부터, {@code photoIndex}). */
  private record Pending(int index, PhotoUploadTicketSigner.Claims claims) {

    String photosOriginal() {
      return PHOTOS + claims.originalKey().substring(PENDING.length());
    }

    String photosThumb() {
      return PHOTOS + claims.thumbKey().substring(PENDING.length());
    }
  }

  /** 두 파일 검사를 통과한 사진. ETag는 조건부 복사에, 체크섬은 지문값에 쓴다. */
  private record Checked(Pending pending, ObjectHead original, ObjectHead thumb) {}

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<PhotoDigest> attach(PhotoAttachCommand command) {
    int size = command.uploadTickets().size();
    if (size < 1 || size > PhotoUploadFileRules.MAX_BATCH) {
      // 요청 DTO의 @Size가 먼저 막는다(06 4-5 VALIDATION_FAILED). 여기까지 왔으면 부르는 쪽 버그다
      throw new IllegalArgumentException("업로드 표는 1~10개여야 해요: " + size);
    }
    checkQuota(command);
    List<Checked> checked = prepare(command);
    return save(command, checked);
  }

  /** 대상 한도(decisions 2026-11-26 {@code PHOTO_LIMIT}). 다시 올려도 풀리지 않으니 사진 번호 없이, S3보다 먼저 거절한다. */
  private void checkQuota(PhotoAttachCommand command) {
    PhotoQuota quota = command.quota();
    RoomAccess uploader = command.uploader();
    int visible =
        counts.countVisible(
            uploader.roomRecordId(),
            uploader.side(),
            uploader.sharingStoppedAt(),
            quota.countedTargets());
    if ((long) visible + command.uploadTickets().size() > quota.max()) {
      throw new BusinessException(ErrorCode.PHOTO_LIMIT);
    }
  }

  // ---- 준비: 표 → 파일 검사 → 확인 줄 → 복사 ----

  /**
   * 실패하면 목록에서 가장 앞의 사진 번호로 거절한다(06 4-10). 표 검사는 로컬이라 먼저 하지만, 표가 틀린 사진(k번)보다 앞의 사진은 파일 검사까지 해야 그중 더
   * 앞의 실패를 찾는다. 그래서 파일 검사는 k번 앞의 사진에만 하고, 다 통과하면 k번을 알린다.
   */
  private List<Checked> prepare(PhotoAttachCommand command) {
    List<Pending> pending = new ArrayList<>();
    BusinessException ticketRejection = null;
    Set<String> seen = new HashSet<>();
    Instant now = clock.instant();
    for (int i = 0; i < command.uploadTickets().size(); i++) {
      TicketCheck result = checkTicket(command, i, now, seen);
      if (result.problem() != null) {
        ticketRejection = new BusinessException(result.problem(), i);
        break;
      }
      pending.add(result.pending());
    }

    List<Checked> checked = new ArrayList<>(pending.size());
    for (Pending photo : pending) {
      checked.add(inspect(photo));
    }
    if (ticketRejection != null) {
      throw ticketRejection;
    }

    // 복사보다 먼저 커밋한다(05 불변식 17): 복사와 카드 저장 사이에 멈추면 이 줄이 남은 파일을 지운다
    orphanChecks.schedule(
        checked.stream()
            .map(c ->
                new OrphanCheckOutbox.PhotoFiles(
                    c.pending().photosOriginal(), c.pending().photosThumb()))
            .toList(),
        now,
        now.plus(checkAfter));
    for (Checked photo : checked) {
      copy(photo);
    }
    return checked;
  }

  /** 표 한 장의 결과. 문제가 있으면 {@code problem}, 없으면 {@code pending}. */
  private record TicketCheck(ErrorCode problem, Pending pending) {}

  /** 서명·주인·호실·만료, 같은 요청 안의 중복, 이미 붙은 표. {@code seen}에는 통과한 표의 원본 키를 넣는다. */
  private TicketCheck checkTicket(
      PhotoAttachCommand command, int index, Instant now, Set<String> seen) {
    RoomAccess uploader = command.uploader();
    var claims = tickets.verify(command.uploadTickets().get(index)).orElse(null);
    if (claims == null
        || !claims.memberId().equals(uploader.memberId())
        || !claims.roomRecordId().equals(uploader.roomRecordId())
        || !inRoom(claims.originalKey(), uploader.roomRecordId())
        || !inRoom(claims.thumbKey(), uploader.roomRecordId())) {
      return new TicketCheck(ErrorCode.UPLOAD_TICKET_INVALID, null);
    }
    if (!claims.expiresAt().isAfter(now)) {
      return new TicketCheck(ErrorCode.UPLOAD_TICKET_EXPIRED, null);
    }
    Pending photo = new Pending(index, claims);
    // 같은 표는 한 번만 확정한다(04 5-1 UNIQUE(original_key)). S3보다 먼저 본다: 이미 붙은 표는 pending/이 지워져 있다
    if (seen.contains(claims.originalKey()) || photos.existsByOriginalKey(photo.photosOriginal())) {
      return new TicketCheck(ErrorCode.UPLOAD_TICKET_INVALID, null);
    }
    seen.add(claims.originalKey());
    return new TicketCheck(null, photo);
  }

  private static boolean inRoom(String key, UUID roomRecordId) {
    String prefix = PENDING + roomRecordId + "/";
    return key.startsWith(prefix) && key.length() > prefix.length();
  }

  /** 원본·썸네일 모두: 있는지, 형식·체크섬이 표와 같은지, 앞 64KB가 위치 정보 없는 JPEG인지(06 PHO-1a). */
  private Checked inspect(Pending photo) {
    ObjectHead original =
        inspectFile(photo, photo.claims().originalKey(), photo.claims().originalSha256());
    ObjectHead thumb = inspectFile(photo, photo.claims().thumbKey(), photo.claims().thumbSha256());
    return new Checked(photo, original, thumb);
  }

  private ObjectHead inspectFile(Pending photo, String key, String expectedSha256) {
    ObjectHead head = files.head(key).orElse(null);
    if (head == null
        || head.sizeBytes() <= 0
        || !JPEG.equals(head.contentType())
        || !expectedSha256.equals(head.sha256Base64())) {
      throw rejectFiles(photo);
    }
    byte[] start;
    try {
      start = files.readStart(key, head.eTag(), PhotoContentRules.CHECK_BYTES);
    } catch (ObjectChangedException e) {
      throw rejectFiles(photo);
    }
    if (!PhotoContentRules.isJpegWithoutLocation(start)) {
      throw rejectFiles(photo);
    }
    return head;
  }

  /** {@code x-amz-copy-source-if-match}: 검사 뒤 바꿔치기하면 S3가 412로 거절한다(04 5-1). */
  private void copy(Checked photo) {
    try {
      files.copy(
          photo.pending().claims().originalKey(),
          photo.pending().photosOriginal(),
          photo.original().eTag());
      files.copy(
          photo.pending().claims().thumbKey(), photo.pending().photosThumb(), photo.thumb().eTag());
    } catch (ObjectChangedException e) {
      throw rejectFiles(photo.pending());
    }
  }

  /** 두 파일을 지우고 그 사진 번호로 거절한다("다시 올려 주세요"). 이미 복사한 정식 파일은 확인 줄이 지운다. */
  private BusinessException rejectFiles(Pending photo) {
    deleteQuietly(List.of(photo.claims().originalKey(), photo.claims().thumbKey()));
    return new BusinessException(ErrorCode.PHOTO_CHECK_FAILED, photo.index());
  }

  // ---- 저장: 카드 + 확인 줄 삭제, 커밋 뒤 pending/ 삭제 ----

  private List<PhotoDigest> save(PhotoAttachCommand command, List<Checked> checked) {
    RoomAccess uploader = command.uploader();
    int order = counts.nextSortOrder(uploader.roomRecordId(), command.target());
    List<PhotoDigest> digests = new ArrayList<>(checked.size());
    List<String> pendingKeys = new ArrayList<>();
    for (Checked photo : checked) {
      Photo card = photos.save(card(command, photo, order));
      visibilities.save(
          new PhotoVisibility(
              card.getId(),
              uploader.roomRecordId(),
              command.at(),
              command.released() ? command.at() : null));
      digests.add(new PhotoDigest(card.getId(), card.getOriginalSha256(), order));
      order++;
      pendingKeys.add(photo.pending().claims().originalKey());
      pendingKeys.add(photo.pending().claims().thumbKey());
    }
    orphanChecks.clear(checked.stream().map(c -> c.pending().photosOriginal()).toList());
    afterCommit(() -> deleteQuietly(pendingKeys));
    return List.copyOf(digests);
  }

  private static Photo card(PhotoAttachCommand command, Checked photo, int sortOrder) {
    RoomAccess uploader = command.uploader();
    Pending pending = photo.pending();
    return switch (command.target()) {
      case PhotoTarget.Ref ref ->
          Photo.forTarget(
              uploader.roomRecordId(),
              ref.type(),
              ref.id(),
              null,
              command.stage(),
              uploader.side(),
              pending.claims().deviceTakenAt(),
              pending.photosOriginal(),
              pending.photosThumb(),
              hex(photo.original().sha256Base64()),
              hex(photo.thumb().sha256Base64()),
              photo.original().sizeBytes(),
              sortOrder,
              command.at());
      case PhotoTarget.InspectionPart part ->
          Photo.forInspection(
              uploader.roomRecordId(),
              part.inspectionId(),
              part.roomPartId(),
              null,
              command.stage(),
              uploader.side(),
              pending.claims().deviceTakenAt(),
              pending.photosOriginal(),
              pending.photosThumb(),
              hex(photo.original().sha256Base64()),
              hex(photo.thumb().sha256Base64()),
              photo.original().sizeBytes(),
              sortOrder,
              command.at());
    };
  }

  /** S3 체크섬(base64)을 64자 소문자 hex로(04 5-1 "지문값"). */
  private static String hex(String sha256Base64) {
    return HexFormat.of().formatHex(Base64.getDecoder().decode(sha256Base64));
  }

  /** 커밋된 뒤에만 지운다. 롤백되면 pending/은 그대로 두어 같은 표로 다시 붙일 수 있다. */
  private static void afterCommit(Runnable task) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            task.run();
          }
        });
  }

  /** 지우지 못해도 요청은 성공이다. 남은 pending/ 파일은 하루 뒤 정리된다(06 PHO-1e). */
  private void deleteQuietly(Collection<String> keys) {
    for (String key : keys) {
      try {
        files.delete(key);
      } catch (RuntimeException e) {
        log.warn("사진 임시 파일을 지우지 못했어요: {}", key, e);
      }
    }
  }

  /** 작성 중에 붙여 아직 상대에게 보이지 않은 사진을 행동 시각부터 보이게 한다(#295). 부르는 쪽 행동의 트랜잭션 안에서만 부른다. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(UUID roomRecordId, Collection<PhotoTarget> targets, Instant at) {
    targetPhotos.releaseByTargets(roomRecordId, targets, at);
  }

  // ---- 읽기: 회원 카드·링크 사진 ----
  //
  // 두 메서드는 같은 틀이다: 사진과 공개 상태를 한 번씩 읽고(read) → 보는 사람에 맞게 거르고 → 대상별로 묶는다(byTarget).
  // 다른 것은 거르는 조건과 한 장을 무엇으로 바꾸는지뿐이다.
  //   cards       회원. PhotoSight로 거르고, 링크에서 숨긴 사진도 남긴다. 주소 없음(화면이 view-urls로 받음)
  //   linkPhotos  링크 열람자. 아직 안 보인 사진과 링크에서 숨긴 사진을 빼고, 남은 사진에 5분 주소를 붙인다

  /**
   * 회원 화면 카드. 보이는 범위는 {@link PhotoSight}로 Java에서 거른다. view-urls와 같은 함수라 같은 규칙을 SQL에 또 적지 않는다.
   *
   * <p>상대의 작성 중 점검 사진은 {@code released_at}이 비어 있어 부르는 쪽이 그 대상을 넘겨도 빠진다(#295). 그 밖에 대상이 가려졌는지는 보지
   * 않으므로 부르는 쪽이 보는 사람에게 보이는 대상만 넘긴다({@code InspectionQueryService}는 {@code InspectionSight}로 항목을 거른
   * 뒤 부른다).
   *
   * <p>{@code availableActions}는 빈 목록이다. 사진 행동(PHO-3·5·9)을 만들 때 그 판단과 같은 함수로 채운다(decisions
   * 2026-11-24).
   */
  @Override
  @Transactional(readOnly = true)
  public Map<PhotoTarget, List<PhotoCard>> cards(
      RoomAccess viewer, Collection<PhotoTarget> targets) {
    Set<PhotoTarget> keys = new LinkedHashSet<>(targets);
    List<Stored> visible =
        read(viewer.roomRecordId(), keys).stream()
            .filter(stored ->
                PhotoSight.visibleTo(
                    stored.photo().getUploadedBySide(),
                    stored.state().getReleasedAt(),
                    viewer.side(),
                    viewer.sharingStoppedAt()))
            .toList();
    return byTarget(keys, visible, JpaPhotoService::card);
  }

  /**
   * 링크 열람 화면 사진. 링크에서 숨긴 사진과 아직 상대에게 보이지 않은 사진(작성 중 점검, 03 3-1 링크 열람자 ✕, #295)은 빼고, 남은 사진은 썸네일·원본
   * 모두 요청마다 새로 서명한 {@code photo.url.short} 주소에 {@code no-store}다({@link PhotoUrlRules#forLink}). 한
   * 번 부를 때의 주소는 모두 같은 서명 시각·만료다.
   *
   * <p>{@link PhotoSight}로 거르지 않는다: 공유를 그만두면 링크가 닫혀서(03 B "공유 그만두기") 링크 열람자에게 공유 중단 뒤의 사진이 갈 일이 없다.
   * 링크가 유효한지, 대상이 링크가 가리키는 기록의 것인지는 부르는 쪽(link)이 본다.
   *
   * <p><b>알려진 한계</b>: view-urls와 같다. 서명한 자격 증명이 10분 안에 끝나면 {@code IllegalStateException}(500)이다.
   */
  @Override
  @Transactional(readOnly = true)
  public Map<PhotoTarget, List<LinkPhoto>> linkPhotos(
      UUID roomRecordId, Collection<PhotoTarget> targets) {
    Set<PhotoTarget> keys = new LinkedHashSet<>(targets);
    List<Stored> shown =
        read(roomRecordId, keys).stream()
            .filter(stored ->
                stored.state().getReleasedAt() != null && !stored.state().isHiddenOnLink())
            .toList();
    // 지운 사진만 있으면 서명할 것이 없어 자격 증명도 읽지 않는다(PhotoViewService.needsUrl과 같음)
    UrlPlan plan =
        shown.stream().anyMatch(stored -> stored.erased() == null)
            ? PhotoUrlRules.forLink(
                clock.instant(), linkUrlTtl, urlSigner.credentialsExpireAt().orElse(null))
            : null;
    return byTarget(keys, shown, stored -> linkPhoto(stored, plan));
  }

  /** 읽은 사진 한 장과 그 공개 상태. 둘은 같은 트랜잭션에서 함께 만들어서({@link #save}) 늘 짝이 있다. */
  private record Stored(Photo photo, PhotoVisibility state) {

    /** 지운 사진이면 흔적(불변식 16), 아니면 null. {@code THIRD_PARTY}면 측은 {@link ErasedPhoto}가 지운다. */
    ErasedPhoto erased() {
      return state.getErasedAt() == null
          ? null
          : new ErasedPhoto(photo.getUploadedBySide(), state.getErasedAt(), state.getEraseReason());
    }
  }

  /**
   * 이 호실 기록에서 대상들에 붙은 사진과 공개 상태. 쿼리는 사진·공개 상태 한 번씩이다. 지운 사진·숨긴 사진·안 보이는 사진도 모두 담고, 거르는 것은 부르는 쪽이다.
   * {@link #PHOTO_ORDER} 순서다.
   */
  private List<Stored> read(UUID roomRecordId, Set<PhotoTarget> targets) {
    List<Photo> found = targetPhotos.findByTargets(roomRecordId, targets);
    if (found.isEmpty()) {
      return List.of();
    }
    Map<UUID, PhotoVisibility> states =
        visibilities.findAllById(found.stream().map(Photo::getId).toList()).stream()
            .collect(Collectors.toMap(PhotoVisibility::getPhotoId, Function.identity()));
    return found.stream()
        .sorted(PHOTO_ORDER)
        .map(photo -> {
          PhotoVisibility state = states.get(photo.getId());
          if (state == null) {
            // 사진 카드와 공개 상태는 같은 트랜잭션에서 함께 만든다(save). 없으면 만드는 쪽 버그다
            throw new IllegalStateException("사진 공개 상태가 없어요: " + photo.getId());
          }
          return new Stored(photo, state);
        })
        .toList();
  }

  /** 넘긴 대상마다 키를 두고(사진이 없으면 빈 목록), 순서를 지킨 채 대상별로 넣는다. {@code stored}는 {@link #read} 순서 그대로여야 한다. */
  private static <T> Map<PhotoTarget, List<T>> byTarget(
      Set<PhotoTarget> targets, List<Stored> stored, Function<Stored, T> item) {
    Map<PhotoTarget, List<T>> result = new LinkedHashMap<>();
    for (PhotoTarget target : targets) {
      result.put(target, new ArrayList<>());
    }
    for (Stored one : stored) {
      List<T> items = result.get(one.photo().target());
      if (items == null) {
        // 쿼리 조건(PhotoTargetSql)과 Photo.target()이 어긋났다는 뜻이다
        throw new IllegalStateException("넘기지 않은 대상의 사진을 읽었어요: " + one.photo().getId());
      }
      items.add(item.apply(one));
    }
    result.replaceAll((target, items) -> List.copyOf(items));
    return Map.copyOf(result);
  }

  private static PhotoCard card(Stored stored) {
    Photo photo = stored.photo();
    PhotoVisibility state = stored.state();
    return new PhotoCard(
        photo.getId(),
        photo.target(),
        photo.getStage(),
        photo.getUploadedBySide(),
        photo.getSortOrder(),
        photo.getCreatedAt(),
        photo.getDeviceTakenAt(),
        state.isHiddenOnLink(),
        state.getMarkedWrongAt(),
        photo.getRedactedFromPhotoId(),
        stored.erased(),
        List.of());
  }

  /** 지운 사진은 파일이 없어 어떤 주소도 만들지 않는다(불변식 16). 그때 {@code plan}은 쓰지 않는다. */
  private LinkPhoto linkPhoto(Stored stored, UrlPlan plan) {
    Photo photo = stored.photo();
    ErasedPhoto erased = stored.erased();
    if (erased != null) {
      return new LinkPhoto(
          photo.getId(), photo.target(), photo.getSortOrder(), null, null, null, erased);
    }
    return new LinkPhoto(
        photo.getId(),
        photo.target(),
        photo.getSortOrder(),
        sign(photo.getThumbKey(), plan),
        sign(photo.getOriginalKey(), plan),
        plan.expiresAt(),
        null);
  }

  private String sign(String key, UrlPlan plan) {
    return urlSigner.presignGet(key, plan.signedAt(), plan.expiresAt(), plan.cacheControl());
  }
}
