package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
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
import com.chilsuitjo.back.member.service.MemberQueryService;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 가입 전 링크 열람({@code POST /api/public/links/view}, 06 4-4, LINK-1·2·3).
 *
 * <p>어떤 이유로 못 열든(없는 토큰, 무효 링크, 정산 완료·합류 전 기록인 호실) 모두 같은 {@code LINK_INVALID}다. 이유를 다르게 답하면 링크를 가진
 * 사람에게 호실 상태가 드러난다(LINK-2, decisions 2026-11-19). 호실 상태로 막힐 때는 pipeline을 부르지 않아 열람 기록도 남지 않는다.
 *
 * <p>pipeline이 트랜잭션을 맡아서 이 서비스에는 {@code @Transactional}을 두지 않는다(09 3-1).
 */
@Service
public class LinkViewService {

  /** 호실 상태 때문에 pipeline이 거절했을 때. 미리 거르지만 그사이 상태가 바뀐 경우를 같은 응답으로 덮는다. */
  private static final Set<ErrorCode> HIDDEN_ROOM_ERRORS =
      Set.of(ErrorCode.SETTLED, ErrorCode.JOINED_RECORD);

  private final LinkRepository links;
  private final LinkContentSource content;
  private final MemberQueryService members;
  private final ActionPipeline pipeline;
  private final Clock clock;
  private final Duration viewDedupe;

  public LinkViewService(
      LinkRepository links,
      LinkContentSource content,
      MemberQueryService members,
      ActionPipeline pipeline,
      Clock clock,
      @Value("${app.link.view-dedupe:10m}") Duration viewDedupe) {
    this.links = links;
    this.content = content;
    this.members = members;
    this.pipeline = pipeline;
    this.clock = clock;
    this.viewDedupe = viewDedupe;
  }

  public LinkViewResponse view(LinkViewRequest request) {
    String token = request == null ? null : request.token();
    if (token == null || token.isBlank()) {
      throw invalid();
    }
    Link link = links.findByTokenHash(LinkToken.hash(token)).orElseThrow(LinkViewService::invalid);
    if (link.isInvalidated()) {
      links.countFailedView(link.getId());
      throw invalid();
    }
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
   * link-view:<링크>:<구간>}).
   */
  private void recordView(Link link, LinkRoom room) {
    long window = clock.instant().getEpochSecond() / viewDedupe.toSeconds();
    String key = "link-view:" + link.getId() + ":" + window;
    try {
      pipeline.executeAll(
          ActionBatch.of(
                  link.getRoomRecordId(), Actor.link(link.getId(), room.recipientSide()), key)
              .record(ActionType.LINK_VIEW, Map.of()));
    } catch (BusinessException e) {
      if (HIDDEN_ROOM_ERRORS.contains(e.errorCode())) {
        throw invalid();
      }
      throw e;
    }
  }

  private LinkViewResponse response(Link link, LinkRoom room) {
    String inviter = members.maskedName(link.getIssuedByMemberId()).orElse(null);
    if (link.getKind() == LinkKind.SETTLEMENT_INVITE) {
      return new LinkViewResponse(
          LinkViewKind.SETTLEMENT_INVITE,
          room.sido(),
          room.sigungu(),
          room.dong(),
          inviter,
          room.recipientSide(),
          null,
          false);
    }
    TargetKind kind = targetKind(link);
    if (kind == null) {
      return new LinkViewResponse(
          LinkViewKind.ROOM_INVITE,
          room.sido(),
          room.sigungu(),
          room.dong(),
          inviter,
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
        inviter,
        room.recipientSide(),
        record,
        content.hasMoney(link.getRoomRecordId(), kind, recordId));
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
}
