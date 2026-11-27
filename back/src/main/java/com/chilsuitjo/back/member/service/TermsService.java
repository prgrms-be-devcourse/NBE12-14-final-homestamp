package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.PendingAcknowledgement;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.entity.MemberAcknowledgement;
import com.chilsuitjo.back.member.repository.MemberAcknowledgementRepository;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.AcknowledgementKind;
import com.chilsuitjo.back.member.rule.MemberStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 바뀐 약관·확인 항목 다시 확인(06 CONS-4, 03 6-2 "문구가 바뀌면", decisions 2026-11-27).
 *
 * <p>종류마다 지금 판({@link TermsVersions})으로 확인한 기록이 없으면 다시 확인할 대상이다. 세 종류 모두 같은 규칙이다. 대상이 남은 회원은 상태를
 * 바꾸는 요청이 {@code TERMS_UPDATE_REQUIRED}로 막힌다({@code TermsGateInterceptor}). 조회와 권리 행사는 막지 않는다.
 */
@Service
public class TermsService {

  private final MemberRepository members;
  private final MemberAcknowledgementRepository acknowledgements;
  private final TermsVersions versions;
  private final Clock clock;
  private final boolean gateEnabled;

  /**
   * @param gateEnabled {@code terms.gate.enabled}. 끄면 {@link #requireUpToDate}가 막지 않는다. 확인 기록 없이
   *     회원을 바로 넣는 다른 기능의 테스트에서만 끈다(테스트 클래스패스 설정). 운영 기본값은 켬
   */
  public TermsService(
      MemberRepository members,
      MemberAcknowledgementRepository acknowledgements,
      TermsVersions versions,
      Clock clock,
      @Value("${terms.gate.enabled:true}") boolean gateEnabled) {
    this.members = members;
    this.acknowledgements = acknowledgements;
    this.versions = versions;
    this.clock = clock;
    this.gateEnabled = gateEnabled;
  }

  /** 다시 확인할 항목(종류 순). 없으면 빈 목록. */
  @Transactional(readOnly = true)
  public List<PendingAcknowledgement> pending(UUID memberId) {
    return pendingOf(acknowledgements.findByMemberId(memberId));
  }

  /**
   * 다시 확인할 항목이 남았으면 막는다. 탈퇴했거나 없는 회원은 여기서 판단하지 않는다(그 요청이 {@code LOGIN_REQUIRED}로 끝나게).
   *
   * @throws BusinessException {@code TERMS_UPDATE_REQUIRED}
   */
  @Transactional(readOnly = true)
  public void requireUpToDate(UUID memberId) {
    if (!gateEnabled) {
      return;
    }
    boolean active =
        members.findById(memberId).map(m -> m.getStatus() == MemberStatus.ACTIVE).orElse(false);
    if (active && !pending(memberId).isEmpty()) {
      throw new BusinessException(ErrorCode.TERMS_UPDATE_REQUIRED);
    }
  }

  /**
   * 보낸 항목을 지금 판으로 확인한 기록으로 덧붙인다. 이미 그 판으로 확인한 항목은 다시 남기지 않고 그대로 성공한다.
   *
   * <p>같은 회원의 다시 확인이 동시에 와도 기록이 두 줄이 되지 않게, 회원 행을 잠근 뒤({@code FOR UPDATE}) 기록을 읽고 남긴다. 확인 기록에는 유니크
   * 제약이 없다(V1).
   *
   * @throws BusinessException {@code VALIDATION_FAILED}(같은 종류 두 번), {@code VERSION_CONFLICT}(지금 판이
   *     아님), {@code LOGIN_REQUIRED}(탈퇴했거나 없는 회원)
   */
  @Transactional
  public void acknowledge(UUID memberId, Map<AcknowledgementKind, String> textVersions) {
    if (textVersions == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    versions.requireCurrent(textVersions);
    Member member =
        members
            .lockById(memberId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    Set<AcknowledgementKind> missing =
        pendingOf(acknowledgements.findByMemberId(member.getId())).stream()
            .map(PendingAcknowledgement::kind)
            .collect(Collectors.toSet());
    Instant now = clock.instant();
    List<MemberAcknowledgement> rows =
        textVersions.entrySet().stream()
            .filter(e -> missing.contains(e.getKey()))
            .map(e -> new MemberAcknowledgement(member.getId(), e.getKey(), e.getValue(), now, now))
            .toList();
    acknowledgements.saveAll(rows);
  }

  private List<PendingAcknowledgement> pendingOf(List<MemberAcknowledgement> rows) {
    Set<String> confirmed =
        rows.stream().map(r -> r.getKind() + ":" + r.getTextVersion()).collect(Collectors.toSet());
    return Arrays.stream(AcknowledgementKind.values())
        .filter(kind -> !confirmed.contains(kind + ":" + versions.current(kind)))
        .map(kind -> new PendingAcknowledgement(kind, versions.current(kind)))
        .toList();
  }
}
