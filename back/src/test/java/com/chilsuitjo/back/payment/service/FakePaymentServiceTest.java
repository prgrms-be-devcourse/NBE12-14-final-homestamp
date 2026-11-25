package com.chilsuitjo.back.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.payment.dto.PayeeRequest;
import com.chilsuitjo.back.payment.dto.PayerRequest;
import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakePaymentServiceTest {
  private final FakePaymentService service = new FakePaymentService();
  private final UUID roomId = UUID.randomUUID();
  private final UUID repairCaseId = UUID.randomUUID();
  private final RoomAccess access = access(UUID.randomUUID());
  private final PaymentCreateRequest request =
      new PaymentCreateRequest(
          new PayerRequest(PayerKind.TENANT, null),
          new PayeeRequest(PayeeKind.VENDOR, null),
          10000,
          LocalDate.of(2026, 11, 24),
          null,
          List.of(),
          false);

  @Test
  void 처음에는_새_ID를_만들고_재요청에는_같은_ID를_돌려준다() {
    var first = service.record(access, "key", repairCaseId, request);
    var replay = service.record(access, "key", repairCaseId, request);

    assertThat(first.id()).isNotNull();
    assertThat(first.replayed()).isFalse();
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.replayed()).isTrue();
  }

  @Test
  void 키가_다르거나_회원이_다르면_별도_기록이다() {
    var first = service.record(access, "key", repairCaseId, request);
    var otherKey = service.record(access, "other", repairCaseId, request);
    var otherMember = service.record(access(UUID.randomUUID()), "key", repairCaseId, request);

    assertThat(otherKey.id()).isNotEqualTo(first.id());
    assertThat(otherMember.id()).isNotEqualTo(first.id());
    assertThat(otherKey.replayed()).isFalse();
    assertThat(otherMember.replayed()).isFalse();
  }

  @Test
  void 같은_키를_다른_호실에_쓰면_거절한다() {
    service.record(access, "key", repairCaseId, request);
    RoomAccess otherRoom = access(UUID.randomUUID(), access.memberId());

    assertThatThrownBy(() -> service.record(otherRoom, "key", repairCaseId, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void 같은_호실이면_수리_건이_달라도_처음_결과를_돌려준다() {
    var first = service.record(access, "key", repairCaseId, request);
    var replay = service.record(access, "key", UUID.randomUUID(), request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.replayed()).isTrue();
  }

  @Test
  void 잘못된_키는_거절한다() {
    for (String key : new String[] {null, "", " ", "key#2", "a".repeat(65)}) {
      assertThatThrownBy(() -> service.record(access, key, repairCaseId, request))
          .isInstanceOfSatisfying(
              BusinessException.class,
              ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
  }

  @Test
  void 기준_금액은_적어_두지_않으면_0이다() {
    assertThat(service.confirmedTotal(roomId, repairCaseId)).isZero();
  }

  @Test
  void 지불이_없으면_돈_기록도_없다() {
    PaymentService reader = service;

    assertThat(reader.hasMoney(roomId, repairCaseId)).isFalse();
  }

  @Test
  void 상대가_확인하기_전에도_지불을_등록하면_돈_기록이_있다() {
    service.record(access, "key", repairCaseId, request);
    PaymentService reader = service;

    assertThat(reader.hasMoney(roomId, repairCaseId)).isTrue();
    assertThat(reader.confirmedTotal(roomId, repairCaseId)).isZero();
  }

  @Test
  void 돈_기록은_그_호실의_그_수리_건으로만_읽힌다() {
    service.record(access, "key", repairCaseId, request);

    assertThat(service.hasMoney(UUID.randomUUID(), repairCaseId)).isFalse();
    assertThat(service.hasMoney(roomId, UUID.randomUUID())).isFalse();
  }

  @Test
  void 같은_키로_다른_수리_건에_재요청해도_돈_기록은_처음_수리_건에만_있다() {
    service.record(access, "key", repairCaseId, request);
    UUID otherCaseId = UUID.randomUUID();
    service.record(access, "key", otherCaseId, request);

    assertThat(service.hasMoney(roomId, repairCaseId)).isTrue();
    assertThat(service.hasMoney(roomId, otherCaseId)).isFalse();
  }

  @Test
  void 기준_금액은_그_호실의_그_수리_건으로만_읽힌다() {
    service.seedConfirmedTotal(roomId, repairCaseId, 92000);

    assertThat(service.confirmedTotal(roomId, repairCaseId)).isEqualTo(92000);
    assertThat(service.confirmedTotal(UUID.randomUUID(), repairCaseId)).isZero();
    assertThat(service.confirmedTotal(roomId, UUID.randomUUID())).isZero();
  }

  private RoomAccess access(UUID memberId) {
    return access(roomId, memberId);
  }

  private RoomAccess access(UUID roomRecordId, UUID memberId) {
    return new RoomAccess(
        roomRecordId,
        memberId,
        UUID.randomUUID(),
        PartySide.TENANT,
        PartyStatus.CONNECTED,
        RoomStatus.LIVING,
        null,
        PartyStatus.CONNECTED,
        null);
  }
}
