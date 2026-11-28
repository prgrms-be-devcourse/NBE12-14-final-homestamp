package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.ActionPipelineFixture;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
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
import com.chilsuitjo.back.link.repository.LinkViewStateStore;
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import com.chilsuitjo.back.notification.service.NoticeWaitStart.EvidenceNotRecorded;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
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

  private final LinkRepository links = mock(LinkRepository.class);
  private final FakeLinkContentSource content = new FakeLinkContentSource();
  private final MutableClock clock = new MutableClock(NOW);
  private final ActionPipelineFixture fx = new ActionPipelineFixture(clock);
  private final NoticeWaitStart waitStart = mock(NoticeWaitStart.class);
  private final LinkViewStateStore viewStore =
      new LinkViewStateStore(waitStart, fx.requestContext());
  private final LinkViewService service =
      new LinkViewService(
          links, content, fx.pipeline(), fx.actions(), viewStore, clock, Duration.ofMinutes(10));

  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID partyId = UUID.randomUUID();
  private final UUID inviterId = UUID.randomUUID();
  private final UUID repairCaseId = UUID.randomUUID();

  @Test
  void 기록_링크는_그_기록과_돈이_있다는_것만_보이고_열람이_남는다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), true);

    LinkViewResponse response = service.view(new LinkViewRequest(token));

    assertThat(response.kind()).isEqualTo(LinkViewKind.RECORD);
    assertThat(response.dong()).isEqualTo("역삼동");
    assertThat(response.yourSide()).isEqualTo(PartySide.LANDLORD);
    assertThat(response.record().title()).isEqualTo("욕실 천장 누수");
    assertThat(response.hasMoney()).isTrue();
    assertThat(viewActions()).hasSize(1);
    ActionTarget target = ActionTarget.of(TargetKind.LINK, link.getId());
    assertThat(viewActions().getFirst().target()).isEqualTo(target);
    assertThat(fx.actions().findByTarget(target)).hasSize(1);
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
  void 열_분_구간_경계에서도_실제_열람_간격이_짧으면_한_줄이다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    clock.advance(Duration.ofMinutes(9).plusSeconds(59));

    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofSeconds(2));
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
  void 점검_링크는_hasMoney_false이며_돈_조회는_하지_않는다() {
    UUID inspectionId = UUID.randomUUID();
    Link link = newLink(LinkKind.INVITE, null, inspectionId);
    String token = stub(link);
    LinkContentSource source = mock(LinkContentSource.class);
    when(source.room(roomRecordId, partyId))
        .thenReturn(
            Optional.of(
                new LinkRoom(
                    PartySide.LANDLORD,
                    "서울특별시",
                    "강남구",
                    "역삼동",
                    false,
                    false,
                    PartyStatus.INVITED,
                    RoomStatus.LIVING)));
    when(source.record(roomRecordId, TargetKind.INSPECTION, inspectionId, PartySide.LANDLORD))
        .thenReturn(Optional.of(inspectionRecord()));
    LinkViewService inspectionService =
        new LinkViewService(
            links, source, fx.pipeline(), fx.actions(), viewStore, clock, Duration.ofMinutes(10));

    LinkViewResponse response = inspectionService.view(new LinkViewRequest(token));

    assertThat(response.hasMoney()).isFalse();
    verify(source, never()).hasMoney(any(), any());
  }

  @Test
  void 무효화_후_같은_멱등키로_다시와도_저장된_성공을_재생하지_않는다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    service.view(new LinkViewRequest(token));
    link.invalidate(LinkInvalidReason.REINVITED, NOW.plusSeconds(1));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));

    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).hasSize(1);
  }

  // ---- 최근 열람이 있어도 잠근 뒤의 호실 접근 검사를 건너뛰지 않는다(#89 리뷰) ----

  @Test
  void 최근_열람이_있어도_그_뒤_정산_완료가_되면_다시_열지_못한다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    service.view(new LinkViewRequest(token));
    // 미리 거르는 조회(content.room)는 아직 열린 호실로 읽고, 잠근 뒤의 호실은 정산 완료다
    fx.gates().putRoom(new RoomGate(roomRecordId, null, true, false, false, true));
    clock.advance(Duration.ofMinutes(3));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));

    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).hasSize(1);
  }

  @Test
  void 최근_열람이_있어도_그_뒤_합류_전_기록이_되면_다시_열지_못한다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    service.view(new LinkViewRequest(token));
    fx.gates().putRoom(new RoomGate(roomRecordId, null, false, true, false, false));
    clock.advance(Duration.ofMinutes(3));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));

    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).hasSize(1);
  }

  @Test
  void 같은_멱등_구간에_다시_와도_정산_완료_뒤에는_저장된_성공을_재생하지_않는다() {
    // 최근 열람 확인을 건너뛰어도(구간 0초 전) 같은 키의 재생이 잠근 뒤 호실 검사를 앞지르지 않는다
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    service.view(new LinkViewRequest(token));
    fx.gates().putRoom(new RoomGate(roomRecordId, null, true, false, false, true));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));

    assertThat(viewActions()).hasSize(1);
  }

  @Test
  void 최근_열람이_있고_호실이_그대로면_다시_열어도_성공하고_한_줄이다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));

    LinkViewResponse again = service.view(new LinkViewRequest(token));

    assertThat(again.kind()).isEqualTo(LinkViewKind.RECORD);
    assertThat(viewActions()).hasSize(1);
    verify(links, never()).countFailedView(any());
  }

  // ---- 최근 열람 예외: 아직 전달 증거가 없는 보낸 문자가 있으면 한 줄을 더 남긴다(06 LINK-1a 예외, NOTI-3b) ----

  @Test
  void 최근_열람_안이라도_새_증거_후보가_있으면_그_통지_키로_한_줄을_더_남긴다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    // ID 순서로 읽은 후보(NoticeWaitStart.unviewedSent). 키는 그 첫 번째다
    List<UUID> candidates = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(waitStart.unviewedSent(any(), any(), any())).thenReturn(candidates);

    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(2);
    assertThat(viewActions().get(1).idempotencyKey())
        .isEqualTo("link-view:n:" + candidates.getFirst());
    // 첫 열람 한 번 + 예외 줄 한 번
    verify(waitStart, times(2)).linkViewed(any(), any(), any(), any());
  }

  @Test
  void 증거_키의_저장이_그_통지를_처리하지_못하면_키도_줄도_남기지_않고_다시_정해_남긴다() {
    // 서버 시계가 뒤로 가 행동 시각이 그 통지의 보낸 시각보다 앞선 경우. 키가 소비되면 그 뒤 열람이 재생만 해서 증거가 영구히 빠진다
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    UUID sent = UUID.randomUUID();
    when(waitStart.unviewedSent(any(), any(), any())).thenReturn(List.of(sent));
    when(waitStart.linkViewed(any(), any(), any(), eq(sent)))
        .thenThrow(new EvidenceNotRecorded())
        .thenReturn(1);

    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(2);
    assertThat(viewActions().get(1).idempotencyKey()).isEqualTo("link-view:n:" + sent);
  }

  @Test
  void 다시_정해도_증거를_남기지_못하면_열람은_성공하고_키는_다음_열람에_남는다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    UUID sent = UUID.randomUUID();
    when(waitStart.unviewedSent(any(), any(), any())).thenReturn(List.of(sent));
    when(waitStart.linkViewed(any(), any(), any(), eq(sent)))
        .thenThrow(new EvidenceNotRecorded(), new EvidenceNotRecorded())
        .thenReturn(1);

    LinkViewResponse failed = service.view(new LinkViewRequest(token));
    assertThat(failed.kind()).isEqualTo(LinkViewKind.RECORD);
    assertThat(viewActions()).hasSize(1);

    // 키가 쓰이지 않았으므로 다음 열람이 같은 키로 증거를 남긴다
    service.view(new LinkViewRequest(token));
    assertThat(viewActions()).hasSize(2);
    assertThat(viewActions().get(1).idempotencyKey()).isEqualTo("link-view:n:" + sent);
  }

  @Test
  void 잠그기_전에_없던_후보가_잠근_뒤_보이면_구간_키를_쓰지_않고_다시_정해_한_줄을_남긴다() {
    // 구간 키로 들어가면 앞 열람 줄이 재생돼 save가 불리지 않는다. 잠근 뒤 확인이 멈추고 증거 키로 다시 보낸다
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    UUID late = UUID.randomUUID();
    when(waitStart.unviewedSent(any(), any(), any()))
        .thenReturn(List.of(), List.of(late), List.of(late), List.of(late));

    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(2);
    assertThat(viewActions().get(1).idempotencyKey()).isEqualTo("link-view:n:" + late);
    // 첫 열람 한 번 + 예외 줄 한 번
    verify(waitStart, times(2)).linkViewed(any(), any(), any(), any());
  }

  @Test
  void 키의_후보가_잠그는_사이_처리되고_다른_후보가_남으면_그_후보_키로_다시_정한다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    UUID gone = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(waitStart.unviewedSent(any(), any(), any()))
        .thenReturn(List.of(gone), List.of(next), List.of(next), List.of(next));

    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(2);
    assertThat(viewActions().get(1).idempotencyKey()).isEqualTo("link-view:n:" + next);
    verify(waitStart, times(2)).linkViewed(any(), any(), any(), any());
  }

  @Test
  void 다시_정해도_후보가_또_바뀌면_열람은_성공하지만_새_줄도_증거도_없다() {
    // 재시도는 한 번뿐이다. 이 요청은 증거를 남기지 못하고 다음 열람이나 전달 결과에 맡긴다(decisions 2026-11-28)
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    UUID late = UUID.randomUUID();
    when(waitStart.unviewedSent(any(), any(), any()))
        .thenReturn(List.of(), List.of(late), List.of(), List.of(late));

    LinkViewResponse again = service.view(new LinkViewRequest(token));

    assertThat(again.kind()).isEqualTo(LinkViewKind.RECORD);
    assertThat(viewActions()).hasSize(1);
    verify(waitStart, times(1)).linkViewed(any(), any(), any(), any());
  }

  @Test
  void 키의_후보가_잠그는_사이_다른_열람으로_처리됐고_남은_후보가_없으면_새_줄이_없다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(RoomGate.open(roomRecordId, null));
    service.view(new LinkViewRequest(token));
    clock.advance(Duration.ofMinutes(3));
    when(waitStart.unviewedSent(any(), any(), any()))
        .thenReturn(List.of(UUID.randomUUID()), List.of());

    service.view(new LinkViewRequest(token));

    assertThat(viewActions()).hasSize(1);
    // 첫 열람의 한 번뿐이다
    verify(waitStart, times(1)).linkViewed(any(), any(), any(), any());
  }

  @Test
  void 멱등_구간이_1초보다_짧으면_시작할_수_없다() {
    assertThatThrownBy(() ->
            new LinkViewService(
                links,
                content,
                fx.pipeline(),
                fx.actions(),
                viewStore,
                clock,
                Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.link.view-dedupe");
  }

  @Test
  void 정산_초대_링크는_안내만_보인다() {
    String token = issue(LinkKind.SETTLEMENT_INVITE, null);
    openRoom();

    LinkViewResponse response = service.view(new LinkViewRequest(token));

    assertThat(response.kind()).isEqualTo(LinkViewKind.SETTLEMENT_INVITE);
    assertThat(response.record()).isNull();
    assertThat(response.dong()).isNull();
    assertThat(response.yourSide()).isNull();
  }

  @Test
  void 없는_토큰과_빈_토큰은_LINK_INVALID() {
    when(links.findByTokenHash(any())).thenReturn(Optional.empty());

    assertLinkInvalid(() -> service.view(new LinkViewRequest("없는토큰")));
    assertLinkInvalid(() -> service.view(new LinkViewRequest(" ")));
    assertLinkInvalid(() -> service.view(new LinkViewRequest(null)));
    // 링크를 특정할 수 없어 셀 행이 없다. 반복 조회는 nginx IP 제한이 막는다(05 link.failed_view_count, 04 4-3)
    verify(links, never()).countFailedView(any());
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
  void 미리_본_뒤_잠그기_전에_자리가_거절됨이_되면_잠근_뒤_다시_읽어_LINK_INVALID이고_열람이_남지_않는다() {
    // 거절·연결·거주 종료도 호실을 잠그고 저장한다. 미리 본 자리 상태는 잠그기 전 값이라 pipeline 접근 검증에서 다시 읽는다
    Link link = newLink(LinkKind.INVITE, null);
    String token = stub(link);
    LinkContentSource source = mock(LinkContentSource.class);
    when(source.room(roomRecordId, partyId))
        .thenReturn(Optional.of(seat(PartyStatus.INVITED, RoomStatus.LIVING)))
        .thenReturn(Optional.of(seat(PartyStatus.REJECTED, RoomStatus.LIVING)));
    LinkViewService racing =
        new LinkViewService(
            links, source, fx.pipeline(), fx.actions(), viewStore, clock, Duration.ofMinutes(10));

    assertLinkInvalid(() -> racing.view(new LinkViewRequest(token)));
    verify(source, times(2)).room(roomRecordId, partyId);
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 정산_초대_링크에는_초대_링크의_자리_호실_조건을_쓰지_않는다() {
    Link link = newLink(LinkKind.SETTLEMENT_INVITE, null);
    String token = stub(link);
    content.seedRoom(roomRecordId, partyId, seat(PartyStatus.NUMBER_ONLY, RoomStatus.ENDED));

    assertThat(service.view(new LinkViewRequest(token)).kind())
        .isEqualTo(LinkViewKind.SETTLEMENT_INVITE);
  }

  private static LinkRoom seat(PartyStatus seat, RoomStatus room) {
    return new LinkRoom(PartySide.LANDLORD, "서울특별시", "강남구", "역삼동", false, false, seat, room);
  }

  @Test
  void 합류_전_기록인_호실은_같은_LINK_INVALID이고_열람이_남지_않는다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(
            PartySide.LANDLORD,
            "서울특별시",
            "강남구",
            "역삼동",
            false,
            true,
            PartyStatus.INVITED,
            RoomStatus.LIVING));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    // 링크를 식별한 실패라 센다(05 link.failed_view_count)
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 정산_완료_호실은_같은_LINK_INVALID이고_실패_횟수를_센다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(
            PartySide.LANDLORD,
            "서울특별시",
            "강남구",
            "역삼동",
            true,
            false,
            PartyStatus.INVITED,
            RoomStatus.LIVING));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 미리_거른_뒤_호실이_정산_완료로_바뀌어도_LINK_INVALID이고_실패_횟수를_센다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);
    fx.gates().putRoom(new RoomGate(roomRecordId, null, true, false, false, true));

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 공유한_기록이_그_호실에_없으면_LINK_INVALID이고_실패_횟수를_센다() {
    Link link = newLink(LinkKind.INVITE, repairCaseId);
    String token = stub(link);
    openRoom();

    assertLinkInvalid(() -> service.view(new LinkViewRequest(token)));
    verify(links).countFailedView(link.getId());
    assertThat(viewActions()).isEmpty();
  }

  @Test
  void 열람에_성공하면_실패_횟수를_세지_않는다() {
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);

    service.view(new LinkViewRequest(token));

    verify(links, never()).countFailedView(any());
  }

  // ---- 링크를 연 사람에게 실패 이유를 드러내지 않는다(06 LINK-2, 원칙 8) ----

  @Test
  void 다른_도메인_조회가_오류를_던져도_LINK_INVALID다() {
    LinkContentSource failing = mock(LinkContentSource.class);
    when(failing.room(any(), any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
    LinkViewService withFailingContent =
        new LinkViewService(
            links, failing, fx.pipeline(), fx.actions(), viewStore, clock, Duration.ofMinutes(10));
    String token = issue(LinkKind.INVITE, repairCaseId);

    assertLinkInvalid(() -> withFailingContent.view(new LinkViewRequest(token)));
  }

  @Test
  void pipeline이_정산_완료_합류_말고_다른_이유로_거절해도_LINK_INVALID다() {
    ActionPipeline rejecting = mock(ActionPipeline.class);
    when(rejecting.executeAll(any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
    LinkViewService withRejectingPipeline =
        new LinkViewService(
            links, content, rejecting, fx.actions(), viewStore, clock, Duration.ofMinutes(10));
    String token = issue(LinkKind.INVITE, repairCaseId);
    openRoom();
    content.seedRecord(roomRecordId, repairCaseId, repairRecord(), false);

    assertLinkInvalid(() -> withRejectingPipeline.view(new LinkViewRequest(token)));
  }

  @Test
  void 예상하지_못한_서버_오류는_LINK_INVALID로_덮지_않는다() {
    // 500은 호실 상태를 드러내지 않고, 덮으면 5xx 장애 알림(04 11장)이 숨는다
    LinkContentSource broken = mock(LinkContentSource.class);
    when(broken.room(any(), any())).thenThrow(new IllegalStateException("고장"));
    LinkViewService withBrokenContent =
        new LinkViewService(
            links, broken, fx.pipeline(), fx.actions(), viewStore, clock, Duration.ofMinutes(10));
    String token = issue(LinkKind.INVITE, repairCaseId);

    assertThatThrownBy(() -> withBrokenContent.view(new LinkViewRequest(token)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 요청을_문자로_찍어도_토큰은_보이지_않는다() {
    assertThat(new LinkViewRequest("비밀토큰").toString()).doesNotContain("비밀토큰");
  }

  private String issue(LinkKind kind, UUID repairCase) {
    return stub(newLink(kind, repairCase));
  }

  private Link newLink(LinkKind kind, UUID repairCase) {
    return newLink(kind, repairCase, null);
  }

  private Link newLink(LinkKind kind, UUID repairCase, UUID inspectionId) {
    return new Link(
        UUID.randomUUID(),
        roomRecordId,
        kind,
        partyId,
        repairCase,
        inspectionId,
        "hash-" + UUID.randomUUID(),
        inviterId,
        NOW.minusSeconds(3600));
  }

  /** 새 토큰을 만들고, 그 해시로 찾으면 이 링크가 나오게 한다. */
  private String stub(Link link) {
    String token = LinkToken.newToken();
    when(links.findByTokenHash(LinkToken.hash(token))).thenReturn(Optional.of(link));
    when(links.findByIdForUpdate(link.getId())).thenReturn(Optional.of(link));
    return token;
  }

  private static LinkRecordResponse inspectionRecord() {
    return new LinkRecordResponse(
        TargetKind.INSPECTION, "욕실", null, null, List.of(), List.of(), List.of());
  }

  private void openRoom() {
    content.seedRoom(
        roomRecordId,
        partyId,
        new LinkRoom(
            PartySide.LANDLORD,
            "서울특별시",
            "강남구",
            "역삼동",
            false,
            false,
            PartyStatus.INVITED,
            RoomStatus.LIVING));
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
