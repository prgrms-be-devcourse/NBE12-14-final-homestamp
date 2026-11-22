package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 가짜 문자 발송기. 실제로 보내지 않고 받은 묶음 ID만 기억한다(번호·본문은 기억하지 않음). 돌려줄 결과는 {@link #respondWith}로 정한다(04 4-2
 * "가짜 발송기는 설정으로").
 *
 * <p>전달 결과 조회는 {@link #respondDelivery}로 묶음마다 정한 결과만 돌려주고, 정하지 않은 묶음은 언제나 {@link
 * DeliveryOutcome#NONE}이다. 실제로 아무에게도 가지 않은 문자에 전달됨 증거가 저절로 생기지 않게 하려는 것이다(03 불변식 4, #120). 보내기가
 * 성공해도 전달 결과는 바뀌지 않는다.
 *
 * <p>실제 솔라피 구현이 들어오면 이 클래스를 지운다.
 */
@Component
public class FakeSmsGateway implements SmsGateway {

  private final AtomicReference<SmsSendResult.Outcome> next =
      new AtomicReference<>(SmsSendResult.Outcome.SENT);
  private final List<UUID> sentBatchIds = new CopyOnWriteArrayList<>();
  private final Map<UUID, DeliveryOutcome> deliveryResults = new ConcurrentHashMap<>();
  private final AtomicBoolean queryFails = new AtomicBoolean(false);

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

  /** 업체 메시지 ID는 보지 않고 묶음 ID로만 정한 결과를 찾는다(가짜 안에서의 대응일 뿐 실제 업체 검색 방식이 아님). */
  @Override
  public Map<UUID, DeliveryOutcome> queryResults(List<DeliveryQuery> queries) {
    if (queryFails.get()) {
      throw new IllegalStateException("가짜 업체 조회 실패");
    }
    Map<UUID, DeliveryOutcome> results = new LinkedHashMap<>();
    for (DeliveryQuery query : queries) {
      results.put(
          query.batchLeadId(),
          deliveryResults.getOrDefault(query.batchLeadId(), DeliveryOutcome.NONE));
    }
    return results;
  }

  public void respondWith(SmsSendResult.Outcome outcome) {
    next.set(outcome);
  }

  /** 이 묶음의 문자를 물으면 돌려줄 전달 결과를 정한다(테스트·명시적 시연 설정용). */
  public void respondDelivery(UUID batchLeadId, DeliveryOutcome outcome) {
    deliveryResults.put(batchLeadId, outcome);
  }

  /** 켜면 전달 결과 조회가 업체 조회 실패처럼 예외를 던진다. */
  public void failQueries(boolean fail) {
    queryFails.set(fail);
  }

  /** 업체에 넘긴 문자의 묶음 ID(보낸 순서). {@code RETRY}는 업체가 받지 않은 것이라 넣지 않는다. */
  public List<UUID> sentBatchIds() {
    return List.copyOf(sentBatchIds);
  }

  public void reset() {
    next.set(SmsSendResult.Outcome.SENT);
    sentBatchIds.clear();
    deliveryResults.clear();
    queryFails.set(false);
  }
}
