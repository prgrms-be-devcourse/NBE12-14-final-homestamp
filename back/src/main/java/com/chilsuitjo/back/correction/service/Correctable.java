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
 *
 * <p>{@link #load}·{@link #normalize}는 pipeline이 호실을 잠근 뒤(그 {@code StateStore.load}에서), 나머지는 {@code
 * save}에서 부른다. 같은 트랜잭션이라 판과 머리 가리킴이 행동 기록과 함께 커밋되거나 함께 되돌려진다. 판을 만들 때 시각은 {@code
 * ActionRequestContext.at()}(행동 시각)을 쓴다.
 */
public interface Correctable {

  CorrectionTargetKind kind();

  /** 기록의 지금 모습. 없는 기록이면 {@code NOT_FOUND}. 호실이 맞는지는 부르는 쪽이 {@code roomRecordId}로 본다. */
  CorrectableSnapshot load(UUID targetId);

  /**
   * 고칠 칸과 새 값을 확인하고 저장할 모양으로 바꾼다(예: 부위 ID 글자 → 그 호실의 보이는 부위인지 확인). 없는 칸·틀린 형식·길이 초과는 {@code
   * VALIDATION_FAILED}, 주민등록번호 형태는 {@code RESIDENT_ID_BLOCKED}. 돌려준 값은 {@link
   * CorrectableSnapshot#fields}와 같은 모양이라 {@code SAME_VALUE}를 그대로 비교할 수 있다.
   *
   * @param memberId 고치는 회원(공유를 그만둔 뒤 그 사람에게 보이는 부위 상태로 보기 위해)
   */
  Map<String, Object> normalize(UUID targetId, Map<String, Object> changes, UUID memberId);

  /** 도장 전: 지금 판에 이 칸들을 합친 새 판을 만들고 지금 판 가리킴을 옮긴다(도장 판은 아직 없음). */
  AppliedVersion applyNow(UUID targetId, Map<String, Object> changes, PartySide bySide);

  /**
   * 도장 뒤 상대가 확인: "지금 도장 찍힌 판 + 이 칸만" 합쳐 새 판을 만들고 지금 판·도장 판 가리킴을 함께 옮긴다. 기준 판 뒤 같은 칸이 바뀌었으면 합치지 않고
   * {@code VERSION_CONFLICT}다.
   */
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
