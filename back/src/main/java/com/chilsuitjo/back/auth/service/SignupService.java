package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.member.dto.SignupRequest;
import com.chilsuitjo.back.member.rule.SignupAcknowledgements;
import com.chilsuitjo.back.member.service.MemberService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입(06 ACC-1, 4-1 {@code POST /api/auth/signup}).
 *
 * <p>순서: 확인 항목(ACC-1a) → 이메일 중복(ACC-1c) → 비밀번호 해시 → <b>한 트랜잭션</b>에서 SIGNUP 인증 표 쓰기(ACC-1f·1l) → 번호
 * 잠금 → 같은 번호를 쓰던 계정의 번호 비우기(ACC-3a) → 회원과 확인 기록 세 줄(ACC-1b). 트랜잭션 안에서 실패하면 롤백되어 인증 표가 그대로
 * 남는다(ACC-1f). 해시는 잠금을 오래 잡지 않으려고 트랜잭션 전에 만든다.
 *
 * <p>아직 하지 않는 것(notification 계약이 오면 같은 트랜잭션에 더한다): 번호를 비운 이전 계정에 서비스 안 알림(ACC-3a), 그 번호의 수신 거부 해제와
 * 삭제 감사·{@code AUDIT_WRITE}(05 {@code sms_opt_out}).
 */
@Service
@RequiredArgsConstructor
public class SignupService {

  private final PhoneVerificationService verifications;
  private final PhoneVerificationRepository verificationRows;
  private final VerificationKeys keys;
  private final MemberService members;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transaction;

  /**
   * 가입하고 회원 ID를 돌려준다.
   *
   * @throws com.chilsuitjo.back.global.error.BusinessException {@code AGE_CONFIRM_REQUIRED}, {@code
   *     TERMS_REQUIRED}, {@code VALIDATION_FAILED}(문구 판), {@code EMAIL_TAKEN}, {@code CODE_EXPIRED}
   */
  public UUID signUp(SignupRequest request) {
    SignupAcknowledgements confirmed = request.acknowledgements();
    members.requireEmailAvailable(request.email());
    String passwordHash = passwordEncoder.encode(request.password());

    return transaction.execute(status -> {
      String phone =
          verifications.useTicket(request.verificationTicket(), VerificationPurpose.SIGNUP);
      // 같은 번호의 SIGNUP 표가 둘 있어 동시에 가입해도 차례로 처리한다(인증 요청과 같은 번호 잠금, 05)
      verificationRows.lockPhone(keys.phoneLockKey(phone));
      return members.register(
          request.email(), passwordHash, request.displayName(), phone, confirmed);
    });
  }
}
