package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사진 붙이기(#112) 테스트의 공통 준비. 실제 구현({@code photo.service=jpa}) + 실제 DB(Testcontainers) + 메모리 S3({@link
 * RecordingPhotoObjectStore}). 시계는 고정이다.
 *
 * <p>테스트마다 새 호실(세입자·집주인 연결, 수리 건 하나)을 만들어 섞이지 않게 한다. {@link #attach}는 pipeline처럼 호실 행을 잠근 트랜잭션 안에서
 * 부른다.
 */
@SpringBootTest(properties = "photo.service=jpa")
@Import({TestcontainersConfiguration.class, PhotoAttachTestSupport.TestBeans.class})
@ActiveProfiles("test")
abstract class PhotoAttachTestSupport {

  static final Instant NOW = Instant.parse("2026-11-26T03:00:00.123456Z");
  static final Instant DEVICE_TIME = Instant.parse("2026-11-25T03:00:00Z");
  static final Duration ORPHAN_CHECK_AFTER = Duration.ofHours(1);
  static final int REPAIR_LIMIT = 30;

  @Autowired protected PhotoService photos;
  @Autowired protected RecordingPhotoObjectStore store;
  @Autowired protected PhotoUploadTicketSigner signer;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected TransactionTemplate transaction;

  protected UUID room;
  protected UUID tenant;
  protected UUID landlord;
  protected UUID tenantParty;
  protected UUID part;
  protected UUID repairCase;

  @BeforeEach
  void createsRoomWithRepairCase() {
    store.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = member();
    landlord = member();
    tenantParty = seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '욕실', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
    repairCase = repairCase();
  }

  // ---- 올리기 ----

  /** 화면이 presigned POST로 올린 사진 한 장과 그 업로드 표. */
  record Upload(String ticket, String pendingOriginal, String pendingThumb, String originalSha256) {

    /** 붙인 뒤의 정식 키. {@code pending/}을 {@code photos/}로 바꾼 같은 경로다(04 5-1). */
    String photosOriginal() {
      return "photos/" + pendingOriginal.substring("pending/".length());
    }

    String photosThumb() {
      return "photos/" + pendingThumb.substring("pending/".length());
    }

    /** 확인 줄의 중복 방지 키(05 {@code outbox.dedupe_key}). */
    String orphanCheckKey() {
      return "orphan-check:" + photosOriginal();
    }

    /** DB·payload에 남는 64자 소문자 hex. */
    String originalSha256Hex() {
      return HexFormat.of().formatHex(Base64.getDecoder().decode(originalSha256));
    }
  }

  Upload upload() {
    return upload(TestJpegs.redrawn(), TestJpegs.redrawn());
  }

  Upload upload(byte[] original, byte[] thumb) {
    Upload upload = notUploaded(original, thumb);
    store.put(upload.pendingOriginal(), original);
    store.put(upload.pendingThumb(), thumb);
    return upload;
  }

  /** 표만 받고 아직 올리지 않은 사진. */
  Upload notUploaded(byte[] original, byte[] thumb) {
    return signed(signer, room, tenant, original, thumb, NOW.plus(Duration.ofHours(12)));
  }

  Upload signed(
      PhotoUploadTicketSigner by,
      UUID roomId,
      UUID memberId,
      byte[] original,
      byte[] thumb,
      Instant expiresAt) {
    String prefix = "pending/" + roomId + "/" + Uuids.v7() + "/";
    String originalSha = RecordingPhotoObjectStore.sha256(original);
    var file =
        new UploadFileRequest(
            original.length,
            thumb.length,
            originalSha,
            RecordingPhotoObjectStore.sha256(thumb),
            DEVICE_TIME);
    String ticket =
        by.sign(
            roomId,
            memberId,
            prefix + "original.jpg",
            prefix + "thumb.jpg",
            file,
            expiresAt.truncatedTo(ChronoUnit.SECONDS));
    return new Upload(ticket, prefix + "original.jpg", prefix + "thumb.jpg", originalSha);
  }

  // ---- 붙이기 ----

  PhotoTarget.Ref caseTarget() {
    return new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, repairCase);
  }

  PhotoQuota caseQuota() {
    return new PhotoQuota(REPAIR_LIMIT, List.of(caseTarget()));
  }

  RoomAccess tenantAccess() {
    return access(null);
  }

  RoomAccess access(Instant sharingStoppedAt) {
    return new RoomAccess(
        room,
        tenant,
        tenantParty,
        PartySide.TENANT,
        PartyStatus.CONNECTED,
        RoomStatus.LIVING,
        null,
        PartyStatus.CONNECTED,
        sharingStoppedAt);
  }

  PhotoAttachCommand command(List<Upload> uploads) {
    return command(tenantAccess(), caseQuota(), uploads);
  }

  PhotoAttachCommand command(RoomAccess who, PhotoQuota quota, List<Upload> uploads) {
    return new PhotoAttachCommand(
        who,
        caseTarget(),
        PhotoStage.BEFORE,
        uploads.stream().map(Upload::ticket).toList(),
        quota,
        NOW);
  }

  List<PhotoDigest> attach(Upload... uploads) {
    return attach(command(List.of(uploads)));
  }

  /** pipeline처럼 호실 행을 잠근 트랜잭션 안에서 붙인다. */
  List<PhotoDigest> attach(PhotoAttachCommand command) {
    return transaction.execute(status -> {
      lockRoom();
      return photos.attach(command);
    });
  }

  void lockRoom() {
    jdbc.queryForObject(
        "SELECT id FROM state.room_record WHERE id = ? FOR UPDATE", UUID.class, room);
  }

  /** 붙이기가 {@code code}로 거절되는지. {@code photoIndex}는 null이면 없어야 한다. */
  static void assertRejected(Runnable call, ErrorCode code, Integer photoIndex) {
    Throwable thrown = catchThrowable(call::run);
    assertThat(thrown).isInstanceOf(BusinessException.class);
    BusinessException rejected = (BusinessException) thrown;
    assertThat(rejected.errorCode()).isEqualTo(code);
    assertThat(rejected.photoIndex().orElse(null)).isEqualTo(photoIndex);
  }

  // ---- DB ----

  int photoCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.photo WHERE room_record_id = ?", Integer.class, room);
  }

  List<Map<String, Object>> photoRows() {
    return jdbc.queryForList(
        "SELECT * FROM record.photo WHERE room_record_id = ? ORDER BY sort_order, id", room);
  }

  /** 확인 줄. 없으면 null. */
  Map<String, Object> checkRow(Upload upload) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT kind, status, payload::text AS payload, next_attempt_at FROM state.outbox"
                + " WHERE dedupe_key = ?",
            upload.orphanCheckKey());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }

  /** 이미 붙어 있는 사진(한도 셈용). 파일은 없다. 표시 순서는 이 호실의 사진 수다. */
  UUID existingPhoto(PhotoTarget.Ref target, PartySide side, Instant createdAt, boolean erased) {
    return existingPhoto(target, side, createdAt, erased, null);
  }

  /** {@code sortOrder}가 null이면 이 호실의 사진 수를 표시 순서로 쓴다. */
  UUID existingPhoto(
      PhotoTarget.Ref target,
      PartySide side,
      Instant createdAt,
      boolean erased,
      Integer sortOrder) {
    UUID id = UUID.randomUUID();
    String column =
        switch (target.type()) {
          case REPAIR_CASE -> "repair_case_id";
          case PROGRESS_NOTE -> "progress_note_id";
          case CAUSE_NOTE -> "cause_note_id";
          default -> throw new IllegalArgumentException(target.type().name());
        };
    jdbc.update(
        "INSERT INTO record.photo (id, room_record_id, "
            + column
            + ", stage, uploaded_by_side, original_key, thumb_key, original_sha256, thumb_sha256,"
            + " size_bytes, sort_order, created_at)"
            + " VALUES (?, ?, ?, 'BEFORE', ?, ?, ?, ?, ?, 1,"
            + " COALESCE(CAST(? AS int), (SELECT count(*) FROM record.photo WHERE room_record_id = ?)),"
            + " ?)",
        id,
        room,
        target.id(),
        side.name(),
        "photos/" + room + "/" + id + "/original.jpg",
        "photos/" + room + "/" + id + "/thumb.jpg",
        "0".repeat(64),
        "0".repeat(64),
        sortOrder,
        room,
        Timestamp.from(createdAt));
    jdbc.update(
        "INSERT INTO state.photo_visibility (photo_id, room_record_id, erased_at, erase_reason,"
            + " created_at, version) VALUES (?, ?, ?, ?, ?, 0)",
        id,
        room,
        erased ? Timestamp.from(createdAt.plusSeconds(1)) : null,
        erased ? "PRIVACY" : null,
        Timestamp.from(createdAt));
    return id;
  }

  List<UUID> existingPhotos(int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(existingPhoto(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), false));
    }
    return ids;
  }

  UUID progressNote() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO record.progress_note (id, room_record_id, repair_case_id, side, text,"
            + " created_at) VALUES (?, ?, ?, 'LANDLORD', '업체 불렀어요', CURRENT_TIMESTAMP)",
        id,
        room,
        repairCase);
    return id;
  }

  /** 수리 건과 첫 내용 판. 서로 가리키는 FK가 지연 검사라 한 트랜잭션에 넣는다. */
  private UUID repairCase() {
    return transaction.execute(status -> insertRepairCase());
  }

  private UUID insertRepairCase() {
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.repair_case (id, room_record_id, current_content_id, status, defect_ack,
          money_status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        content);
    jdbc.update(
        """
        INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no, room_part_id,
          title, created_by_side, created_at)
        VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
        """,
        content,
        room,
        id,
        part);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private UUID seat(PartySide side, UUID memberId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side.name(),
        memberId);
    return id;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    @Primary
    Clock photoAttachClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    RecordingPhotoObjectStore recordingPhotoObjectStore() {
      return new RecordingPhotoObjectStore();
    }
  }
}
