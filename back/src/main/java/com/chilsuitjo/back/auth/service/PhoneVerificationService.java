package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.dto.VerificationTicket;
import com.chilsuitjo.back.auth.entity.PhoneVerification;
import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.auth.rule.PhoneNumber;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 휴대폰 인증(06 ACC-1e~l, 05 {@code phone_verification}). 인증번호를 만들고, 확인해 인증 표를 주고, 가입·재설정·번호 변경이 표를 한
 * 번만 쓰게 한다.
 *
 * <p><b>문자 보내기는 아직 없다.</b> 04 4-2대로 {@link #issue}가 인증 행을 커밋한 뒤 notification의 인증 문자 바로 보내기(D 계약, 가칭
 * {@code VerificationSmsSender})를 불러야 한다. 그 계약이 오면 발송 흐름에서 {@link #issue} → 보내기 → 실패면 {@link
 * #forgetFailedSend}를 잇는다.
 */
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PhoneVerificationRepository verifications;
  private final VerificationKeys keys;
  private final VerificationSettings settings;
  private final TransactionTemplate transaction;
  private final Clock clock;

  /**
   * 인증번호를 만든다(06 ACC-1e·1g·1h·1i·1k). 번호 잠금 안에서 다시 받기 간격(같은 번호·목적) → 하루 인증 횟수(같은 번호, 목적 구분 없음) →
   * 서비스 전체 문자 한도({@code sms-total})를 차례로 보고, 같은 진행의 이전 번호를 만료시킨 뒤 새 행을 만든다(05). 원문 번호는 돌려주기만 하고
   * 저장하지 않는다.
   *
   * <p>잠금을 기다리는 동안 시간이 지나도 한 날짜·한 시각으로 처리한다. 번호 잠금과 같은 진행의 이전 행 잠금을 먼저 잡고 시각을 읽는다. 그 뒤 {@code
   * sms-total} 줄은 다른 번호의 요청과 같이 쓰여 또 기다릴 수 있어서, 센 뒤 시각을 다시 읽어 한국 날짜가 바뀌었으면 이 트랜잭션을 통째로 되돌리고(센 한도도
   * 함께) 새 날짜로 다시 한다. 전날 한도에 이미 닿았어도 날짜부터 다시 본다. 새 행의 생성·만료 시각과 이전 행의 만료 시각은 다시 읽은 시각 하나로 정한다. 그래서
   * 트랜잭션 밖에서만 부를 수 있고, 안에서 부르면 {@code IllegalStateException}이다.
   *
   * @throws BusinessException {@code RATE_LIMITED}(다시 받기 간격, 남은 시간을 {@code Retry-After}로), {@code
   *     DAILY_LIMIT}(하루 인증 횟수·전체 문자 한도), {@code VALIDATION_FAILED}(번호 모양)
   */
  public IssuedCode issue(String rawPhone, VerificationPurpose purpose) {
    // 날짜가 바뀌면 되돌리고 다시 하고, 인증 행을 커밋한 뒤 문자를 보내야 해서 바깥 트랜잭션 안에서는 부를 수 없다
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("인증번호 발급은 트랜잭션 밖에서 부른다");
    }
    String phone = PhoneNumber.normalize(rawPhone);
    for (int attempt = 0; attempt < 3; attempt++) {
      IssuedCode issued = transaction.execute(status -> issueOnSameDay(phone, purpose, status));
      if (issued != null) {
        return issued;
      }
    }
    throw new IllegalStateException("인증번호를 만드는 동안 날짜가 계속 바뀌었다");
  }

  /** 처리 중 한국 날짜가 바뀌면 되돌리고 비어 있다. */
  private IssuedCode issueOnSameDay(
      String phone, VerificationPurpose purpose, TransactionStatus status) {
    verifications.lockPhone(keys.phoneLockKey(phone));
    verifications.lockPending(phone, purpose);
    // 잠금을 기다리는 동안 시간이 지나거나 한국 날짜가 바뀔 수 있어, 시각은 잠금을 얻은 뒤 읽는다
    Instant now = clock.instant();

    verifications
        .findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
        .ifPresent(last -> {
          Instant next = last.getCreatedAt().plus(settings.resendInterval());
          if (now.isBefore(next)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, Duration.between(now, next));
          }
        });

    LocalDate today = LocalDate.ofInstant(now, settings.zone());
    Instant startOfDay = today.atStartOfDay(settings.zone()).toInstant();
    if (verifications.countByPhoneAndCreatedAtGreaterThanEqual(phone, startOfDay)
        >= settings.dailyPhone()) {
      throw new BusinessException(ErrorCode.DAILY_LIMIT);
    }
    if (settings.smsDailyTotal() < 1) {
      throw new BusinessException(ErrorCode.DAILY_LIMIT);
    }
    boolean counted = verifications.countSmsTotal(today, settings.smsDailyTotal()) > 0;

    // sms-total 줄을 기다렸을 수 있다. 한도에 닿았든 아니든 날짜부터 다시 본다. 바뀌었으면 센 한도까지 되돌리고 새 날짜로 다시 한다
    Instant issuedAt = clock.instant();
    if (!LocalDate.ofInstant(issuedAt, settings.zone()).equals(today)) {
      status.setRollbackOnly();
      return null;
    }
    if (!counted) {
      throw new BusinessException(ErrorCode.DAILY_LIMIT);
    }
    verifications.expirePending(phone, purpose, issuedAt);
    UUID id = Uuids.v7();
    String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
    verifications.save(
        new PhoneVerification(
            id,
            phone,
            purpose,
            keys.codeHash(id, code),
            issuedAt,
            issuedAt.plus(settings.codeTtl())));
    return new IssuedCode(id, phone, code);
  }

  /**
   * 솔라피가 실패로 답한 발송의 행을 지운다(06 ACC-1j, 05). 하루 횟수·다시 받기 간격에 세지 않게 되고, {@code sms-total}은 되돌리지 않는다.
   * 다시 받기였다면 이전 번호는 이미 만료돼 있다.
   */
  @Transactional
  public void forgetFailedSend(UUID verificationId) {
    verifications
        .findById(verificationId)
        .filter(v -> v.getVerifiedAt() == null)
        .ifPresent(verifications::delete);
  }

  /**
   * 인증번호를 확인하고 인증 표를 준다(06 ACC-1e·1g·1l). 순서는 05와 같다.
   *
   * <ol>
   *   <li>같은 진행에 나중 행이 있으면 {@code CODE_REPLACED}(틀린 횟수 그대로)
   *   <li>만료됐거나 {@code verify.attempts.max}번 틀렸으면 {@code CODE_EXPIRED}(다시 받아야 함)
   *   <li>맞으면 인증. 표는 인증한 때부터 {@code verify.ticket.ttl}
   *   <li>틀렸는데 같은 진행의 바뀐 이전 번호와 같으면 {@code CODE_REPLACED}(틀린 횟수 그대로)
   *   <li>그 밖에는 틀린 횟수를 올리고 {@code CODE_INVALID}
   * </ol>
   *
   * <p>없는 행과 이미 인증한 행은 {@code CODE_EXPIRED}다. 틀린 횟수는 오류를 돌려줘도 남아야 해서, 확인은 따로 커밋한 뒤 오류를 던진다.
   */
  public VerificationTicket verify(UUID verificationId, String code) {
    VerifyResult result = transaction.execute(status -> check(verificationId, code));
    if (result.error() != null) {
      throw new BusinessException(result.error());
    }
    return result.ticket();
  }

  private VerifyResult check(UUID verificationId, String code) {
    PhoneVerification row = verifications.findForUpdate(verificationId).orElse(null);
    // 행 잠금을 기다리는 동안 만료될 수 있어, 시각은 잠금을 얻은 뒤 읽는다
    Instant now = clock.instant();
    if (row == null || row.getVerifiedAt() != null) {
      return VerifyResult.failed(ErrorCode.CODE_EXPIRED);
    }
    if (verifications.existsByPhoneAndPurposeAndCreatedAtAfter(
        row.getPhone(), row.getPurpose(), row.getCreatedAt())) {
      return VerifyResult.failed(ErrorCode.CODE_REPLACED);
    }
    if (!now.isBefore(row.getExpiresAt()) || row.getAttemptCount() >= settings.attemptsMax()) {
      return VerifyResult.failed(ErrorCode.CODE_EXPIRED);
    }
    if (matches(row, code)) {
      Instant ticketExpiresAt = now.plus(settings.ticketTtl());
      row.markVerified(now, ticketExpiresAt);
      return VerifyResult.verified(
          new VerificationTicket(keys.signTicket(row.getId()), ticketExpiresAt));
    }
    boolean replacedCode =
        verifications
            .findByPhoneAndPurposeAndCreatedAtBeforeAndVerifiedAtIsNull(
                row.getPhone(), row.getPurpose(), row.getCreatedAt())
            .stream()
            .anyMatch(earlier -> matches(earlier, code));
    if (replacedCode) {
      return VerifyResult.failed(ErrorCode.CODE_REPLACED);
    }
    row.recordWrongAttempt();
    return VerifyResult.failed(ErrorCode.CODE_INVALID);
  }

  private boolean matches(PhoneVerification row, String code) {
    return code != null
        && VerificationKeys.sameHash(row.getCodeHash(), keys.codeHash(row.getId(), code));
  }

  /**
   * 바꾸는 일(가입·비밀번호 재설정·번호 변경)에 인증 표를 한 번 쓰고 인증한 번호를 돌려준다(06 ACC-1f·1l). 그 작업이 성공하는 트랜잭션 안에서 불러야 하고,
   * 작업이 실패해 롤백되면 표는 그대로 남는다. 행을 잠근 뒤 서명·목적·인증 여부·유효 시간·쓴 적 없음을 한 문장으로 확인해, 같은 표로 두 요청이 동시에 와도 하나만
   * 성공한다.
   *
   * @throws BusinessException 쓸 수 없는 표면 {@code CODE_EXPIRED}
   * @throws IllegalArgumentException 아이디 찾기({@link #readTicket}을 쓴다)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public String useTicket(String ticket, VerificationPurpose purpose) {
    if (!purpose.usedOnce()) {
      throw new IllegalArgumentException(purpose + " 인증 표는 쓴 것으로 바꾸지 않는다. readTicket을 쓴다");
    }
    UUID id = keys.readTicket(ticket).orElseThrow(() -> expired());
    // 먼저 행을 잠그고 잠금을 얻은 뒤의 시각으로 확인한다. 조건부 UPDATE에 시각을 넘긴 채 잠금을 기다리면 기다리는 동안 만료된 표가 통과한다
    if (verifications.findForUpdate(id).isEmpty()
        || verifications.consume(id, purpose, clock.instant()) == 0) {
      throw expired();
    }
    return verifications.findById(id).orElseThrow(() -> expired()).getPhone();
  }

  /**
   * 아이디 찾기의 인증 표를 확인하고 인증한 번호를 돌려준다. 보기만 해서 쓴 것으로 바꾸지 않고, 유효 시간 안에는 다시 쓸 수 있다(06 ACC-1f, 4-1).
   *
   * @throws BusinessException 쓸 수 없는 표면 {@code CODE_EXPIRED}
   * @throws IllegalArgumentException 한 번만 쓰는 목적({@link #useTicket}을 쓴다)
   */
  @Transactional(readOnly = true)
  public String readTicket(String ticket, VerificationPurpose purpose) {
    if (purpose.usedOnce()) {
      throw new IllegalArgumentException(purpose + " 인증 표는 한 번만 쓴다. useTicket을 쓴다");
    }
    Instant now = clock.instant();
    return keys.readTicket(ticket)
        .flatMap(verifications::findById)
        .filter(v -> v.getPurpose() == purpose)
        .filter(v -> v.getVerifiedAt() != null && v.getConsumedAt() == null)
        .filter(v -> now.isBefore(v.getExpiresAt()))
        .map(PhoneVerification::getPhone)
        .orElseThrow(() -> expired());
  }

  private static BusinessException expired() {
    return new BusinessException(ErrorCode.CODE_EXPIRED);
  }

  private record VerifyResult(ErrorCode error, VerificationTicket ticket) {

    static VerifyResult failed(ErrorCode error) {
      return new VerifyResult(error, null);
    }

    static VerifyResult verified(VerificationTicket ticket) {
      return new VerifyResult(null, ticket);
    }
  }
}
