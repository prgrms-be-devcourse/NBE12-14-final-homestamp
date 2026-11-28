package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.payment.dto.PayeeRequest;
import com.chilsuitjo.back.payment.dto.PayerRequest;
import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.entity.Payment;
import com.chilsuitjo.back.payment.entity.PaymentVersion;
import com.chilsuitjo.back.payment.repository.PaymentRepository;
import com.chilsuitjo.back.payment.repository.PaymentVersionRepository;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

/**
 * 다른 도메인 테스트가 수리 건의 기준 금액({@code PaymentService.confirmedTotal})을 만들 때 쓰는 도우미. 가짜 창구에 숫자를 적어 두지 않고
 * 실제 지불을 남긴다. {@code @Import(PaymentFixtures.class)}로 붙인다.
 */
@TestComponent
@RequiredArgsConstructor
public class PaymentFixtures {

  private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 27);

  private final PaymentService payments;
  private final RoomAccessService access;
  private final PaymentRepository paymentRows;
  private final PaymentVersionRepository versionRows;

  /**
   * 실제 API 흐름으로 확인된 지불 하나(06 MONEY-1·2): 기록한 회원이 업체에 낸 돈을 남기고, 상대가 지금 판에 [맞아요]를 누른다. pipeline을 지나므로
   * 바깥 트랜잭션 없이 부르고, 호실·두 자리·수리 건은 커밋돼 있어야 한다. 같은 금액을 여러 번 남겨도 막히지 않게 비슷한 기록 안내는 넘긴다({@code
   * similarConfirmed}).
   *
   * @return 지불 ID
   */
  public UUID confirmed(
      UUID roomRecordId, UUID repairCaseId, UUID recordedBy, UUID confirmedBy, long amount) {
    RoomAccess recorder = access.require(roomRecordId, recordedBy);
    UUID paymentId =
        payments
            .record(
                recorder,
                "fixture-" + Uuids.v7(),
                repairCaseId,
                new PaymentCreateRequest(
                    new PayerRequest(PayerKind.valueOf(recorder.side().name()), null),
                    new PayeeRequest(PayeeKind.VENDOR, null),
                    amount,
                    PAID_ON,
                    null,
                    List.of(),
                    true))
            .id();
    payments.respond(
        paymentId, confirmedBy, "fixture-" + Uuids.v7(), new RespondRequest(Answer.ACK, null, 1));
    return paymentId;
  }

  /**
   * 확인된 지불 행만 넣는다(pipeline·행동 기록·응답 요청 없이). 테스트 전체가 {@code @Transactional}이라 pipeline을 부를 수 없는 테스트가
   * 쓴다. 머리를 먼저 내보내야 판의 머리 FK(바로 검사)를 지난다. 맞아요는 엔티티의 {@code confirmCurrent}로 남긴다.
   *
   * @return 지불 ID
   */
  public UUID insertConfirmed(UUID roomRecordId, UUID repairCaseId, long amount) {
    UUID id = Uuids.v7();
    UUID versionId = Uuids.v7();
    Instant now = Instant.now();
    Payment payment =
        paymentRows.saveAndFlush(
            new Payment(id, roomRecordId, repairCaseId, versionId, false, now));
    versionRows.saveAndFlush(
        new PaymentVersion(
            versionId,
            roomRecordId,
            id,
            1,
            PayerKind.TENANT,
            null,
            PayeeKind.VENDOR,
            null,
            amount,
            PAID_ON,
            PartySide.TENANT,
            null,
            null,
            now));
    payment.confirmCurrent();
    paymentRows.flush();
    return id;
  }
}
