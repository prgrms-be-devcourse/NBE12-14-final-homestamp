package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionEntry;
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
import com.chilsuitjo.back.link.repository.LinkViewStateStore;
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.link.rule.LinkViewTransitions;
import com.chilsuitjo.back.notification.service.NoticeWaitStart.EvidenceNotRecorded;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
  private final LinkViewStateStore viewStore;
  private final Clock clock;
  private final Duration viewDedupe;

  public LinkViewService(
      LinkRepository links,
      LinkContentSource content,
      ActionPipeline pipeline,
      ActionReader actions,
      LinkViewStateStore viewStore,
      Clock clock,
      @Value("${app.link.view-dedupe:10m}") Duration viewDedupe) {
    this.links = links;
    this.content = content;
    this.pipeline = pipeline;
    this.actions = actions;
    this.viewStore = viewStore;
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
   * 링크 열람 행동. 같은 링크는 {@code app.link.view-dedupe}(10분) 안에서 한 줄이다(06 4-4 LINK-1a, 멱등 키 {@code
   * link-view:<링크>:<구간>}). 예외: 그 링크로 보냈는데 아직 전달 증거가 없는 문자(SENT, {@link
   * LinkViewStateStore#unviewedSent})가 있으면 최근 열람 안이라도 한 줄을 더 남기고 그 줄의 {@code save}가 증거를 적는다(06
   * NOTI-3b, decisions 2026-11-28). 그 줄의 키는 {@code link-view:n:<가장 이른 후보 통지 ID>}다(행동 주체 키가 링크 단위라
   * 링크 ID는 넣지 않는다. 키 한도 {@code ActionBatch.MAX_KEY_LENGTH} 64자, 구간 키와 모양이 달라 겹치지 않는다). 그 통지는 이 줄로
   * 열람이 되어 다시 후보가 되지 않으므로 키가 다시 쓰이지 않고, 같은 증거로 동시에 온 요청은 같은 키·호실 잠금으로 한 줄만 남긴다.
   *
   * <p>키는 pipeline 밖에서(잠그기 전) 정해야 해서 미리 읽어 정하고, pipeline이 호실을 잠근 뒤 링크를 잠그고 다시 확인한다({@link
   * #requireCurrentLink}). 그사이 후보가 바뀌어 이 키로는 맞지 않으면(새 후보가 생겼는데 구간 키라 앞 줄이 재생될 수 있음, 키의 통지가 이미 열람됨)
   * 아무것도 쓰지 않고 {@link EvidenceChanged}로 멈춰 한 번만 다시 정한다. 최근 열람이고 후보가 없으면 지금처럼 새 줄 없이 성공이다.
   *
   * <p>최근 열람이라 새 줄을 남기지 않는 경우와 같은 키의 재생도 성공으로 끝나기 전에, 접근 검증이 붙은 요청이라 pipeline이 잠근 호실의 지금 상태로 호실 단위
   * 거절을 먼저 한다({@code ActionBatch.accessCheck}). 미리 거른 {@code content.room}은 잠그기 전 값이라, 그 사이 정산
   * 완료·합류가 되면 거기서 막히고 {@link #view}가 {@code LINK_INVALID}로 덮는다(#89 리뷰).
   */
  private void recordView(Link link, LinkRoom room) {
    Actor actor = Actor.link(link.getId(), room.recipientSide());
    for (int attempt = 1; ; attempt++) {
      Instant observedAt = clock.instant();
      List<UUID> candidates =
          viewStore.unviewedSent(link.getRoomRecordId(), link.getId(), micros(observedAt));
      UUID evidence = candidates.isEmpty() ? null : candidates.getFirst();
      String key =
          evidence == null
              ? "link-view:"
                  + link.getId()
                  + ":"
                  + observedAt.getEpochSecond() / viewDedupe.toSeconds()
              : LinkViewStateStore.EVIDENCE_KEY_PREFIX + evidence;
      try {
        pipeline.executeAll(
            ActionBatch.of(link.getRoomRecordId(), actor, key)
                .accessCheck(() -> requireCurrentLink(link, observedAt, evidence))
                // 열람 행동이 처음 남을 때 같은 트랜잭션에서 전달 증거를 적는다(LinkViewStateStore, 06 NOTI-3b)
                .step(
                    ActionType.LINK_VIEW,
                    done -> ActionTarget.of(TargetKind.LINK, link.getId()),
                    viewStore,
                    LinkViewTransitions.INSTANCE));
        return;
      } catch (RecentLinkView ignored) {
        // 링크 행과 호실 행을 잠근 뒤 확인한 최근 열람이고 새 증거가 없다. 성공 응답만 돌리고 새 행동은 남기지 않는다.
        return;
      } catch (EvidenceChanged | EvidenceNotRecorded changed) {
        // EvidenceNotRecorded: 저장 단계가 키의 통지를 열람으로 바꾸지 못해(서버 시계가 뒤로 감) 행동·키까지 함께 되돌아갔다. 키가 소비되지 않아
        // 다시 정한다
        if (attempt >= 2) {
          // 잠그는 사이 후보가 또 바뀌었다. 더 다시 하지 않고 다음 열람이나 전달 결과가 증거가 된다(열람은 그대로 성공, decisions 2026-11-28)
          log.warn(
              "링크 열람 증거를 두 번 다 남기지 못함(후보 바뀜 또는 시계 역행): link={}, reason={}",
              link.getId(),
              changed.getClass().getSimpleName());
          return;
        }
      }
    }
  }

  /**
   * 잠근 뒤 확인. 링크를 잠그고 무효 여부를 다시 본 뒤 최근 열람과 새 증거 후보를 본다. 읽기만 하고 쓰지 않는다(쓰기는 {@code
   * LinkViewStateStore.save}). 후보는 행동 시각({@code at}, 이 확인 뒤에 정해짐)보다 늦지 않은 시각으로 읽어, 여기서 본 후보는 {@code
   * save}가 모두 처리한다.
   *
   * @param evidence 키를 정할 때 본 가장 이른 후보 통지(없으면 구간 키)
   */
  private void requireCurrentLink(Link resolved, Instant observedAt, UUID evidence) {
    Link current = links.findByIdForUpdate(resolved.getId()).orElseThrow(LinkViewService::invalid);
    if (current.isInvalidated() || !current.getRoomRecordId().equals(resolved.getRoomRecordId())) {
      throw invalid();
    }
    Instant cutoff = observedAt.minus(viewDedupe);
    List<ActionEntry> views =
        actions.findByTarget(ActionTarget.of(TargetKind.LINK, resolved.getId())).stream()
            .filter(action -> action.type() == ActionType.LINK_VIEW)
            .toList();
    boolean recordedRecently =
        views.stream().anyMatch(action -> !action.createdAt().isBefore(cutoff));
    List<UUID> candidates =
        viewStore.unviewedSent(
            resolved.getRoomRecordId(), resolved.getId(), micros(clock.instant()));
    if (evidence == null) {
      // 구간 키: 최근 열람이 없으면 이 구간 키는 쓰인 적이 없다. 최근 열람이 있는데 새 후보가 생겼으면 이 키는 앞 줄을 재생하므로 다시 정한다
      if (!recordedRecently) {
        return;
      }
      if (candidates.isEmpty()) {
        throw new RecentLinkView();
      }
      throw new EvidenceChanged();
    }
    // 증거 키: 그 통지가 아직 후보면 이 키는 쓰인 적이 없다(쓰였다면 그 줄이 그 통지를 열람으로 바꿨다)
    if (candidates.contains(evidence)) {
      return;
    }
    if (candidates.isEmpty() && recordedRecently) {
      throw new RecentLinkView();
    }
    throw new EvidenceChanged();
  }

  /** 행동 시각과 같은 정밀도(마이크로초). 후보를 읽는 시각이 행동 시각보다 늦지 않게 한다. */
  private static Instant micros(Instant at) {
    return at.truncatedTo(ChronoUnit.MICROS);
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

  /** 키를 정한 뒤 잠그는 사이 새 증거 후보가 바뀌었다. 아무것도 쓰지 않았고 키를 다시 정한다. */
  private static final class EvidenceChanged extends RuntimeException {
    private EvidenceChanged() {
      super(null, null, false, false);
    }
  }
}
