package com.chilsuitjo.back.photo.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 작성 중 점검의 사진을 상대에게 가리기(#295, 03 3-1 "작성 중 점검", 06 INSP-1a). 상대에게 보이기 시작한 시각은 {@code
 * photo_visibility.released_at}이다: 보통 사진은 붙일 때, 기준 측이 작성 중에 붙인 사진은 확인 요청({@code
 * REQUEST_INSPECTION_CONFIRM})을 저장할 때 채운다.
 *
 * <p>점검 상세가 항목을 가릴 때와 사진이 가려질 때가 늘 같아야 한다. 그래서 가림을 보는 테스트는 상대의 점검 상세({@code GET
 * /api/inspections/{id}})와 {@code view-urls}를 함께 본다({@link #assertSameSight}).
 *
 * <p>점검 API부터 실제 사진 구현({@code photo.service=jpa})·실제 DB·메모리 S3까지 띄운다. 시계는 {@link #START}에서 손으로만
 * 옮긴다. 부위 사진({@code POST /api/inspections/{id}/part-photos})은 아직 API가 없어 항목 사진으로만 본다.
 */
@SpringBootTest(properties = "photo.service=jpa")
@Import({TestcontainersConfiguration.class, InspectionDraftPhotoSightTest.TestBeans.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionDraftPhotoSightTest {

  static final Instant START = Instant.parse("2026-11-26T03:00:00.123456Z");
  static final Instant DEVICE_TIME = Instant.parse("2026-11-25T03:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhotoService photos;
  @Autowired private RecordingPhotoObjectStore store;
  @Autowired private PhotoUploadTicketSigner signer;
  @Autowired private StepClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID tenantParty;
  private UUID landlordParty;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    store.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', ?, 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room,
        Date.valueOf(LocalDate.parse("2026-03-01")));
    tenant = member();
    landlord = member();
    tenantParty = seat(PartySide.TENANT, tenant);
    landlordParty = seat(PartySide.LANDLORD, landlord);
    part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, (SELECT id FROM state.part_template ORDER BY sort_order LIMIT 1), '안방', '천장', 0,
               CURRENT_TIMESTAMP, 0
        """,
        part,
        room);
  }

  // ---- 완료 조건 1: 작성 중 점검의 사진은 ID를 알아도 상대가 받을 수 없다 ----

  @Test
  void 작성_중_점검의_사진을_상대가_view_urls로_요청하면_NOT_FOUND다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    UUID photo = itemPhoto(item);

    viewUrls(landlord, photo)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    // 올린 사람(기준 측)은 작성 중에도 자기 사진을 본다
    viewUrls(tenant, photo).andExpect(status().isOk());
    assertThat(releasedAt(photo)).isNull();
  }

  @Test
  void 작성_중_점검의_사진은_부르는_쪽이_대상을_넘겨도_상대의_cards에서_빠진다() {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    PhotoTarget target = new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item);

    assertThat(photos.cards(access(PartySide.LANDLORD), List.of(target)).get(target)).isEmpty();
    assertThat(photos.cards(access(PartySide.TENANT), List.of(target)).get(target)).hasSize(1);
  }

  // ---- 보이기 시작한 시각(released_at) ----

  @Test
  void 확인_요청을_보내면_그_점검의_사진이_행동_시각부터_상대에게_보인다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    UUID photo = itemPhoto(item);
    later();

    requestConfirm(tenant, inspection);

    assertThat(releasedAt(photo)).isEqualTo(requestedAt(inspection));
    assertSameSight(landlord, inspection, item, photo, true);
  }

  @Test
  void INSP_1a_상대가_작성_중에_더한_항목의_사진은_붙인_때부터_기준_측에게_보인다() throws Exception {
    UUID inspection = start(tenant);
    later();
    UUID theirs = addItem(landlord, inspection, "방충망 찢어짐");
    UUID photo = itemPhoto(theirs);

    assertThat(releasedAt(photo)).isEqualTo(photoCreatedAt(photo));
    assertSameSight(tenant, inspection, theirs, photo, true);
  }

  @Test
  void 확인_요청_뒤에_기준_측이_더한_항목의_사진은_붙인_때부터_상대에게_보인다() throws Exception {
    UUID inspection = start(tenant);
    requestConfirm(tenant, inspection);
    later();

    UUID item = addItem(tenant, inspection, "창틀 곰팡이");
    UUID photo = itemPhoto(item);

    assertThat(releasedAt(photo)).isEqualTo(photoCreatedAt(photo));
    assertSameSight(landlord, inspection, item, photo, true);
  }

  // ---- 완료 조건 2: 점검 상세가 항목을 가릴 때와 사진이 가려질 때가 같다 ----

  @Test
  void 작성_중에는_기준_측_항목도_그_사진도_상대에게_가려진다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");

    assertSameSight(landlord, inspection, item, itemPhoto(item), false);
  }

  @Test
  @Tag("invariant-18")
  void 상대가_공유를_그만둔_뒤_보낸_확인_요청으로는_항목도_사진도_드러나지_않는다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    later();
    stopSharing(PartySide.LANDLORD, clock.instant());
    later();

    requestConfirm(tenant, inspection);

    assertSameSight(landlord, inspection, item, itemPhoto(item), false);
  }

  @Test
  @Tag("invariant-18")
  void 기준_측이_공유를_그만둔_뒤_상대가_보낸_확인_요청으로도_기준_측_항목과_사진은_드러나지_않는다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    // inspection.draft.stale-after(7d)가 지나야 상대도 보낼 수 있다(INSP-1f)
    clock.advance(Duration.ofDays(8));
    stopSharing(PartySide.TENANT, clock.instant());
    later();

    requestConfirm(landlord, inspection);

    assertSameSight(landlord, inspection, item, itemPhoto(item), false);
  }

  @Test
  @Tag("invariant-18")
  void 공유를_그만두기_전에_보낸_확인_요청의_항목과_사진은_그만둔_뒤에도_보인다() throws Exception {
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    later();
    requestConfirm(tenant, inspection);
    later();

    stopSharing(PartySide.LANDLORD, clock.instant());

    assertSameSight(landlord, inspection, item, itemPhoto(item), true);
  }

  @Test
  @Tag("invariant-18")
  void 그만둔_시각과_같은_때_보낸_확인_요청은_보이고_그_직전에_그만뒀으면_항목도_사진도_가려진다() throws Exception {
    // 경계는 "그만둔 시각까지" 포함이다(InspectionApiTest의 같은 경계, #234 리뷰)
    UUID inspection = start(tenant);
    UUID item = addItem(tenant, inspection, "벽지 찢어짐");
    UUID photo = itemPhoto(item);
    later();
    requestConfirm(tenant, inspection);
    Instant requestedAt = requestedAt(inspection);

    stopSharing(PartySide.LANDLORD, requestedAt);
    assertSameSight(landlord, inspection, item, photo, true);

    stopSharing(PartySide.LANDLORD, requestedAt.minus(Duration.ofNanos(1_000)));
    assertSameSight(landlord, inspection, item, photo, false);
  }

  // ---- 확인 ----

  /** 보는 사람에게 점검 상세의 항목과 그 사진이 함께 보이거나 함께 가려지는지. */
  private void assertSameSight(UUID viewer, UUID inspection, UUID item, UUID photo, boolean visible)
      throws Exception {
    detail(viewer, inspection)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].id")
                .value(visible ? hasItem(item.toString()) : not(hasItem(item.toString()))));
    if (visible) {
      viewUrls(viewer, photo).andExpect(status().isOk());
    } else {
      viewUrls(viewer, photo)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
  }

  // ---- API ----

  private UUID start(UUID member) {
    return created(
        perform(
            post("/api/rooms/{id}/inspections", room)
                .with(loginAs(member))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"MOVE_IN\"}")));
  }

  /** 사진 한 장을 붙여 항목을 더한다. 업로드 표는 더하는 사람의 것이다. */
  private UUID addItem(UUID member, UUID inspection, String description) {
    return created(
        perform(
            post("/api/inspections/{id}/items", inspection)
                .with(loginAs(member))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"roomPartId\":\""
                        + part
                        + "\",\"description\":\""
                        + description
                        + "\",\"uploadTickets\":[\""
                        + upload(member)
                        + "\"]}")));
  }

  private void requestConfirm(UUID member, UUID inspection) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(member))
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  private ResultActions detail(UUID member, UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(member)));
  }

  private ResultActions viewUrls(UUID member, UUID photo) throws Exception {
    return mvc.perform(
        post("/api/photos/view-urls")
            .with(loginAs(member))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"photoIds\":[\"" + photo + "\"],\"variant\":\"THUMB\"}"));
  }

  private ResultActions perform(RequestBuilder request) {
    try {
      return mvc.perform(request);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID created(ResultActions result) {
    try {
      String body =
          result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
      return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- 준비 ----

  /** 화면이 presigned POST로 올린 사진 한 장의 업로드 표. 파일은 메모리 S3의 {@code pending/}에 있다. */
  private String upload(UUID member) {
    byte[] original = TestJpegs.redrawn();
    byte[] thumb = TestJpegs.redrawn();
    String prefix = "pending/" + room + "/" + Uuids.v7() + "/";
    var file =
        new UploadFileRequest(
            original.length,
            thumb.length,
            RecordingPhotoObjectStore.sha256(original),
            RecordingPhotoObjectStore.sha256(thumb),
            DEVICE_TIME);
    String ticket =
        signer.sign(
            room,
            member,
            prefix + "original.jpg",
            prefix + "thumb.jpg",
            file,
            clock.instant().plus(Duration.ofHours(12)).truncatedTo(ChronoUnit.SECONDS));
    store.put(prefix + "original.jpg", original);
    store.put(prefix + "thumb.jpg", thumb);
    return ticket;
  }

  private RoomAccess access(PartySide side) {
    boolean isTenant = side == PartySide.TENANT;
    return new RoomAccess(
        room,
        isTenant ? tenant : landlord,
        isTenant ? tenantParty : landlordParty,
        side,
        PartyStatus.CONNECTED,
        RoomStatus.LIVING,
        null,
        PartyStatus.CONNECTED,
        null);
  }

  /** 서버 시각을 조금 옮겨 앞뒤 행동의 시각이 겹치지 않게 한다. */
  private void later() {
    clock.advance(Duration.ofSeconds(1));
  }

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }

  // ---- DB ----

  private UUID itemPhoto(UUID item) {
    return jdbc.queryForObject(
        "SELECT id FROM record.photo WHERE inspection_item_id = ?", UUID.class, item);
  }

  private Instant releasedAt(UUID photo) {
    Timestamp at =
        jdbc.queryForObject(
            "SELECT released_at FROM state.photo_visibility WHERE photo_id = ?",
            Timestamp.class,
            photo);
    return at == null ? null : at.toInstant();
  }

  private Instant photoCreatedAt(UUID photo) {
    return jdbc.queryForObject(
            "SELECT created_at FROM record.photo WHERE id = ?", Timestamp.class, photo)
        .toInstant();
  }

  private Instant requestedAt(UUID inspection) {
    return jdbc.queryForObject(
            "SELECT created_at FROM record.action WHERE inspection_id = ?"
                + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
            Timestamp.class,
            inspection)
        .toInstant();
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

  /** {@link #START}에서 시작해 손으로 옮긴 만큼만 가는 서버 시각(pipeline·조회·업로드 표 검사가 같이 쓴다). */
  static class StepClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void reset() {
      offset.set(Duration.ZERO);
    }

    void advance(Duration duration) {
      offset.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return START.plus(offset.get());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    @Primary
    StepClock inspectionDraftPhotoClock() {
      return new StepClock();
    }

    @Bean
    RecordingPhotoObjectStore recordingPhotoObjectStore() {
      return new RecordingPhotoObjectStore();
    }
  }
}
