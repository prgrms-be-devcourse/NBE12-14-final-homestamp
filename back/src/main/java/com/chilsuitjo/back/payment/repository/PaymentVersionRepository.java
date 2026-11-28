package com.chilsuitjo.back.payment.repository;

import com.chilsuitjo.back.payment.entity.PaymentVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 지불 판({@code record.payment_version}, B). 새 판을 더하기만 한다. 판은 고칠 수 없어서 잠금 없이 읽어도 값이 바뀌지 않는다. payment
 * 안에서만 쓴다.
 */
public interface PaymentVersionRepository extends JpaRepository<PaymentVersion, UUID> {

  /** 그 지불의 이 번호 판. 응답할 때 본 판의 내용 지문을 만든다. */
  Optional<PaymentVersion> findByPaymentIdAndNo(UUID paymentId, int no);
}
