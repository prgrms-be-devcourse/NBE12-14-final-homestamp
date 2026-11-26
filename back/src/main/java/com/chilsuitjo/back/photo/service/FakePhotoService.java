package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사진 창구 가짜(이슈 110). 업로드 표를 검사하지 않고 메모리에 사진 카드만 만든다.
 *
 * <p>트랜잭션 안에서 붙인 사진은 롤백 때 메모리에서도 지운다. 실제 구현(검사·S3 복사·주소, #112)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link
 * PhotoService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakePhotoService implements PhotoService {

  private static final Comparator<PhotoCard> CARD_ORDER =
      Comparator.comparingInt(PhotoCard::sortOrder).thenComparing(PhotoCard::id);

  private final Clock clock;
  private final Map<PhotoTarget, List<Stored>> photos = new ConcurrentHashMap<>();
  private final Map<String, ErrorCode> failingTickets = new ConcurrentHashMap<>();

  public FakePhotoService() {
    this(Clock.systemUTC());
  }

  @Autowired
  public FakePhotoService(Clock clock) {
    this.clock = clock;
  }

  /**
   * 업로드 표 목록 순서대로 사진 카드를 만든다. 같은 대상에 이미 사진이 있으면 그 뒤로 순서를 이어 붙인다. 한도({@code quota})와 한 번에 1~10장 장수는
   * 검사하지 않는다(실제 검사는 #112).
   *
   * <p>{@link #failTicket}으로 적어 둔 표가 하나라도 있으면 한 장도 붙이지 않고 그 코드와 0부터 시작하는 사진 번호로 거절한다.
   */
  @Override
  public synchronized List<PhotoDigest> attach(PhotoAttachCommand command) {
    for (int i = 0; i < command.uploadTickets().size(); i++) {
      ErrorCode code = failingTickets.get(command.uploadTickets().get(i));
      if (code != null) {
        throw new BusinessException(code, i);
      }
    }

    List<Stored> stored = photos.computeIfAbsent(command.target(), target -> new ArrayList<>());
    int nextOrder =
        stored.stream()
                .filter(photo -> photo.roomRecordId().equals(command.uploader().roomRecordId()))
                .mapToInt(photo -> photo.card().sortOrder())
                .max()
                .orElse(-1)
            + 1;
    List<PhotoDigest> attached = new ArrayList<>();
    for (String ticket : command.uploadTickets()) {
      PhotoCard card =
          new PhotoCard(
              UUID.randomUUID(),
              command.target(),
              command.stage(),
              command.uploader().side(),
              nextOrder,
              command.at(),
              null,
              false,
              null,
              null,
              null,
              List.of());
      String originalSha256 = fakeSha256(ticket);
      stored.add(new Stored(command.uploader().roomRecordId(), card));
      attached.add(new PhotoDigest(card.id(), originalSha256, nextOrder));
      nextOrder++;
    }
    if (!attached.isEmpty()
        && TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      List<UUID> addedIds = attached.stream().map(PhotoDigest::photoId).toList();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status == STATUS_ROLLED_BACK) {
                synchronized (FakePhotoService.this) {
                  stored.removeIf(photo -> addedIds.contains(photo.card().id()));
                  if (stored.isEmpty()) {
                    photos.remove(command.target(), stored);
                  }
                }
              }
            }
          });
    }
    return List.copyOf(attached);
  }

  @Override
  public synchronized Map<PhotoTarget, List<PhotoCard>> cards(
      RoomAccess viewer, Collection<PhotoTarget> targets) {
    Map<PhotoTarget, List<PhotoCard>> result = new LinkedHashMap<>();
    for (PhotoTarget target : targets) {
      List<PhotoCard> cards =
          roomCards(viewer.roomRecordId(), target).stream()
              .filter(card ->
                  card.uploadedBy() == viewer.side()
                      || viewer.sharingStoppedAt() == null
                      || !card.createdAt().isAfter(viewer.sharingStoppedAt()))
              .toList();
      result.put(target, cards);
    }
    return Map.copyOf(result);
  }

  @Override
  public synchronized Map<PhotoTarget, List<LinkPhoto>> linkPhotos(
      UUID roomRecordId, Collection<PhotoTarget> targets) {
    Instant expiresAt = clock.instant().plus(Duration.ofMinutes(5));
    Map<PhotoTarget, List<LinkPhoto>> result = new LinkedHashMap<>();
    for (PhotoTarget target : targets) {
      List<LinkPhoto> links =
          roomCards(roomRecordId, target).stream()
              .filter(card -> !card.hiddenOnLink())
              .map(card ->
                  new LinkPhoto(
                      card.id(),
                      card.target(),
                      card.sortOrder(),
                      card.erased() == null ? fakeUrl(card.id(), "thumb", expiresAt) : null,
                      card.erased() == null ? fakeUrl(card.id(), "original", expiresAt) : null,
                      card.erased() == null ? expiresAt : null,
                      card.erased()))
              .toList();
      result.put(target, links);
    }
    return Map.copyOf(result);
  }

  /** 테스트용: 숨김·지움 등 원하는 상태의 사진 카드를 적어 둔다. 같은 호실의 같은 사진이면 바꾼다. */
  public synchronized void put(UUID roomRecordId, PhotoCard card) {
    List<Stored> stored = photos.computeIfAbsent(card.target(), target -> new ArrayList<>());
    stored.removeIf(
        photo -> photo.roomRecordId().equals(roomRecordId) && photo.card().id().equals(card.id()));
    stored.add(new Stored(roomRecordId, card));
  }

  private List<PhotoCard> roomCards(UUID roomRecordId, PhotoTarget target) {
    return photos.getOrDefault(target, List.of()).stream()
        .filter(photo -> photo.roomRecordId().equals(roomRecordId))
        .map(Stored::card)
        .sorted(CARD_ORDER)
        .toList();
  }

  /** 실제 파일은 없는 가짜 주소다. 만료를 주소에도 담아 재발급을 구분한다. */
  private static String fakeUrl(UUID photoId, String variant, Instant expiresAt) {
    return "https://photos.example.invalid/"
        + photoId
        + "/"
        + variant
        + "?expiresAt="
        + expiresAt.getEpochSecond();
  }

  /** 테스트용: 이 업로드 표로 붙이면 {@code code}로 거절한다(06 PHO-1a·1b·1c 경로). */
  public void failTicket(String uploadTicket, ErrorCode code) {
    failingTickets.put(uploadTicket, code);
  }

  /** 같은 표에서는 늘 같은 64자 소문자 hex가 나온다. {@code Photo}의 지문 형식과 같다. */
  private static String fakeSha256(String ticket) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code PhotoCard}에는 호실 기록이 없어서, 읽기 창구가 호실로 거를 수 있게 함께 둔다. */
  private record Stored(UUID roomRecordId, PhotoCard card) {}
}
