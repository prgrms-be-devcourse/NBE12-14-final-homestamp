package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.link.dto.LinkViewKind;
import com.chilsuitjo.back.link.dto.LinkViewRequest;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.service.InviteLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 초대 링크 발급·무효(#163 ②)를 실제 DB로 본다. 포트는 부르는 쪽 트랜잭션 안에서만 돌아서 테스트가 트랜잭션을 연다. 행은 임의 ID라 다른 테스트와 겹치지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class InviteLinkServiceTest {

  @Autowired private InviteLinks inviteLinks;
  @Autowired private LinkTokenKeys tokenKeys;
  @Autowired private LinkViewService views;
  @Autowired private FakeLinkContentSource content;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void 처음_발급하면_링크_ID만_돌려주고_토큰_원문_없이_해시만_남긴다() {
    Room room = room();

    UUID id =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", id);
    assertThat(row.get("kind")).isEqualTo("INVITE");
    assertThat(row.get("room_record_id")).isEqualTo(room.id());
    assertThat(row.get("party_id")).isEqualTo(room.counterpart());
    assertThat(row.get("issued_by_member_id")).isEqualTo(room.issuer());
    assertThat(row.get("repair_case_id")).isNull();
    assertThat(row.get("inspection_id")).isNull();
    assertThat(row.get("invalidated_at")).isNull();
    String token = tokenKeys.token(id);
    assertThat(row.get("token_hash")).isEqualTo(LinkToken.hash(token));
    assertThat(row.values()).doesNotContain(token);
  }

  @Test
  void 같은_자리_같은_기록이면_유효한_링크를_다시_쓴다() {
    Room room = room();
    UUID inspection = inspection(room.id());
    RequestTarget record = RequestTarget.of(TargetKind.INSPECTION, inspection);

    UUID first =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), record, room.issuer()));
    Map<String, Object> before = jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", first);
    UUID again =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), record, room.issuer()));

    assertThat(again).isEqualTo(first);
    assertThat(linkCount(room.id())).isEqualTo(1);
    // 다시 쓰면 행을 바꾸지 않는다: 처음 발급자·만든 시각·토큰 해시·판이 그대로다
    assertThat(jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", first))
        .containsEntry("issued_by_member_id", room.issuer())
        .containsEntry("created_at", before.get("created_at"))
        .containsEntry("token_hash", before.get("token_hash"))
        .containsEntry("version", before.get("version"));
  }

  @Test
  void 번호를_고쳐_다시_초대하면_먼저_무효로_한_뒤_새_링크를_만들고_옛_토큰은_열리지_않는다() {
    Room room = room();
    content.seedRoom(
        room.id(),
        room.counterpart(),
        new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", false, false));
    UUID old =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    String oldToken = tokenKeys.token(old);
    assertThat(views.view(new LinkViewRequest(oldToken)).kind())
        .isEqualTo(LinkViewKind.ROOM_INVITE);

    // InviteLinks.issueInvite 설명의 순서 ②·③을 한 트랜잭션에서 한다(①·④는 room 몫이라 여기서 보지 않음)
    UUID fresh =
        inTx(() -> {
          inviteLinks.invalidateInvites(room.id(), room.counterpart(), LinkInvalidReason.REINVITED);
          return inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer());
        });

    assertThat(fresh).isNotEqualTo(old);
    assertThat(reason(old)).isEqualTo("REINVITED");
    assertThat(reason(fresh)).isNull();
    assertThatThrownBy(() -> views.view(new LinkViewRequest(oldToken)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
    assertThat(views.view(new LinkViewRequest(tokenKeys.token(fresh))).kind())
        .isEqualTo(LinkViewKind.ROOM_INVITE);
  }

  @Test
  void 기록이_다르거나_자리가_다르면_따로_만든다() {
    Room room = room();
    UUID inspection = inspection(room.id());

    UUID roomInvite =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    UUID recordLink =
        inTx(() ->
            inviteLinks.issueInvite(
                room.id(),
                room.counterpart(),
                RequestTarget.of(TargetKind.INSPECTION, inspection),
                room.issuer()));
    UUID otherSeat =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.mine(), null, room.issuer()));

    assertThat(List.of(roomInvite, recordLink, otherSeat)).doesNotHaveDuplicates();
    assertThat(
            jdbc.queryForObject(
                "SELECT inspection_id FROM state.link WHERE id = ?", UUID.class, recordLink))
        .isEqualTo(inspection);
  }

  @Test
  void 다른_호실의_링크는_다시_쓰지_않는다() {
    Room one = room();
    Room two = room();

    UUID a = inTx(() -> inviteLinks.issueInvite(one.id(), one.counterpart(), null, one.issuer()));
    UUID b = inTx(() -> inviteLinks.issueInvite(two.id(), two.counterpart(), null, two.issuer()));

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void 다른_호실의_자리나_기록이면_저장이_실패하고_링크가_남지_않는다() {
    Room one = room();
    Room two = room();
    UUID otherInspection = inspection(two.id());

    assertThatThrownBy(() ->
            inTx(() -> inviteLinks.issueInvite(one.id(), two.counterpart(), null, one.issuer())))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            inTx(() ->
                inviteLinks.issueInvite(
                    one.id(),
                    one.counterpart(),
                    RequestTarget.of(TargetKind.INSPECTION, otherInspection),
                    one.issuer())))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            inTx(() ->
                inviteLinks.issueInvite(
                    one.id(),
                    one.counterpart(),
                    RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID()),
                    one.issuer())))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(linkCount(one.id())).isZero();
  }

  @Test
  void 수리_건_점검이_아닌_기록이나_없는_호실은_받지_않는다() {
    Room room = room();

    assertThatThrownBy(() ->
            inTx(() ->
                inviteLinks.issueInvite(
                    room.id(),
                    room.counterpart(),
                    RequestTarget.roomRecord(room.id()),
                    room.issuer())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            inTx(() ->
                inviteLinks.issueInvite(
                    UUID.randomUUID(), room.counterpart(), null, room.issuer())))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void 트랜잭션_밖에서_부르면_개발_오류다() {
    Room room = room();

    assertThatThrownBy(
            () -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () -> inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void 부르는_쪽이_되돌리면_링크도_남지_않는다() {
    Room room = room();

    new TransactionTemplate(txManager)
        .executeWithoutResult(status -> {
          inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer());
          status.setRollbackOnly();
        });

    assertThat(linkCount(room.id())).isZero();
  }

  @Test
  void 같은_조건의_발급이_동시에_와도_링크는_하나다() throws Exception {
    Room room = room();
    CountDownLatch start = new CountDownLatch(1);
    Callable<UUID> issue =
        () -> {
          start.await();
          return inTx(
              () -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<UUID>> results = new ArrayList<>();
      results.add(pool.submit(issue));
      results.add(pool.submit(issue));
      start.countDown();

      assertThat(results.get(0).get(30, TimeUnit.SECONDS))
          .isEqualTo(results.get(1).get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(linkCount(room.id())).isEqualTo(1);
  }

  @Test
  void 자리_하나만_무효로_하면_그_자리_링크만_닫히고_이유와_시각이_남는다() {
    Room room = room();
    UUID theirs =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    UUID mine = inTx(() -> inviteLinks.issueInvite(room.id(), room.mine(), null, room.issuer()));

    int closed =
        inTx(() ->
            inviteLinks.invalidateInvites(
                room.id(), room.counterpart(), LinkInvalidReason.REINVITED));

    assertThat(closed).isEqualTo(1);
    assertThat(reason(theirs)).isEqualTo("REINVITED");
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, theirs))
        .isNotNull();
    assertThat(reason(mine)).isNull();
  }

  @Test
  void 호실_전체를_무효로_해도_먼저_닫힌_이유는_그대로고_정산_초대_링크는_건드리지_않는다() {
    Room room = room();
    UUID reinvited =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    inTx(() ->
        inviteLinks.invalidateInvites(room.id(), room.counterpart(), LinkInvalidReason.REINVITED));
    UUID fresh =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    UUID other = inTx(() -> inviteLinks.issueInvite(room.id(), room.mine(), null, room.issuer()));
    UUID settlement = settlementInvite(room);

    int closed =
        inTx(() -> inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED));

    assertThat(fresh).isNotEqualTo(reinvited);
    assertThat(closed).isEqualTo(2);
    assertThat(reason(reinvited)).isEqualTo("REINVITED");
    assertThat(reason(fresh)).isEqualTo("RESIDENCE_ENDED");
    assertThat(reason(other)).isEqualTo("RESIDENCE_ENDED");
    assertThat(reason(settlement)).isNull();
    assertThat(
            inTx(() -> inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED)))
        .isZero();
  }

  @Test
  void 발급한_링크는_열람으로_열리고_무효로_하면_열리지_않는다() {
    Room room = room();
    content.seedRoom(
        room.id(),
        room.counterpart(),
        new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", false, false));
    UUID id =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    String token = tokenKeys.token(id);

    assertThat(views.view(new LinkViewRequest(token)).kind()).isEqualTo(LinkViewKind.ROOM_INVITE);

    inTx(() -> inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED));
    assertThatThrownBy(() -> views.view(new LinkViewRequest(token)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
  }

  private <T> T inTx(Supplier<T> work) {
    return new TransactionTemplate(txManager).execute(status -> work.get());
  }

  private int linkCount(UUID room) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.link WHERE room_record_id = ?", Integer.class, room);
  }

  private String reason(UUID link) {
    return jdbc.queryForObject(
        "SELECT invalidated_reason FROM state.link WHERE id = ?", String.class, link);
  }

  /** 거주 중 호실 하나: 만든 측(세입자, 회원) 자리와 초대받는 상대(집주인) 자리. */
  private Room room() {
    UUID id = UUID.randomUUID();
    String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)"
            + " VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        id,
        region);
    UUID issuer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        issuer);
    UUID mine = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, member_id, status, created_at, version)"
            + " VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, 0)",
        mine,
        id,
        issuer);
    UUID counterpart = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', '01012345678', 'INVITED', CURRENT_TIMESTAMP, 0)",
        counterpart,
        id);
    return new Room(id, mine, counterpart, issuer);
  }

  private UUID inspection(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)"
            + " VALUES (?, ?, 'MOVE_IN', 'TENANT', 'DRAFT', CURRENT_TIMESTAMP, 0)",
        id,
        room);
    return id;
  }

  /** 정산 초대 링크(LINK-3)는 아직 발급 기능이 없어 행을 직접 넣는다. */
  private UUID settlementInvite(Room room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,"
            + " created_at, version) VALUES (?, ?, ?, ?, 'SETTLEMENT_INVITE', ?, CURRENT_TIMESTAMP, 0)",
        id,
        room.id(),
        room.counterpart(),
        room.issuer(),
        LinkToken.hash(LinkToken.newToken()));
    return id;
  }

  private record Room(UUID id, UUID mine, UUID counterpart, UUID issuer) {}
}
