package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.NoticeStep;
import com.chilsuitjo.back.notification.dto.SentRequest;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeLine;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB({@code state.response_request}·{@code state.notice})로 읽는 {@link NoticeStatusQuery}. 요청 한 번·통지
 * 한 번 읽는다.
 */
@Service
@RequiredArgsConstructor
public class JpaNoticeStatusQuery implements NoticeStatusQuery {

  private static final EnumSet<NoticeKind> SENT_TO_COUNTERPART =
      EnumSet.of(NoticeKind.REQUEST, NoticeKind.RENOTIFY);

  private final ResponseRequestRepository requests;
  private final NoticeRepository notices;

  @Override
  @Transactional(readOnly = true)
  public List<SentRequest> sentBy(UUID roomRecordId, PartySide side) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(side, "side");
    List<ResponseRequest> sent = requests.findRequestedBy(roomRecordId, side);
    if (sent.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<NoticeStep>> byRequest =
        notices
            .findForRequests(
                roomRecordId,
                sent.stream().map(ResponseRequest::getId).toList(),
                SENT_TO_COUNTERPART)
            .stream()
            .collect(
                Collectors.groupingBy(
                    Notice::getResponseRequestId,
                    LinkedHashMap::new,
                    Collectors.mapping(JpaNoticeStatusQuery::step, Collectors.toList())));
    return sent.stream()
        .map(r -> {
          List<NoticeStep> history = byRequest.getOrDefault(r.getId(), List.of());
          return new SentRequest(
              r.getId(),
              JpaWaitService.view(r),
              NoticeLine.of(history),
              NoticeLine.renotified(history),
              history);
        })
        .toList();
  }

  private static NoticeStep step(Notice n) {
    return new NoticeStep(
        n.getKind(),
        n.getChannel(),
        n.getStatus(),
        n.getCreatedAt(),
        n.getSentAt(),
        n.getDeliveredAt(),
        n.getViewedAt(),
        n.getFailedAt());
  }
}
