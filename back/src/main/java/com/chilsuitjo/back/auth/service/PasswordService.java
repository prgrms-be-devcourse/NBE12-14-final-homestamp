package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.PasswordChangeRequest;
import com.chilsuitjo.back.auth.dto.PasswordResetRequest;
import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.member.service.MemberPasswordService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 변경(06 ACC-4a, 4-1 {@code POST /api/me/password})과 재설정(ACC-5b, {@code POST
 * /api/auth/reset-password}). 비밀번호는 member가 바꾸고, 리프레시 토큰은 {@link AuthTokenService}가 같은 트랜잭션에서 폐기한다.
 *
 * <p>아직 하지 않는 것: "비밀번호가 바뀌었어요" 문자와 서비스 안 알림(06 ACC-4a·5b, 03 L). 계정 문자를 보낼 길이 정해지면(#261) 같은 트랜잭션에
 * 더한다.
 */
@Service
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class PasswordService {

  private final MemberPasswordService members;
  private final AuthTokenService tokens;
  private final PhoneVerificationService verifications;
  private final PhoneVerificationRepository verificationRows;
  private final VerificationKeys keys;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transaction;

  /**
   * 현재 비밀번호를 확인하고 바꾼 뒤, 지금 기기(액세스 토큰의 묶음)를 뺀 리프레시 토큰을 모두 폐기한다(ACC-4a). 실패하면 아무것도 바뀌지 않는다.
   *
   * @param currentFamilyId 요청한 액세스 토큰의 묶음
   * @throws BusinessException {@code PASSWORD_MISMATCH}, {@code LOGIN_REQUIRED}(탈퇴했거나 없는 회원)
   */
  public void change(UUID memberId, UUID currentFamilyId, PasswordChangeRequest request) {
    transaction.executeWithoutResult(status -> {
      members.change(memberId, request.currentPassword(), request.newPassword());
      tokens.revokeOthers(memberId, currentFamilyId);
    });
  }

  /**
   * RESET 인증 표로 그 번호의 회원 비밀번호를 바꾸고 모든 리프레시 토큰을 폐기한다(ACC-5b). 순서는 가입과 같다: 인증 표 미리 확인 → 해시 → <b>한
   * 트랜잭션</b>에서 표 쓰기 → 번호 잠금 → 비밀번호 바꾸기 → 토큰 폐기. 트랜잭션 안에서 실패하면 롤백되어 표가 그대로 남는다(ACC-1f).
   *
   * <p>그 번호의 회원이 없으면(가입하지 않은 번호, 탈퇴, 다른 계정이 번호를 가져감) 표를 쓰지 않고 {@code CODE_EXPIRED}다(06 ACC-5c,
   * decisions 2026-11-26).
   *
   * @throws BusinessException {@code CODE_EXPIRED}
   */
  public void reset(PasswordResetRequest request) {
    // 표가 없는 요청이 해시까지 오지 않게 먼저 본다. 쓰는 것은 아래 트랜잭션에서 한다
    verifications.checkTicket(request.verificationTicket(), VerificationPurpose.RESET);
    String passwordHash = passwordEncoder.encode(request.newPassword());

    transaction.executeWithoutResult(status -> {
      String phone =
          verifications.useTicket(request.verificationTicket(), VerificationPurpose.RESET);
      // 같은 번호로 가입(ACC-3a 번호 가져가기)이 동시에 와도 회원을 한 번만 정한다(가입과 같은 번호 잠금, 05)
      verificationRows.lockPhone(keys.phoneLockKey(phone));
      UUID memberId =
          members
              .reset(phone, passwordHash)
              .orElseThrow(() -> new BusinessException(ErrorCode.CODE_EXPIRED));
      tokens.revokeAll(memberId);
    });
  }
}
