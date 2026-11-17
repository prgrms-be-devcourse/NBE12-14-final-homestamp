package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.correction.dto.AppliedVersion;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.global.common.PartySide;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 고칠 수 있는 기록이 구현한다(09 3장 "고치기": {@code correction}은 이 인터페이스만 두고 각 도메인이 구현).
 *
 * <p>구현: repair 수리 건(B), payment 지불·settlement 송금(C), inspection 항목·room 입주일(A). {@code
 * correction}은 이 인터페이스만 알고 Spring이 구현들을 {@code List<Correctable>}로 넣어 준다. 그래서 코드 의존은 도메인 →
 * correction 한 방향이다.
 */
public interface Correctable {

  CorrectionTargetKind kind();

  CorrectableSnapshot load(UUID targetId);

  /** 도장 전: 바로 새 판을 만들고 지금 판·도장 판 가리킴을 함께 옮긴다. */
  AppliedVersion applyNow(UUID targetId, Map<String, Object> changes, PartySide bySide);

  /** 도장 뒤 상대가 확인: "지금 도장 찍힌 판 + 이 칸만" 합쳐 새 판. 기준 판 뒤 같은 칸이 바뀌었으면 합치지 않는다. */
  AppliedVersion mergeConfirmed(UUID targetId, UUID baseVersionId, String field, Object newValue);

  /**
   * 확인할 때 본 판의 지문 재료({@code RESPOND_CORRECTION}·{@code ACCEPT_ALL_CORRECTIONS}의 {@code
   * content_digest}).
   */
  DigestInput digestOf(UUID versionId);

  /**
   * 거절된 고친 내용을 다시 보낼 때 더한 근거 사진을 붙인다({@code RESUBMIT_CORRECTION}). {@code correction}은 아래층이라 {@code
   * photo}를 부를 수 없어서, 대상 도메인이 {@code PhotoService.attach}로 붙인다(05 {@code
   * photo.correction_request_id}, decisions 2026-11-17).
   */
  void attachEvidence(UUID targetId, UUID correctionRequestId, List<String> uploadTickets);
}
