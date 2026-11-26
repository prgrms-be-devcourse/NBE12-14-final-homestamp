package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.RequestContextTestSupport;
import com.chilsuitjo.back.action.service.StateStore;
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
import java.sql.Timestamp;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;
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
  @Autowired private ActionRequestContext requestContext;
  @Autowired private ActionPipeline pipeline;

  /** 테스트의 행동 시각. 지금 시계와 일부러 다르게 둔다(링크 시각이 {@code Clock}이 아니라 행동 시각인지 보려고). */
  private static final Instant AT = Instant.parse("2026-11-20T01:02:03.456789Z");

  private static final Instant LATER = AT.plusSeconds(3600);

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
  void 다른_호실의_자리면_개발_오류로_저장이_실패하고_링크가_남지_않는다() {
    Room one = room();
    Room two = room();

    assertThatThrownBy(() ->
            inTx(() -> inviteLinks.issueInvite(one.id(), two.counterpart(), null, one.issuer())))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(linkCount(one.id())).isZero();
  }

  @Test
  void 수리_건_점검_기록으로_초대_링크를_만든다() {
    Room room = room();
    UUID repair = repairCase(room.id());
    UUID inspection = inspection(room.id());

    UUID repairLink =
        inTx(() ->
            inviteLinks.issueInvite(
                room.id(),
                room.counterpart(),
                RequestTarget.of(TargetKind.REPAIR_CASE, repair),
                room.issuer()));
    UUID inspectionLink =
        inTx(() ->
            inviteLinks.issueInvite(
                room.id(),
                room.counterpart(),
                RequestTarget.of(TargetKind.INSPECTION, inspection),
                room.issuer()));

    assertThat(jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", repairLink))
        .containsEntry("repair_case_id", repair)
        .containsEntry("inspection_id", null);
    assertThat(jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", inspectionLink))
        .containsEntry("repair_case_id", null)
        .containsEntry("inspection_id", inspection);
  }

  /** 기록 ID는 요청에서 온 값이라 없거나 다른 호실의 기록이면 같은 {@code NOT_FOUND}다(#163). */
  @Test
  void 없거나_다른_호실의_기록이면_NOT_FOUND이고_링크가_남지_않는다() {
    Room one = room();
    Room two = room();
    List<RequestTarget> wrong =
        List.of(
            RequestTarget.of(TargetKind.INSPECTION, inspection(two.id())),
            RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID()),
            RequestTarget.of(TargetKind.REPAIR_CASE, repairCase(two.id())),
            RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID()));

    for (RequestTarget record : wrong) {
      assertThatThrownBy(() ->
              inTx(
                  () -> inviteLinks.issueInvite(one.id(), one.counterpart(), record, one.issuer())))
          .as(record.toString())
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    assertThat(linkCount(one.id())).isZero();
  }

  /**
   * pipeline {@code save} 안에서 나면 {@code NOT_FOUND}가 그대로 올라오고 같은 트랜잭션의 행동 기록·링크가 모두 되돌아간다. 무결성 위반이
   * 아니라서 같은 키 재요청 찾기({@code replayIfDone})로도 가지 않는다. 호실 전체 롤백(자리·통지)은 초대 API(A) 통합 테스트가 본다.
   */
  @Test
  void pipeline_save에서_기록이_없으면_NOT_FOUND가_올라오고_행동과_링크가_남지_않는다() {
    Room room = room();
    StateStore<String> store =
        new StateStore<>() {
          @Override
          public String load(ActionCommand command) {
            return "";
          }

          @Override
          public SavedState save(ActionCommand command, String next) {
            inviteLinks.issueInvite(
                room.id(),
                room.counterpart(),
                RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID()),
                room.issuer());
            return SavedState.unchanged();
          }
        };
    Transitions<String> rules =
        new Transitions<>() {
          @Override
          public String initial() {
            return "";
          }

          @Override
          public String apply(String now, ActionEntry action) {
            return now;
          }
        };

    assertThatThrownBy(() ->
            pipeline.execute(
                new ActionCommand(
                    room.id(),
                    Actor.member(room.issuer()),
                    ActionType.CHANGE_INVITE_PHONE,
                    "invite-not-found-" + UUID.randomUUID(),
                    ActionTarget.party(room.counterpart()),
                    null,
                    null,
                    null,
                    null),
                store,
                rules))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    assertThat(linkCount(room.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE room_record_id = ?",
                Integer.class,
                room.id()))
        .isZero();
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
  void 만든_시각과_무효_시각은_시계가_아니라_그_행동의_시각이다() {
    Room room = room();

    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    assertThat(createdAt(link)).isEqualTo(AT);

    inTx(
        LATER,
        () ->
            inviteLinks.invalidateInvites(
                room.id(), room.counterpart(), LinkInvalidReason.REINVITED));
    assertThat(invalidatedInstant(link)).isEqualTo(LATER);

    // 이미 무효인 링크는 나중 행동(호실 전체·자리 단위)이 와도 처음 무효 시각이 남는다
    Instant later = LATER.plusSeconds(60);
    inTx(later, () -> inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED));
    inTx(
        later,
        () ->
            inviteLinks.invalidateInvites(
                room.id(), room.counterpart(), LinkInvalidReason.CONNECTED));
    assertThat(invalidatedInstant(link)).isEqualTo(LATER);
    assertThat(reason(link)).isEqualTo("REINVITED");
  }

  @Test
  void 실제_pipeline의_한_행동에서_옛_링크_무효와_새_링크_발급은_행동_기록과_같은_시각이다() {
    Room room = room();
    UUID old =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    AtomicReference<List<UUID>> closed = new AtomicReference<>();
    AtomicReference<UUID> fresh = new AtomicReference<>();
    // 번호 고치기(room, 아직 없음)가 save 안에서 부를 순서 ②·④만 흉내 낸다
    StateStore<String> store =
        new StateStore<>() {
          @Override
          public String load(ActionCommand command) {
            return "";
          }

          @Override
          public SavedState save(ActionCommand command, String next) {
            closed.set(
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED));
            fresh.set(inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
            return SavedState.unchanged();
          }
        };
    Transitions<String> rules =
        new Transitions<>() {
          @Override
          public String initial() {
            return "";
          }

          @Override
          public String apply(String now, ActionEntry action) {
            return now;
          }
        };

    pipeline.execute(
        new ActionCommand(
            room.id(),
            Actor.member(room.issuer()),
            ActionType.CHANGE_INVITE_PHONE,
            "invite-links-" + UUID.randomUUID(),
            ActionTarget.party(room.counterpart()),
            null,
            null,
            null,
            null),
        store,
        rules);

    Instant actionAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE room_record_id = ? AND type = ?",
                Timestamp.class,
                room.id(),
                ActionType.CHANGE_INVITE_PHONE.name())
            .toInstant();
    assertThat(closed.get()).containsExactly(old);
    assertThat(invalidatedInstant(old)).isEqualTo(actionAt);
    assertThat(createdAt(fresh.get())).isEqualTo(actionAt);
  }

  @Test
  void 트랜잭션은_있어도_save_밖이면_개발_오류고_링크가_남지_않는다() {
    Room room = room();
    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    TransactionTemplate tx = new TransactionTemplate(txManager);

    assertThatThrownBy(() ->
            tx.execute(
                status -> inviteLinks.issueInvite(room.id(), room.mine(), null, room.issuer())))
        .isInstanceOf(IllegalStateException.class);
    // 다시 쓸 링크가 있어도(새로 만들지 않아도) 창구가 닫혀 있으면 바로 실패한다
    assertThatThrownBy(() ->
            tx.execute(status ->
                inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            tx.execute(status ->
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            tx.execute(status ->
                inviteLinks.invalidateInvites(room.id(), LinkInvalidReason.RESIDENCE_ENDED)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(linkCount(room.id())).isEqualTo(1);
    assertThat(reason(link)).isNull();
  }

  @Test
  void 일이_실패해도_행동_시각_창구는_닫혀_다음_요청으로_새지_않는다() {
    Room room = room();

    assertThatThrownBy(() ->
            inTx(() -> {
              inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer());
              throw new IllegalArgumentException("중간 실패");
            }))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(linkCount(room.id())).isZero();
    assertThatThrownBy(requestContext::at).isInstanceOf(IllegalStateException.class);
    // 창구가 남아 있었다면 다시 열 때 "pipeline 안에서 pipeline을 다시 부르지 않아요"로 실패한다
    UUID link =
        inTx(
            LATER,
            () -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    assertThat(createdAt(link)).isEqualTo(LATER);
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
        .executeWithoutResult(status ->
            RequestContextTestSupport.within(
                requestContext,
                AT,
                () -> {
                  inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer());
                  status.setRollbackOnly();
                  return null;
                }));

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

    List<UUID> closed =
        inTx(() ->
            inviteLinks.invalidateInvites(
                room.id(), room.counterpart(), LinkInvalidReason.REINVITED));

    assertThat(closed).containsExactly(theirs);
    assertThat(reason(theirs)).isEqualTo("REINVITED");
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, theirs))
        .isNotNull();
    assertThat(reason(mine)).isNull();
  }

  @Test
  void 자리_단위_무효는_이미_무효였던_링크까지_그_자리의_초대_링크_ID를_전부_돌려준다() {
    Room room = room();
    Room otherRoom = room();
    UUID inspection = inspection(room.id());
    UUID closedBefore =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    inTx(() ->
        inviteLinks.invalidateInvites(room.id(), room.counterpart(), LinkInvalidReason.CONNECTED));
    Object closedAt = invalidatedAt(closedBefore);
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
    UUID otherRoomLink =
        inTx(() ->
            inviteLinks.issueInvite(
                otherRoom.id(), otherRoom.counterpart(), null, otherRoom.issuer()));
    UUID settlement = settlementInvite(room);

    // 처음 무효 시각이 덮이지 않는지 보려고 나중 행동 시각으로 부른다
    List<UUID> returned =
        inTx(
            LATER,
            () ->
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED));

    assertThat(returned)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder(closedBefore, roomInvite, recordLink);
    assertThat(reason(roomInvite)).isEqualTo("REINVITED");
    assertThat(reason(recordLink)).isEqualTo("REINVITED");
    assertThat(reason(closedBefore)).isEqualTo("CONNECTED");
    assertThat(invalidatedAt(closedBefore)).isEqualTo(closedAt);
    assertThat(reason(otherSeat)).isNull();
    assertThat(reason(otherRoomLink)).isNull();
    assertThat(reason(settlement)).isNull();
  }

  @Test
  void 이미_무효인_링크만_있으면_그_ID만_돌려주고_처음_이유와_시각은_그대로다() {
    Room room = room();
    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    inTx(() ->
        inviteLinks.invalidateInvites(room.id(), room.counterpart(), LinkInvalidReason.CONNECTED));
    Object closedAt = invalidatedAt(link);

    List<UUID> returned =
        inTx(
            LATER,
            () ->
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED));

    assertThat(returned).containsExactly(link);
    assertThat(invalidatedInstant(link)).isEqualTo(AT);
    assertThat(reason(link)).isEqualTo("CONNECTED");
    assertThat(invalidatedAt(link)).isEqualTo(closedAt);
  }

  @Test
  void 자리에_초대_링크가_없으면_빈_목록이고_정산_초대_링크도_넣지_않는다() {
    Room room = room();
    UUID settlement = settlementInvite(room);

    assertThat(
            inTx(() ->
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED)))
        .isEmpty();
    assertThat(reason(settlement)).isNull();
  }

  @Test
  void 같은_트랜잭션에서_두_번_불러도_같은_집합이고_처음_이유가_남는다() {
    Room room = room();
    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));

    List<List<UUID>> both =
        inTx(() ->
            List.of(
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.REINVITED),
                inviteLinks.invalidateInvites(
                    room.id(), room.counterpart(), LinkInvalidReason.CONNECTED)));

    assertThat(both.get(0)).containsExactly(link);
    assertThat(both.get(1)).containsExactlyInAnyOrderElementsOf(both.get(0));
    assertThat(reason(link)).isEqualTo("REINVITED");
  }

  @Test
  void 부르는_쪽이_되돌리면_자리_단위_무효도_남지_않는다() {
    Room room = room();
    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));

    new TransactionTemplate(txManager)
        .executeWithoutResult(status ->
            RequestContextTestSupport.within(
                requestContext,
                AT,
                () -> {
                  inviteLinks.invalidateInvites(
                      room.id(), room.counterpart(), LinkInvalidReason.REINVITED);
                  status.setRollbackOnly();
                  return null;
                }));

    assertThat(reason(link)).isNull();
  }

  @Test
  void 같은_자리의_무효가_동시에_와도_같은_집합을_돌려주고_이유는_하나만_남는다() throws Exception {
    Room room = room();
    UUID link =
        inTx(() -> inviteLinks.issueInvite(room.id(), room.counterpart(), null, room.issuer()));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<List<UUID>>> results = new ArrayList<>();
      for (LinkInvalidReason reason :
          List.of(LinkInvalidReason.REINVITED, LinkInvalidReason.CONNECTED)) {
        results.add(
            pool.submit(() -> {
              start.await();
              return inTx(
                  () -> inviteLinks.invalidateInvites(room.id(), room.counterpart(), reason));
            }));
      }
      start.countDown();

      assertThat(results.get(0).get(30, TimeUnit.SECONDS)).containsExactly(link);
      assertThat(results.get(1).get(30, TimeUnit.SECONDS)).containsExactly(link);
    } finally {
      pool.shutdownNow();
    }
    assertThat(reason(link)).isIn("REINVITED", "CONNECTED");
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

  /** pipeline {@code save} 안처럼 트랜잭션과 행동 시각 창구를 함께 연다(행동 시각은 {@link #AT}). */
  private <T> T inTx(Supplier<T> work) {
    return inTx(AT, work);
  }

  private <T> T inTx(Instant at, Supplier<T> work) {
    return new TransactionTemplate(txManager)
        .execute(status -> RequestContextTestSupport.within(requestContext, at, work));
  }

  private Instant createdAt(UUID link) {
    return jdbc.queryForObject(
            "SELECT created_at FROM state.link WHERE id = ?", Timestamp.class, link)
        .toInstant();
  }

  private Instant invalidatedInstant(UUID link) {
    return jdbc.queryForObject(
            "SELECT invalidated_at FROM state.link WHERE id = ?", Timestamp.class, link)
        .toInstant();
  }

  private int linkCount(UUID room) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.link WHERE room_record_id = ?", Integer.class, room);
  }

  private Object invalidatedAt(UUID link) {
    return jdbc.queryForObject(
        "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, link);
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

  /** 수리 건 하나(첫 판 포함). 머리와 첫 판은 서로를 가리켜(지연 외래 키) 한 트랜잭션에 넣는다. */
  private UUID repairCase(UUID room) {
    UUID part = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    new TransactionTemplate(txManager)
        .executeWithoutResult(tx -> {
          jdbc.update(
              "INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)"
                  + " VALUES (?, ?, '방', '천장', 0, CURRENT_TIMESTAMP, 0)",
              part,
              room);
          jdbc.update(
              "INSERT INTO state.repair_case (id, room_record_id, current_content_id, status, defect_ack,"
                  + " money_status, created_by_side, created_at, version)"
                  + " VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)",
              id,
              room,
              content);
          jdbc.update(
              "INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no, room_part_id,"
                  + " title, created_by_side, created_at)"
                  + " VALUES (?, ?, ?, 1, ?, '천장 누수', 'TENANT', CURRENT_TIMESTAMP)",
              content,
              room,
              id,
              part);
        });
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
