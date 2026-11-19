package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipelineFixture;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.link.dto.LinkViewKind;
import com.chilsuitjo.back.link.dto.LinkViewRequest;
import com.chilsuitjo.back.link.dto.LinkViewResponse;
import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.repository.LinkRepository;
import com.chilsuitjo.back.link.rule.LinkInvalidReason;
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.member.service.FakeMemberQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class LinkViewServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

  private final ActionPipelineFixture fx =
      new ActionPipelineFixture(Clock.fixed(NOW, ZoneOffset.UTC));
  private final LinkRepository links = mock(LinkRepository.class);
  private final FakeLinkContentSource content = new FakeLinkContentSource();
  private final FakeMemberQueryService members = new FakeMemberQueryService();
  private final MutableClock clock = new MutableClock(NOW);
  private final LinkViewService service =
      new LinkViewService(links, content, members, fx.pipeline(), clock, Duration.ofMinutes(10));

  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID partyId = UUID.randomUUID();
  private final UUID inviterId = UUID.randomUUID();
  private final UUID repairCaseId = UUID.randomUUID();

  {
    members.seed(inviterId, "정서윤");
  }

  @Test
  void 기록_링크는_그_기록과_돈이_있다는_것만_보이고_열람이_남는다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), true);

    LinkViewResponse response = service.view(new LinkViewRequest(token));

    assertThat(response.kind()).isEqualTo(LinkViewKind.RECORD);
    assertThat(response.dong()).isEqualTo("역삼동");
    assertThat(response.inviterMaskedName()).isEqualTo("정*윤");
    assertThat(response.yourSide()).isEqualTo(PartySide.LANDLORD);
    assertThat(response.record().title()).isEqualTo("욕실 천장 누수");
    assertThat(response.hasMoney()).isTrue();
    assertThat(viewActions()).hasSize(1);
  }

  @Test
  void 같은_링크를_10분_안에_다시_열면_열람은_한_줄이다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);

    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(1);
  }

  @Test
  void 호실만_초대한_링크는_안내만_보이고_기록은_없다() {
    String token = issue(LinkKind.INVITE, null);
    openRoom();

    LinkViewResponse response = service.view(new LinkViewRequest(token));

    assertThat(response.kind()).isEqualTo(LinkViewKind.ROOM_INVITE);
    assertThat(response.record()).isNull();
    assertThat(response.hasMoney()).isFalse();
  }

  @Test
  void 정산_초대_링크는_안내만_보인다() {
    String token = issue(LinkKind.SETTLEMENT_INVITE, null);
    openRoom();

    LinkViewResponse response = service.view(new LinkViewRequest(token));

    assertThat(response.kind()).isEqualTo(LinkViewKind.SETTLEMENT_INVITE);
    assertThat(response.record()).isNull();
  }

  @Test
  void 없는_토큰과_빈_토큰은_LINK_INVALID() {
    when(links.findByTokenHash(any())).thenReturn(Optional.empty());

    assertLinkInvalid(() -> service.view(new LinkViewRequest("없는토큰")));
    assertLinkInvalid(() -> service.view(new LinkViewRequest(" ")));
    assertLinkInvalid(() -> service.view(new LinkViewRequest(null)));
  }

  @Test
  void 무효_링크는_LINK_INVALID이고_실패_횟수를_센다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    link.invalidate(LinkInvalidReason.REINVITED, NOW.minusSeconds(60));
    String token = stub(link);
    openRoom();

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 합류_전_기록인_호실은_같은_LINK_INVALID이고_열람이_남지_않는다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", false, true));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    verify(links, never()).countFailedView(any());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 정산_완료_호실은_같은_LINK_INVALID다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", true, false));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 미리_거른_뒤_호실이_정산_완료로_바뀌어도_LINK_INVALID다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(new RoomGate(roomRecordId, null, true, false, false, true));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 공유한_기록이_그_호실에_없으면_LINK_INVALID다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 요청을_문자로_찍어도_토큰은_보이지_않는다() {
    assertThat(new LinkViewRequest("비밀토큰").toString()).doesNotContain("비밀토큰");
  }

  private String issue(LinkKind kind, UUID repairCase) {
    return stub(newLink(kind, repairCase));
  }

  private Link newLink(LinkKind kind, UUID repairCase) {
    return new Link(
        UUID.randomUUID(),
        roomRecordId,
        kind,
        partyId,
        repairCase,
        null,
        "hash-" + UUID.randomUUID(),
        inviterId,
        NOW.minusSeconds(3600));
  }

  /** 새 토큰을 만들고, 그 해시로 찾으면 이 링크가 나오게 한다. */
  private String stub(Link link) {
    String token = LinkToken.newToken();
    when(links.findByTokenHash(LinkToken.hash(token))).thenReturn(Optional.of(link));
    return token;
  }

  private void openRoom() {
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", false, false));
  }

  private static LinkRecordResponse repairRecord() {
    return new LinkRecordResponse(
        TargetKind.REPAIR_CASE,
        "욕실 천장",
        "욕실 천장 누수",
        "물이 떨어져요",
        List.of("확인 기다림"),
        List.of(),
        List.of());
  }

  private List<ActionEntry> viewActions() {
    return fx.actions().findByRoom(roomRecordId).stream()
        .filter(entry -> entry.type() == ActionType.LINK_VIEW)
        .toList();
  }

  private static void assertLinkInvalid(Executable call) {
    assertThatThrownBy(call::execute)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
  }

  /** 테스트에서 시각을 옮길 수 있는 시계. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
