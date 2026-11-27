package com.chilsuitjo.back.inspection.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.inspection.service.InspectionLinkQuery;
import com.chilsuitjo.back.inspection.service.JpaInspectionLinkQuery;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 링크용 점검 조회(#303, #395). 점검 API로 진짜 점검·항목·행동 기록을 만들고 {@link InspectionLinkQuery}로 읽는다. 세입자가 기준 측으로
 * 항목을 쓰고 임대인이 답한다. 링크 무효·호실 상태는 link가 먼저 보므로 여기서는 보이는 범위만 본다.
 */
@Import({TestcontainersConfiguration.class, InspectionApiTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionLinkQueryTest {

  @Autowired private InspectionLinkQuery query;
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InspectionApiTest.MutableClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room();
    tenant = member();
    landlord = member();
    seat(room, PartySide.TENANT, tenant);
    seat(room, PartySide.LANDLORD, landlord);
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

  @Test
  void 진짜_구현이_쓰인다() {
    assertThat(query).isInstanceOf(JpaInspectionLinkQuery.class);
  }

  @Test
  void 확인_요청한_점검은_항목_사진_대상_경과와_함께_보인다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection).getFirst();

    InspectionForLink seen = query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.kind()).isEqualTo(InspectionKind.MOVE_IN);
    assertThat(seen.status()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(seen.authorSide()).isEqualTo(PartySide.TENANT);
    assertThat(seen.lockedAt()).isNull();
    assertThat(seen.lockReason()).isNull();
    assertThat(seen.asOf()).isNull();
    assertThat(seen.items())
        .singleElement()
        .satisfies(shown -> {
          assertThat(shown.id()).isEqualTo(item);
          assertThat(shown.status()).isEqualTo(AckState.PENDING);
          assertThat(shown.addedBySide()).isEqualTo(PartySide.TENANT);
          assertThat(shown.versionNo()).isEqualTo(1);
          assertThat(shown.partName()).isEqualTo("천장");
          assertThat(shown.description()).isEqualTo("벽지 찢어짐");
          assertThat(shown.photoTarget())
              .isEqualTo(new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item));
        });
    assertThat(seen.history())
        .extracting(InspectionForLink.History::type)
        .containsExactly(
            ActionType.START_INSPECTION,
            ActionType.ADD_ITEM,
            ActionType.REQUEST_INSPECTION_CONFIRM);
    assertThat(seen.history())
        .allSatisfy(line -> assertThat(line.side()).isEqualTo(PartySide.TENANT));
    assertThat(seen.history().get(1).itemId()).isEqualTo(item);
    assertThat(seen.history().getFirst().itemId()).isNull();
  }

  @Test
  void 잠긴_점검은_잠긴_시각_이유와_서버_잠금_경과가_보인다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection).getFirst();
    respond(landlord, item, "a1", "ACK").andExpect(status().isOk());

    InspectionForLink seen = query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.status()).isEqualTo(InspectionStatus.LOCKED);
    assertThat(seen.lockedAt()).isNotNull();
    assertThat(seen.lockReason()).isEqualTo(LockReason.ALL_RESOLVED);
    assertThat(seen.items().getFirst().status()).isEqualTo(AckState.ACK);
    assertThat(seen.history())
        .extracting(InspectionForLink.History::type)
        .endsWith(ActionType.RESPOND_ITEM, ActionType.LOCK);
    assertThat(seen.history().getLast().side()).isNull();
  }

  @ParameterizedTest
  @EnumSource(PartySide.class)
  void 작성_중인_점검은_어느_측이_봐도_빈_값이다(PartySide viewer) throws Exception {
    UUID inspection = created(start("MOVE_IN"));
    created(addItem(inspection, "i0"));

    assertThat(query.forLink(room, inspection, viewer)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(PartySide.class)
  void 공유를_그만둔_시각에_작성_중이던_점검은_그_뒤_확인_요청을_보내도_빈_값이다(PartySide viewer) throws Exception {
    UUID inspection = created(start("MOVE_IN"));
    created(addItem(inspection, "i0"));
    stopSharing(PartySide.LANDLORD, clock.instant());
    clock.advance(Duration.ofMinutes(1));
    requestConfirm(inspection);

    // 기준 측(세입자)에게는 자기 확인 요청으로 확인 요청 중이지만, 링크는 기준 시각까지의 모습으로 본다(#352와 같은 경계)
    assertThat(query.forLink(room, inspection, viewer)).isEmpty();
  }

  @Test
  void 공유를_그만둔_뒤_상대가_시작한_점검은_빈_값이다() throws Exception {
    stopSharing(PartySide.LANDLORD, clock.instant().minusSeconds(3600));
    UUID inspection = requestedWith(1);

    assertThat(query.forLink(room, inspection, PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void 불변식_18_고정_뒤_상대의_응답과_잠금은_보이지_않는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection).getFirst();
    stopSharing(PartySide.TENANT, clock.instant());
    clock.advance(Duration.ofMinutes(1));
    respond(landlord, item, "a1", "ACK").andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM state.inspection WHERE id = ?", String.class, inspection))
        .isEqualTo("LOCKED");

    InspectionForLink seen = query.forLink(room, inspection, PartySide.TENANT).orElseThrow();

    assertThat(seen.asOf()).isNotNull();
    assertThat(seen.status()).isEqualTo(InspectionStatus.REQUESTED);
    assertThat(seen.lockedAt()).isNull();
    assertThat(seen.lockReason()).isNull();
    assertThat(seen.items().getFirst().status()).isEqualTo(AckState.PENDING);
    assertThat(seen.history())
        .extracting(InspectionForLink.History::type)
        .doesNotContain(ActionType.RESPOND_ITEM, ActionType.LOCK);

    // 답한 쪽은 자기 응답을 본다
    InspectionForLink answerer = query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow();
    assertThat(answerer.items().getFirst().status()).isEqualTo(AckState.ACK);
    assertThat(answerer.history())
        .extracting(InspectionForLink.History::type)
        .contains(ActionType.RESPOND_ITEM);
  }

  @Test
  void 불변식_18_고정_뒤_상대가_보충한_것은_보이지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID item = items(inspection).getFirst();
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    stopSharing(PartySide.LANDLORD, clock.instant());
    clock.advance(Duration.ofMinutes(1));
    supplement(item).andExpect(status().isOk());

    InspectionForLink seen = query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.items().getFirst().status()).isEqualTo(AckState.NEED_MORE);
    assertThat(seen.history())
        .extracting(InspectionForLink.History::type)
        .doesNotContain(ActionType.SUPPLEMENT_ITEM);
  }

  @Test
  void 불변식_18_고정_뒤_상대가_만든_판은_보이지_않고_자기_항목은_지금_판이다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection).getFirst();
    stopSharing(PartySide.LANDLORD, clock.instant());
    clock.advance(Duration.ofMinutes(1));
    // 판을 바꾸는 고치기는 아직 없어 판 #2를 직접 넣는다
    UUID second = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO record.inspection_item_version
          (id, room_record_id, inspection_item_id, no, room_part_id, description, previous_id, created_at)
        SELECT ?, room_record_id, inspection_item_id, 2, room_part_id, '고친 설명', id, ?
          FROM record.inspection_item_version WHERE inspection_item_id = ? AND no = 1
        """,
        second,
        Timestamp.from(clock.instant()),
        item);
    jdbc.update(
        "UPDATE state.inspection_item SET current_version_id = ? WHERE id = ?", second, item);

    InspectionForLink.Item other =
        query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow().items().getFirst();
    assertThat(other.versionNo()).isEqualTo(1);
    assertThat(other.description()).isEqualTo("벽지 찢어짐");

    InspectionForLink.Item own =
        query.forLink(room, inspection, PartySide.TENANT).orElseThrow().items().getFirst();
    assertThat(own.versionNo()).isEqualTo(2);
    assertThat(own.description()).isEqualTo("고친 설명");
  }

  @Test
  void 다른_호실의_점검이나_없는_점검은_빈_값이다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID otherRoom = room();

    assertThat(query.forLink(otherRoom, inspection, PartySide.LANDLORD)).isEmpty();
    assertThat(query.forLink(room, UUID.randomUUID(), PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void 퇴거_점검도_그_점검_하나만_보인다() throws Exception {
    UUID inspection = created(start("MOVE_OUT"));
    created(addItem(inspection, "i0"));
    requestConfirm(inspection);

    InspectionForLink seen = query.forLink(room, inspection, PartySide.LANDLORD).orElseThrow();
    assertThat(seen.kind()).isEqualTo(InspectionKind.MOVE_OUT);
    assertThat(seen.items()).hasSize(1);
  }

  @Test
  void 응답에_별칭_번호_회원_ID_호실_부위_ID_행동_목록이_없다() {
    List<String> names =
        Stream.of(
                InspectionForLink.class,
                InspectionForLink.Item.class,
                InspectionForLink.History.class)
            .flatMap(type -> Arrays.stream(type.getRecordComponents()))
            .map(RecordComponent::getName)
            .toList();
    assertThat(names)
        .noneMatch(name ->
            name.contains("alias")
                || name.contains("phone")
                || name.contains("member")
                || name.contains("roomRecord")
                || name.contains("roomPart")
                || name.contains("Actions")
                || name.contains("reason") && !name.equals("lockReason")
                || name.contains("sideBySide"));
  }

  // ---- 도우미 ----

  /** 세입자가 항목 {@code count}개를 쓰고 확인 요청을 보낸 입주 점검. */
  private UUID requestedWith(int count) throws Exception {
    UUID inspection = created(start("MOVE_IN"));
    for (int i = 0; i < count; i++) {
      created(addItem(inspection, "i" + i));
    }
    requestConfirm(inspection);
    return inspection;
  }

  private List<UUID> items(UUID inspection) {
    return jdbc.queryForList(
        "SELECT id FROM state.inspection_item WHERE inspection_id = ? ORDER BY id",
        UUID.class,
        inspection);
  }

  private ResultActions start(String kind) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "start-" + kind)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"" + kind + "\"}"));
  }

  private ResultActions addItem(UUID inspection, String key) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\"벽지 찢어짐\",\"uploadTickets\":[]}"));
  }

  private void requestConfirm(UUID inspection) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "request"))
        .andExpect(status().isOk());
  }

  private ResultActions respond(UUID member, UUID item, String key, String answer)
      throws Exception {
    return respond(member, item, key, answer, null);
  }

  private ResultActions respond(UUID member, UUID item, String key, String answer, String reason)
      throws Exception {
    String body =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":1}";
    return mvc.perform(
        post("/api/inspection-items/{id}/respond", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions supplement(UUID item) throws Exception {
    return mvc.perform(
        post("/api/inspection-items/{id}/supplement", item)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "s1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"밝게 다시 찍었어요\"}"));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', DATE '2026-03-01', 'LIVING', 'TENANT',
                CURRENT_TIMESTAMP, 0)
        """,
        id);
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

  private void seat(UUID roomId, PartySide side, UUID memberId) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        roomId,
        side.name(),
        memberId);
  }

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }
}
