package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 가짜 문자 발송기. 실제로 보내지 않고 받은 묶음 ID만 기억한다(번호·본문은 기억하지 않음). 돌려줄 결과는 {@link #respondWith}로 정한다(04 4-2
 * "가짜 발송기는 설정으로").
 *
 * <p>실제 솔라피 구현이 들어오면 이 클래스를 지운다.
 */
@Component
@Profile("(local | test) & !prod")
public class FakeSmsGateway implements SmsGateway {

  private final AtomicReference<SmsSendResult.Outcome> next =
      new AtomicReference<>(SmsSendResult.Outcome.SENT);
  private final List<UUID> sentBatchIds = new CopyOnWriteArrayList<>();

  @Override
  public SmsSendResult send(SmsMessage message) {
    SmsSendResult.Outcome outcome = next.get();
    if (outcome != SmsSendResult.Outcome.RETRY) {
      sentBatchIds.add(message.batchLeadId());
    }
    return switch (outcome) {
      case SENT -> SmsSendResult.sent("fake-" + message.batchLeadId());
      case REJECTED -> SmsSendResult.rejected();
      case RETRY -> SmsSendResult.retry();
      case UNKNOWN -> SmsSendResult.unknown();
    };
  }

  public void respondWith(SmsSendResult.Outcome outcome) {
    next.set(outcome);
  }

  /** 업체에 넘긴 문자의 묶음 ID(보낸 순서). {@code RETRY}는 업체가 받지 않은 것이라 넣지 않는다. */
  public List<UUID> sentBatchIds() {
    return List.copyOf(sentBatchIds);
  }

  public void reset() {
    next.set(SmsSendResult.Outcome.SENT);
    sentBatchIds.clear();
  }
}
