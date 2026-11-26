package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.SignupRequest;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.entity.MemberAcknowledgement;
import com.chilsuitjo.back.member.repository.MemberAcknowledgementRepository;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.SignupAcknowledgements;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 가입에서 회원 쪽 일(06 ACC-1·3). 인증 표 소비와 번호 잠금은 부르는 쪽(auth {@code SignupService})이 같은 트랜잭션에서 먼저 한다. */
@Service
@RequiredArgsConstructor
public class MemberService {

  /** V1 이메일 유니크 인덱스. 같은 이메일 가입이 동시에 와서 미리 본 검사를 둘 다 통과했을 때 여기서 걸린다. */
  private static final String EMAIL_UNIQUE_INDEX = "ux_member_email_lower";

  private final MemberRepository members;
  private final MemberAcknowledgementRepository acknowledgements;
  private final Clock clock;

  /**
   * 이 이메일로 가입할 수 있는지 본다(06 ACC-1c). 대소문자만 다른 이메일도 같은 이메일이다.
   *
   * <p>같은 이메일로 동시에 가입하면 둘 다 통과할 수 있다. 그때는 {@link #register}에서 {@code ux_member_email_lower}가 하나를
   * 막는다.
   *
   * @throws BusinessException {@code EMAIL_TAKEN}
   */
  @Transactional(readOnly = true)
  public void requireEmailAvailable(String email) {
    if (members.existsByEmailIgnoringCase(email)) {
      throw new BusinessException(ErrorCode.EMAIL_TAKEN);
    }
  }

  /**
   * 가입 확인 항목 세 개가 모두 체크됐는지와 문구 판을 본다(06 ACC-1a, CONS-1a). 부르는 쪽(auth {@code SignupService})이 인증 표와
   * 이메일보다 먼저 부른다. 아무것도 쓰지 않는다.
   *
   * @throws BusinessException {@code AGE_CONFIRM_REQUIRED}, {@code TERMS_REQUIRED}, {@code
   *     VALIDATION_FAILED}(문구 판이 비었거나 칸보다 김)
   */
  public void requireSignupAcknowledgements(SignupRequest request) {
    request.acknowledgements();
  }

  /**
   * 인증을 마친 번호로 회원을 만들고 확인 기록 세 줄을 남긴다(06 ACC-1b, ACC-3a). 같은 번호를 인증한 다른 계정이 있으면 그 계정의 번호를 먼저 비운다.
   * 인증 표를 쓰는 트랜잭션 안에서만 부르고, 부르는 쪽이 그 번호의 잠금을 잡고 있어야 한다(같은 번호로 동시에 가입해도 번호 유니크에 걸리지 않게). 확인 항목은
   * {@link #requireSignupAcknowledgements}로 먼저 본 요청이어야 한다.
   *
   * @param passwordHash 부르는 쪽이 트랜잭션 전에 만든 해시
   * @param phone 인증 표에서 읽은 정규화된 번호
   * @return 만든 회원 ID
   * @throws BusinessException {@code EMAIL_TAKEN}(같은 이메일 가입이 동시에 와서 유니크 인덱스에 걸림)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID register(SignupRequest request, String passwordHash, String phone) {
    SignupAcknowledgements confirmed = request.acknowledgements();
    String email = request.email();
    String displayName = request.displayName();
    Instant now = clock.instant();
    members.findByPhone(phone).ifPresent(Member::releasePhone);
    // Hibernate는 INSERT를 UPDATE보다 먼저 내보내므로, 번호를 비운 것을 먼저 반영해야 새 회원의 번호가 유니크에 걸리지 않는다
    members.flush();

    Member member;
    try {
      member = members.saveAndFlush(new Member(email, passwordHash, displayName, phone, now, now));
    } catch (DataIntegrityViolationException e) {
      if (String.valueOf(e.getMessage()).contains(EMAIL_UNIQUE_INDEX)) {
        throw new BusinessException(ErrorCode.EMAIL_TAKEN);
      }
      throw e;
    }
    recordSignupAcknowledgements(member.getId(), confirmed, now);
    return member.getId();
  }

  /** 가입 확인 기록 세 줄(06 ACC-1b). 확인한 시각은 가입 시각이다. */
  private List<MemberAcknowledgement> recordSignupAcknowledgements(
      UUID memberId, SignupAcknowledgements confirmed, Instant now) {
    List<MemberAcknowledgement> rows =
        confirmed.textVersions().entrySet().stream()
            .map(e -> new MemberAcknowledgement(memberId, e.getKey(), e.getValue(), now, now))
            .toList();
    return acknowledgements.saveAll(rows);
  }
}
