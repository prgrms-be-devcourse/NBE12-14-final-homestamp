package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문자 통지를 아웃박스에 넣는다(04 4-2 트랜잭셔널 아웃박스, NOTI-1).
 *
 * <p>행동과 "보낼 문자"가 같은 트랜잭션이어야 빠지는 통지가 없다. 그래서 부른 쪽 트랜잭션 안에서만 동작하고(밖에서 부르면 {@code
 * IllegalTransactionStateException}), 커밋되면 {@link OutboxWorker}가 꺼내 보낸다.
 *
 * <p>최소판: 바로 보낼 줄로 넣는다. 문자 모으기(2분)·21~08시 보류·하루 한도 미룸·수신 거부와 문자를 끈 회원의 서비스 안 알림 전환은 NOTI-1·3·9에서
 * {@code next_attempt_at}·{@code scheduled_for}·{@code channel}로 더한다.
 */
@Service
@RequiredArgsConstructor
public class NoticeOutbox {

  private final NoticeRepository notices;
  private final OutboxRepository outbox;
  private final Clock clock;

  /** 통지와 문자 줄을 함께 저장하고 통지 ID를 돌려준다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID enqueueSms(SmsNoticeCommand command) {
    Instant now = clock.instant();
    Notice notice =
        notices.save(
            new Notice(
                command.roomRecordId(),
                command.responseRequestId(),
                command.linkId(),
                command.recipientPartyId(),
                command.kind(),
                NoticeChannel.SMS,
                now,
                now));
    outbox.save(Outbox.sms(notice.getId(), now, now));
    return notice.getId();
  }
}
