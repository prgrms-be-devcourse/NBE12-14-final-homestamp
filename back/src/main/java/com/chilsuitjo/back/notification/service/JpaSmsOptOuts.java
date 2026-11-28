package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.SmsOptOutRepository;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SmsOptOuts}의 운영 구현. 번호를 {@link PhoneHmacs}로 바꿔 {@code state.sms_opt_out}을 읽고 쓴다. HMAC·DB
 * 실패는 잡지 않고 그대로 올린다(판정 실패를 "거부 없음"으로 바꾸지 않음). 번호와 HMAC 값은 로그에 남기지 않는다.
 *
 * <p>동시성은 DB 제약과 행 잠금만 쓴다(#370 A-3): 등록은 {@code ON CONFLICT DO NOTHING}, 해제는 지운 수를 보고 1일 때만 감사를
 * 넣는다. 번호 잠금·별도 잠금 테이블은 두지 않는다. 감사 기록을 쓰는 곳은 #163 3번 D 기본안(notification 저장소의 네이티브 INSERT)이다. C의 답은
 * 아직 없다.
 */
@Service
@RequiredArgsConstructor
class JpaSmsOptOuts implements SmsOptOuts {

  private final PhoneHmacs phoneHmacs;
  private final SmsOptOutRepository optOuts;

  @Override
  public boolean isOptedOut(String phone) {
    return optOuts.existsById(phoneHmacs.phoneHmac(phone));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean optOut(String phone, Instant at) {
    Objects.requireNonNull(at, "at");
    return optOuts.insertIfAbsent(phoneHmacs.phoneHmac(phone), at) == 1;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean releaseForVerifiedOwner(String phone, Instant at) {
    Objects.requireNonNull(at, "at");
    String phoneHmac = phoneHmacs.phoneHmac(phone);
    int deleted = optOuts.deleteByPhoneHmac(phoneHmac);
    if (deleted == 0) {
      return false;
    }
    if (deleted != 1 || optOuts.insertReleaseAudit(phoneHmac, at) != 1) {
      // PK라 생기지 않는다. 생기면 감사 없이 지운 채로 커밋하지 않게 트랜잭션을 되돌린다
      throw new IllegalStateException("수신 거부 해제 감사를 남기지 못했어요");
    }
    return true;
  }
}
