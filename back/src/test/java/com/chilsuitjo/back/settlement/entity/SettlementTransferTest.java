package com.chilsuitjo.back.settlement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import com.chilsuitjo.back.settlement.rule.TransferResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementTransferTest {
  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 25);

  @Test
  void 새_송금은_아직_응답과_수령_확인이_없다() {
    UUID relatedTransfer = UUID.randomUUID();
    SettlementTransfer transfer = transfer(UUID.randomUUID(), relatedTransfer);

    assertThat(transfer.getResponse()).isEqualTo(TransferResponse.NONE);
    assertThat(transfer.getConfirmedVersionId()).isNull();
    assertThat(transfer.getReceiptConfirmedAt()).isNull();
    assertThat(transfer.getSameAsTransferId()).isEqualTo(relatedTransfer);
  }

  @Test
  void 자기_자신을_같은_송금으로_연결할_수_없다() {
    UUID id = UUID.randomUUID();

    assertThatIllegalArgumentException().isThrownBy(() -> transfer(id, id));
  }

  @Test
  void 송금_판은_1번부터이고_0원도_기록할_수_있다() {
    SettlementTransferVersion first = version(1, 0);

    assertThat(first.getNo()).isEqualTo(1);
    assertThat(first.getAmount()).isZero();
    assertThat(first.getPreviousId()).isNull();
    assertThatIllegalArgumentException().isThrownBy(() -> version(0, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> version(1, -1));
  }

  private SettlementTransfer transfer(UUID id, UUID sameAsTransferId) {
    return new SettlementTransfer(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        TransferDirection.REFUND,
        UUID.randomUUID(),
        PartySide.TENANT,
        sameAsTransferId,
        NOW);
  }

  private SettlementTransferVersion version(int no, long amount) {
    return new SettlementTransferVersion(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), no, amount, PAID_ON, null, NOW);
  }
}
