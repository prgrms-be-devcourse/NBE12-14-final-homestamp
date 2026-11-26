package com.chilsuitjo.back.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 가짜 발송기의 전달 결과 조회(#120 1단계). 정하지 않은 결과는 언제나 결과 없음이라, 실제로 아무에게도 가지 않은 문자에 전달됨이 저절로 생기지 않는지 본다(03
 * 불변식 4).
 */
class FakeSmsGatewayTest {

  private final FakeSmsGateway gateway = new FakeSmsGateway();

  @Test
  void 결과를_정하지_않으면_결과_없음이다() {
    UUID batch = UUID.randomUUID();

    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, "fake-1"))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.NONE));
  }

  @Test
  void 전달됨으로_정한_묶음만_전달됨이다() {
    UUID batch = UUID.randomUUID();
    gateway.respondDelivery(batch, DeliveryOutcome.DELIVERED);

    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, "fake-1"))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.DELIVERED));
  }

  @Test
  void 최종_실패로_정하면_실패다() {
    UUID batch = UUID.randomUUID();
    gateway.respondDelivery(batch, DeliveryOutcome.FAILED);

    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, "fake-1"))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.FAILED));
  }

  @Test
  void 묶음마다_결과가_섞이지_않고_물은_묶음은_모두_결과가_있다() {
    UUID delivered = UUID.randomUUID();
    UUID failed = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    gateway.respondDelivery(delivered, DeliveryOutcome.DELIVERED);
    gateway.respondDelivery(failed, DeliveryOutcome.FAILED);

    Map<UUID, DeliveryOutcome> results =
        gateway.queryResults(
            List.of(
                new DeliveryQuery(delivered, "fake-1"),
                new DeliveryQuery(failed, "fake-2"),
                new DeliveryQuery(unknown, "fake-3")));

    assertThat(results)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                delivered, DeliveryOutcome.DELIVERED,
                failed, DeliveryOutcome.FAILED,
                unknown, DeliveryOutcome.NONE));
  }

  @Test
  void 업체_메시지_ID가_없어도_가짜_안에서는_묶음_ID로_결과가_대응된다() {
    // 결과를 모른 채 보낸 문자(UNKNOWN)는 메시지 ID가 없다. 실제 업체에서 찾을 수 있는지는 확인되지 않았다(#120, NOTI-11)
    UUID batch = UUID.randomUUID();
    gateway.respondDelivery(batch, DeliveryOutcome.DELIVERED);

    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, null))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.DELIVERED));
  }

  @Test
  void 조회_실패는_결과_없음이_아니라_예외다() {
    UUID batch = UUID.randomUUID();
    gateway.respondDelivery(batch, DeliveryOutcome.DELIVERED);
    gateway.failQueries(true);

    assertThatThrownBy(() -> gateway.queryResults(List.of(new DeliveryQuery(batch, "fake-1"))))
        .isInstanceOf(IllegalStateException.class);

    gateway.failQueries(false);
    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, "fake-1"))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.DELIVERED));
  }

  @Test
  void 보내기가_성공해도_전달_결과는_저절로_생기지_않는다() {
    UUID batch = UUID.randomUUID();

    SmsSendResult sent = gateway.send(new SmsMessage(batch, "01012345678", "본문"));

    assertThat(sent.outcome()).isEqualTo(SmsSendResult.Outcome.SENT);
    assertThat(gateway.sentBatchIds()).containsExactly(batch);
    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, sent.providerMessageId()))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.NONE));
  }

  @Test
  void 초기화하면_정한_결과와_조회_실패도_지워진다() {
    UUID batch = UUID.randomUUID();
    gateway.respondDelivery(batch, DeliveryOutcome.DELIVERED);
    gateway.failQueries(true);

    gateway.reset();

    assertThat(gateway.queryResults(List.of(new DeliveryQuery(batch, null))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.NONE));
  }

  @Test
  void 묶음_ID_없는_조회는_만들_수_없다() {
    assertThatThrownBy(() -> new DeliveryQuery(null, "fake-1"))
        .isInstanceOf(NullPointerException.class);
  }
}
