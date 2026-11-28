package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.dto.PaymentRecordResult;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 지불 가짜(#111). DB·행동 기록 없이 지불 ID·소속과 재요청 결과를 메모리에 보관한다. 멱등 키 검사와 재요청 판정은 진짜 {@code ActionPipeline}과
 * 같게 맞추고, 중복 지불 검사(MONEY-1a·1c)와 공유 중단 막기는 하지 않는다.
 *
 * <p>진짜와 다른 점: 지불만 기억하므로 같은 키를 하자 등록처럼 다른 종류 행동에 먼저 썼어도 새 지불을 만든다(진짜는 {@code
 * IDEMPOTENCY_KEY_REUSED}). 요청 칸({@link PaymentCreateRequest})도 검사하지 않는다.
 *
 * <p>빈이 아니다: {@link PaymentService} 빈은 {@link JpaPaymentService}다. 기준 금액을 적어 쓰는 테스트가 테스트 설정으로 붙여
 * 쓴다. 그 테스트를 실제 구현으로 옮기면 이 클래스를 지운다. 다른 도메인은 {@link PaymentService}만 쓰므로 바뀌지 않는다.
 */
public class FakePaymentService implements PaymentService {
  private final Map<Key, RecordedPayment> payments = new HashMap<>();
  private final Map<CaseKey, Long> confirmedTotals = new HashMap<>();

  @Override
  public synchronized PaymentRecordResult record(
      RoomAccess access, String idempotencyKey, UUID repairCaseId, PaymentCreateRequest req) {
    // 진짜는 ActionBatch를 만들 때 같은 검사로 걸린다(ActionBatch.checkKey, 06 4-0).
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.contains("#")
        || idempotencyKey.length() > ActionBatch.MAX_KEY_LENGTH) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    Key key = new Key(access.memberId(), idempotencyKey);
    RecordedPayment existing = payments.get(key);
    if (existing != null) {
      // ActionPipeline.replay와 같다. 다른 호실이면 거절하고, 새 기록을 만드는 단계는 종류만 비교하므로
      // 같은 호실이면 수리 건이 달라도 처음 결과를 돌려준다(부모 ID는 payload라 비교하지 않음, 09 4장 순서 2).
      if (!existing.roomRecordId().equals(access.roomRecordId())) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
      }
      return new PaymentRecordResult(existing.paymentId(), true);
    }

    UUID paymentId = Uuids.v7();
    payments.put(key, new RecordedPayment(paymentId, access.roomRecordId(), repairCaseId));
    return new PaymentRecordResult(paymentId, false);
  }

  @Override
  public synchronized boolean hasMoney(UUID roomRecordId, UUID repairCaseId) {
    return payments.values().stream()
        .anyMatch(payment ->
            payment.roomRecordId().equals(roomRecordId)
                && payment.repairCaseId().equals(repairCaseId));
  }

  /** 가짜에는 금액 확인 동작이 없어서 {@link #seedConfirmedTotal}로 적어 둔 값을 돌려준다. 적지 않았으면 0. */
  @Override
  public synchronized long confirmedTotal(UUID roomRecordId, UUID repairCaseId) {
    return confirmedTotals.getOrDefault(new CaseKey(roomRecordId, repairCaseId), 0L);
  }

  /** 이 수리 건의 기준 금액을 적어 둔다(다른 호실의 같은 수리 건 ID로는 읽히지 않는다). */
  public synchronized void seedConfirmedTotal(UUID roomRecordId, UUID repairCaseId, long total) {
    confirmedTotals.put(new CaseKey(roomRecordId, repairCaseId), total);
  }

  private record Key(UUID memberId, String idempotencyKey) {}

  private record CaseKey(UUID roomRecordId, UUID repairCaseId) {}

  private record RecordedPayment(UUID paymentId, UUID roomRecordId, UUID repairCaseId) {}
}
