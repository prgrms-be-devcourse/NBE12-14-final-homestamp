package com.chilsuitjo.back.payment.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

class PaymentAmountConfirmedTest {
  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID repairCaseId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  @Test
  void 호실_수리_건_지불_ID만_담는다() {
    var event = new PaymentAmountConfirmed(roomRecordId, repairCaseId, paymentId);

    assertThat(event.roomRecordId()).isEqualTo(roomRecordId);
    assertThat(event.repairCaseId()).isEqualTo(repairCaseId);
    assertThat(event.paymentId()).isEqualTo(paymentId);
  }

  @Test
  void ID가_비면_만들_수_없다() {
    assertThatThrownBy(() -> new PaymentAmountConfirmed(null, repairCaseId, paymentId))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("roomRecordId");
    assertThatThrownBy(() -> new PaymentAmountConfirmed(roomRecordId, null, paymentId))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("repairCaseId");
    assertThatThrownBy(() -> new PaymentAmountConfirmed(roomRecordId, repairCaseId, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("paymentId");
  }

  /** 듣는 쪽이 쓰는 모양: {@code @EventListener}는 publishEvent가 돌아오기 전에 같은 스레드에서 돈다(같은 트랜잭션). */
  @Test
  void EventListener는_낸_자리에서_같은_스레드로_바로_듣는다() {
    try (var context = new AnnotationConfigApplicationContext(Recorder.class)) {
      var event = new PaymentAmountConfirmed(roomRecordId, repairCaseId, paymentId);

      context.publishEvent(event);

      Recorder recorder = context.getBean(Recorder.class);
      assertThat(recorder.received).containsExactly(event);
      assertThat(recorder.threads).containsExactly(Thread.currentThread());
    }
  }

  static class Recorder {
    final List<PaymentAmountConfirmed> received = new ArrayList<>();
    final List<Thread> threads = new ArrayList<>();

    @EventListener
    void on(PaymentAmountConfirmed event) {
      received.add(event);
      threads.add(Thread.currentThread());
    }
  }
}
