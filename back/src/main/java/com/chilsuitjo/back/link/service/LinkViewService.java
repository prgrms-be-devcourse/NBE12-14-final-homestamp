package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.ActionReader;
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
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.link.rule.LinkToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 가입 전 링크 열람({@code POST /api/public/links/view}, 06 4-4, LINK-1·2·3).
 *
 * <p>어떤 이유로 못 열든(없는 토큰, 무효 링크, 정산 완료·합류 전 기록인 호실, 다른 도메인 조회나 pipeline의 거절) 모두 같은 {@code
 * LINK_INVALID}다. 이유를 다르게 답하면 링크를 가진 사람에게 호실 상태가 드러난다(LINK-2, decisions 2026-11-19, 원칙 8 "실패하면 막히는
 * 쪽으로"). 그래서 링크를 찾은 뒤의 업무 오류({@link BusinessException})는 모두 한 곳({@link #view})에서 덮고, 원래 코드는 로그에만
 * 남긴다. 예상하지 못한 서버 오류(500)는 덮지 않는다(호실 상태를 드러내지 않고, 덮으면 5xx 장애 알림이 숨는다, 04 11장).
 *
 * <p>호실 상태로 막힐 때는 pipeline을 부르지 않아 열람 기록도 남지 않는다. pipeline이 트랜잭션을 맡아서 이 서비스에는
 * {@code @Transactional}을 두지 않는다(09 3-1).
 */
@Service
public class LinkViewService {

  private static final Logger log = LoggerFactory.getLogger(LinkViewService.class);

  private final LinkRepository links;
  private final LinkContentSource content;
  private final ActionPipeline pipeline;
  private final ActionReader actions;
  private final Clock clock;
  private final Duration viewDedupe;

  public LinkViewService(
      LinkRepository links,
      LinkContentSource content,
      ActionPipeline pipeline,
      ActionReader actions,
      Clock clock,
      @Value("${app.link.view-dedupe:10m}") Duration viewDedupe) {
    this.links = links;
    this.content = content;
    this.pipeline = pipeline;
    this.actions = actions;
    this.clock = clock;
    if (viewDedupe == null
        || viewDedupe.isZero()
        || viewDedupe.isNegative()
        || viewDedupe.getNano() != 0) {
      throw new IllegalArgumentException("app.link.view-dedupe는 1초 이상의 정수 초여야 해요");
    }
    this.viewDedupe = viewDedupe;
  }

  public LinkViewResponse view(LinkViewRequest request) {
    String token = request == null ? null : request.token();
    if (token == null || token.isBlank()) {
      throw invalid();
    }
    Link link = links.findByTokenHash(LinkToken.hash(token)).orElseThrow(LinkViewService::invalid);
    try {
      return viewFound(link);
    } catch (BusinessException e) {
      // 링크를 찾았는데 못 열었다. 05 link.failed_view_count는 "링크를 식별할 수 있는 요청의 실패"를 모두 센다
      // (무효 링크·호실 상태·내용 없음·pipeline 거절). 이 횟수만으로 링크를 무효로 하지는 않는다.
      links.countFailedView(link.getId());
      if (e.errorCode() != ErrorCode.LINK_INVALID) {
        // 원래 이유는 운영만 본다. 토큰·번호는 남기지 않고 ID는 앞 8자리만(04 11장 "로그")
        log.info(
            "링크 열람 실패를 LINK_INVALID로 덮음: link={}, code={}",
            link.getId().toString().substring(0, 8),
            e.errorCode());
      }
      throw invalid();
    }
  }

  /** 토큰으로 링크를 찾은 뒤. 여기서 나온 업무 오류는 {@link #view}가 모두 {@code LINK_INVALID}로 덮고 실패 횟수를 센다. */
  private LinkViewResponse viewFound(Link link) {
    LinkRoom room =
        content
            .room(link.getRoomRecordId(), link.getPartyId())
            .orElseThrow(LinkViewService::invalid);
    if (room.hiddenFromLink()) {
      throw invalid();
    }
    // 보여 줄 내용을 먼저 읽는다. 못 읽으면(기록이 그 호실에 없음) 열람 기록을 남기지 않고 LINK_INVALID다.
    LinkViewResponse response = response(link, room);
    recordView(link, room);
    return response;
  }

  /**
   * 링크 열람 행동. 같은 링크는 {@code link.view.dedupe}(10분) 안에서 한 줄이다(06 4-4, 멱등 키 {@code
   * link-view:<링크>:<구간>}). pipeline이 호실을 잠근 뒤 링크를 잠그고 무효 여부를 다시 확인한다. 멱등 replay도 이 검증 뒤에만 수행한다.
   */
  private void recordView(Link link, LinkRoom room) {
    var observedAt = clock.instant();
    long window = observedAt.getEpochSecond() / viewDedupe.toSeconds();
    String key = "link-view:" + link.getId() + ":" + window;
    try {
      pipeline.executeAll(
          ActionBatch.of(
                  link.getRoomRecordId(), Actor.link(link.getId(), room.recipientSide()), key)
              .beforeReplayCheck(() -> requireCurrentLink(link, observedAt))
              .record(
                  ActionType.LINK_VIEW,
                  done -> ActionTarget.of(TargetKind.LINK, link.getId()),
                  Map.of()));
    } catch (RecentLinkView ignored) {
      // 링크 행과 호실 행을 잠근 뒤 확인한 최근 열람이다. 성공 응답만 돌리고 새 행동은 남기지 않는다.
    }
  }

  private void requireCurrentLink(Link resolved, Instant observedAt) {
    Link current = links.findByIdForUpdate(resolved.getId()).orElseThrow(LinkViewService::invalid);
    if (current.isInvalidated() || !current.getRoomRecordId().equals(resolved.getRoomRecordId())) {
      throw invalid();
    }
    Instant cutoff = observedAt.minus(viewDedupe);
    boolean recordedRecently =
        actions.findByTarget(ActionTarget.of(TargetKind.LINK, resolved.getId())).stream()
            .anyMatch(action ->
                action.type() == ActionType.LINK_VIEW && !action.createdAt().isBefore(cutoff));
    if (recordedRecently) {
      throw new RecentLinkView();
    }
  }

  private LinkViewResponse response(Link link, LinkRoom room) {
    if (link.getKind() == LinkKind.SETTLEMENT_INVITE) {
      // "퇴거 정산 확인 요청이 있어요"와 가입 버튼만(LINK-3a). 위치·측·금액·항목은 주지 않는다.
      return new LinkViewResponse(
          LinkViewKind.SETTLEMENT_INVITE, null, null, null, null, null, false);
    }
    TargetKind kind = targetKind(link);
    if (kind == null) {
      return new LinkViewResponse(
          LinkViewKind.ROOM_INVITE,
          room.sido(),
          room.sigungu(),
          room.dong(),
          room.recipientSide(),
          null,
          false);
    }
    UUID recordId =
        kind == TargetKind.REPAIR_CASE ? link.getRepairCaseId() : link.getInspectionId();
    LinkRecordResponse record =
        content
            .record(link.getRoomRecordId(), kind, recordId)
            .orElseThrow(LinkViewService::invalid);
    return new LinkViewResponse(
        LinkViewKind.RECORD,
        room.sido(),
        room.sigungu(),
        room.dong(),
        room.recipientSide(),
        record,
        kind == TargetKind.REPAIR_CASE && content.hasMoney(link.getRoomRecordId(), recordId));
  }

  private static TargetKind targetKind(Link link) {
    if (link.getRepairCaseId() != null) {
      return TargetKind.REPAIR_CASE;
    }
    if (link.getInspectionId() != null) {
      return TargetKind.INSPECTION;
    }
    return null;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.LINK_INVALID);
  }

  private static final class RecentLinkView extends RuntimeException {
    private RecentLinkView() {
      super(null, null, false, false);
    }
  }
}
