package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import com.chilsuitjo.back.notification.rule.OutboxKind;
import com.chilsuitjo.back.notification.rule.OutboxStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보내지 않은 문자 취소(#100 U2)의 실제 구현. notification 자기 테이블({@code notice}·{@code outbox})만 바꾼다.
 *
 * <p>잠금 순서는 호실 → 통지 → 문자 줄이다. 작업자가 결과를 적을 때도 통지를 먼저 잠그고 문자 줄을 바꾸므로 서로 거꾸로 기다리지 않는다. 작업자가 줄을 가져갈
 * 때({@code SKIP LOCKED})는 문자 줄만 잠가서, 여기서 잡은 줄은 건너뛰고, 작업자가 잡고 있는 줄은 여기서 기다렸다가 바뀐 상태를 본다.
 *
 * <p>로그에는 통지·문자 줄 ID만 남긴다. 번호·토큰·본문은 이 코드가 읽지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SmsCancellationService implements SmsCancellation {

  private static final Logger log = LoggerFactory.getLogger(SmsCancellationService.class);

  private final NoticeRepository notices;
  private final OutboxRepository outbox;
  private final RoomGateReader roomGateReader;
  private final ActionRequestContext requestContext;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancelPendingSms(UUID roomRecordId, Collection<UUID> linkIds) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(linkIds, "linkIds");
    // MANDATORY는 트랜잭션 안인지만 본다. save 밖(커밋 뒤 리스너·예약 작업 등)이면 여기서 IllegalStateException(#236과 같은 방식)
    requestContext.requestId();
    return cancel(roomRecordId, linkIds);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancelPendingSmsOnWithdrawal(UUID roomRecordId, Collection<UUID> linkIds) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(linkIds, "linkIds");
    // 탈퇴 트랜잭션은 pipeline 밖이라 요청 문맥이 없다. 트랜잭션 안인지(MANDATORY)와 호실 잠금(아래)으로 지킨다
    return cancel(roomRecordId, linkIds);
  }

  private int cancel(UUID roomRecordId, Collection<UUID> linkIds) {
    Set<UUID> links = Set.copyOf(linkIds);
    if (links.isEmpty()) {
      return 0;
    }
    // 부른 쪽이 이미 잡은 호실 잠금을 다시 잡는다(같은 트랜잭션이라 기다리지 않음). 호실 잠금 뒤라는 계약을 부른 쪽에만 맡기지 않는다.
    roomGateReader.lockAndRead(roomRecordId, Actor.server());
    List<Notice> targets = notices.lockByLinks(roomRecordId, links, NoticeChannel.SMS);
    if (targets.isEmpty()) {
      return 0;
    }
    Map<String, Outbox> rows =
        outbox
            .lockByDedupeKeys(
                OutboxKind.SMS, targets.stream().map(n -> dedupeKey(n.getId())).toList())
            .stream()
            .collect(Collectors.toMap(Outbox::getDedupeKey, Function.identity()));

    int cancelled = 0;
    for (Notice notice : targets) {
      Outbox row = rows.get(dedupeKey(notice.getId()));
      if (cancel(notice, row)) {
        cancelled++;
      }
    }
    return cancelled;
  }

  /** 문자 줄이 바뀐 경우에만 통지도 바꾼다. 바꿨는지를 돌려준다. */
  private static boolean cancel(Notice notice, Outbox row) {
    if (row == null) {
      // 문자 통지는 문자 줄과 함께 만든다(NoticeOutbox). 보낼 일이 남은 통지인데 줄이 없으면 연결이 깨진 것이다.
      // 보낼 줄이 없으니 번호 고치기는 막지 않되, 0으로 숨기지 않고 남긴다(#100 결정). 키나 종류가 틀린 줄과 아예 없는 줄은 여기서 나누지 않는다.
      if (notice.getStatus() == NoticeStatus.REQUESTED) {
        log.error("취소할 문자 통지에 맞는 문자 줄이 없음(없거나 중복 방지 키·종류가 다름): notice={}", notice.getId());
      }
      return false;
    }
    if (!row.noticeId().map(notice.getId()::equals).orElse(false)) {
      // 키는 맞는데 payload가 다른 통지를 가리킨다. 작업자도 이 줄은 보내지 않고 실패로 둔다(OutboxWorker).
      log.error(
          "문자 줄의 payload가 키와 다른 통지를 가리켜 취소하지 않음: notice={}, outbox={}",
          notice.getId(),
          row.getId());
      return false;
    }
    if (row.getStatus() != OutboxStatus.PENDING) {
      return false;
    }
    if (notice.getStatus() != NoticeStatus.REQUESTED) {
      // 작업자는 줄을 끝낼 때만 통지를 바꾸므로 지금 코드로는 생길 수 없다. 어느 쪽에 맞출지 몰라 덮어쓰지 않고 되돌린다.
      throw new IllegalStateException(
          "보내기 전 문자 줄인데 통지가 발송 요청됨이 아니에요: notice="
              + notice.getId()
              + ", status="
              + notice.getStatus());
    }
    row.markCancelled();
    notice.markCancelled();
    return true;
  }

  private static String dedupeKey(UUID noticeId) {
    return "sms:" + noticeId;
  }
}
