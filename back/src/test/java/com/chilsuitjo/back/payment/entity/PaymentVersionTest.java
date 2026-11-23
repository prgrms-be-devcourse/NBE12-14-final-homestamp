package com.chilsuitjo.back.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentVersionTest {

  @Test
  void 지불_판은_관계_설명과_증빙을_포함한_입력값을_보존한다() {
    UUID id = Uuids.v7();
    UUID roomRecordId = Uuids.v7();
    UUID paymentId = Uuids.v7();
    LocalDate paidOn = LocalDate.of(2026, 11, 22);
    Instant createdAt = Instant.parse("2026-11-23T00:00:00Z");

    PaymentVersion version =
        new PaymentVersion(
            id,
            roomRecordId,
            paymentId,
            1,
            PayerKind.THIRD_PARTY,
            "가족",
            PayeeKind.THIRD_PARTY,
            "윗집",
            82_000,
            paidOn,
            PartySide.LANDLORD,
            "영수증",
            null,
            createdAt);

    assertThat(version.getId()).isEqualTo(id);
    assertThat(version.getRoomRecordId()).isEqualTo(roomRecordId);
    assertThat(version.getPaymentId()).isEqualTo(paymentId);
    assertThat(version.getPayerKind()).isEqualTo(PayerKind.THIRD_PARTY);
    assertThat(version.getPayerLabel()).isEqualTo("가족");
    assertThat(version.getPayeeKind()).isEqualTo(PayeeKind.THIRD_PARTY);
    assertThat(version.getPayeeLabel()).isEqualTo("윗집");
    assertThat(version.getAmount()).isEqualTo(82_000);
    assertThat(version.getPaidOn()).isEqualTo(paidOn);
    assertThat(version.getRecordedBySide()).isEqualTo(PartySide.LANDLORD);
    assertThat(version.getEvidenceKind()).isEqualTo("영수증");
    assertThat(version.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void 금액을_고친_새_판은_이전_판을_가리키고_이전_금액은_유지한다() {
    PaymentVersion previous = create(1, 82_000);

    PaymentVersion corrected =
        new PaymentVersion(
            Uuids.v7(),
            previous.getRoomRecordId(),
            previous.getPaymentId(),
            2,
            previous.getPayerKind(),
            previous.getPayerLabel(),
            previous.getPayeeKind(),
            previous.getPayeeLabel(),
            92_000,
            previous.getPaidOn(),
            previous.getRecordedBySide(),
            previous.getEvidenceKind(),
            previous.getId(),
            previous.getCreatedAt().plusSeconds(60));

    assertThat(corrected.getId()).isNotEqualTo(previous.getId());
    assertThat(corrected.getPaymentId()).isEqualTo(previous.getPaymentId());
    assertThat(corrected.getRoomRecordId()).isEqualTo(previous.getRoomRecordId());
    assertThat(corrected.getNo()).isEqualTo(2);
    assertThat(corrected.getPreviousId()).isEqualTo(previous.getId());
    assertThat(corrected.getAmount()).isEqualTo(92_000);
    assertThat(previous.getAmount()).isEqualTo(82_000);
    assertThat(previous.getNo()).isEqualTo(1);
    assertThat(previous.getPreviousId()).isNull();
  }

  @Test
  void 첫_판은_0원도_기록할_수_있다() {
    PaymentVersion version = create(1, 0);

    assertThat(version.getAmount()).isZero();
    assertThat(version.getPreviousId()).isNull();
    assertThat(version.getPayerLabel()).isNull();
    assertThat(version.getPayeeLabel()).isNull();
    assertThat(version.getEvidenceKind()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void 판_번호는_1_이상이어야_한다(int no) {
    assertThatThrownBy(() -> create(no, 10_000)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 음수_금액은_거절한다() {
    assertThatThrownBy(() -> create(1, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 미리_지정한_ID가_있어도_저장_전에는_새_행이다() {
    PaymentVersion version = create(1, 10_000);

    assertThat(version.getId()).isNotNull();
    assertThat(version.isNew()).isTrue();

    version.markStored();

    assertThat(version.isNew()).isFalse();
  }

  private PaymentVersion create(int no, long amount) {
    return new PaymentVersion(
        Uuids.v7(),
        Uuids.v7(),
        Uuids.v7(),
        no,
        PayerKind.TENANT,
        null,
        PayeeKind.VENDOR,
        null,
        amount,
        LocalDate.of(2026, 11, 23),
        PartySide.TENANT,
        null,
        null,
        Instant.parse("2026-11-23T00:00:00Z"));
  }
}
