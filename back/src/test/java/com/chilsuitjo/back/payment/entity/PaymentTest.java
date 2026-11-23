package com.chilsuitjo.back.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.payment.rule.ConfirmStrength;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.payment.rule.PaymentResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentTest {

  private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

  @ParameterizedTest
  @CsvSource({"false, ONE_SIDE", "true, EVIDENCE"})
  void 등록할_때_증빙_유무에_따라_확인_강도를_정한다(boolean hasEvidence, ConfirmStrength expectedStrength) {
    Payment payment = new Payment(Uuids.v7(), Uuids.v7(), Uuids.v7(), Uuids.v7(), hasEvidence, NOW);

    assertThat(payment.getConfirmStrength()).isEqualTo(expectedStrength);
    assertThat(payment.getResponse()).isEqualTo(PaymentResponse.PENDING);
    assertThat(payment.getConfirmedVersionId()).isNull();
    assertThat(payment.getFirstShownAt()).isNull();
    assertThat(payment.getCreatedAt()).isEqualTo(NOW);
    assertThat(payment.getVersion()).isNull();
  }

  @Test
  void 저장_전에_만든_ID로_머리와_첫_판을_서로_연결한다() {
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    UUID roomRecordId = Uuids.v7();
    UUID repairCaseId = Uuids.v7();

    Payment payment = new Payment(paymentId, roomRecordId, repairCaseId, versionId, false, NOW);
    PaymentVersion firstVersion =
        new PaymentVersion(
            versionId,
            roomRecordId,
            paymentId,
            1,
            PayerKind.TENANT,
            null,
            PayeeKind.VENDOR,
            null,
            82_000,
            LocalDate.of(2026, 11, 22),
            PartySide.TENANT,
            null,
            null,
            NOW);

    assertThat(payment.getId()).isEqualTo(paymentId);
    assertThat(payment.getRoomRecordId()).isEqualTo(roomRecordId);
    assertThat(payment.getRepairCaseId()).isEqualTo(repairCaseId);
    assertThat(payment.getCurrentVersionId()).isEqualTo(firstVersion.getId());
    assertThat(firstVersion.getPaymentId()).isEqualTo(payment.getId());
    assertThat(firstVersion.getRoomRecordId()).isEqualTo(payment.getRoomRecordId());
    assertThat(firstVersion.getNo()).isEqualTo(1);
    assertThat(firstVersion.getPreviousId()).isNull();
  }
}
