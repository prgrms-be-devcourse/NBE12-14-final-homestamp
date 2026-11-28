package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.payment.repository.PaymentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 지불 읽기(잠그지 않음). {@link PaymentService}와 나눈 이유: 수리 건 사진 한도({@code RepairPhotos})가 이 값을 읽는데, 그
 * {@code RepairPhotos}를 지불 기록이 {@link RepairPhotoScope}로 부른다. {@code PaymentService}에 두면 빈끼리 고리가
 * 생긴다. 저장소만 받는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentReader {

  private final PaymentRepository payments;

  /** 이 수리 건의 지불 ID 모두(기록 순서). 확인 전·이견도 넣는다. 다른 호실의 수리 건이면 비어 있다. */
  public List<UUID> idsByRepairCase(UUID roomRecordId, UUID repairCaseId) {
    return payments.findIds(roomRecordId, repairCaseId);
  }
}
