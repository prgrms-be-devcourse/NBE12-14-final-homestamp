package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.correction.dto.AppliedVersion;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
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
   * 판은 그대로인데 이 기록의 확인 전 고친 내용이 생기거나 바뀌었다: 도장 뒤 고친 내용을 요청함, 거절된 것을 다시 보냄, 상대가 보류·이견으로 거절함. 판이 바뀌는
   * 때({@link #applyNow}·{@link #mergeConfirmed})에는 부르지 않는다. {@code save} 안에서 부른다.
   *
   * <p>기본은 아무것도 하지 않는다. 정산 송금처럼 기록이 바뀌면 같은 트랜잭션에서 해야 할 일이 있는 기록만 구현한다(정산 모습 번호, 끝내기 요청 닫기, 06
   * SET-5b).
   */
  default void pendingChanged(UUID targetId) {}

  /** 확인할 때 본 판의 지문 재료({@code RESPOND_CORRECTION}([모두 확인] 포함)의 {@code content_digest}). */
  DigestInput digestOf(UUID versionId);

  /**
   * 이 기록의 마지막 다시 요청 시각. [사진 더해서 다시 요청]의 7일은 같은 기록의 다시 요청끼리 함께 센다(06 0-1 {@code rerequest.interval}
   * "한 기록에", #278): 수리 건이면 [보충하고 다시 확인 요청]·[상황이 바뀌었어요]·고친 내용의 다시 요청 중 가장 최근. 없으면 null. 행동 기록을 읽으므로
   * 트랜잭션 안(pipeline의 {@code load}, 조회 서비스)에서만 부른다.
   */
  Instant lastRerequestAt(UUID targetId);

  /**
   * 거절된 고친 내용을 다시 보낼 때 더한 근거 사진을 붙인다({@code RESUBMIT_CORRECTION}). {@code correction}은 아래층이라 {@code
   * photo}를 부를 수 없어서, 대상 도메인이 {@code PhotoService.attach}로 붙인다(05 {@code
   * photo.correction_request_id}, decisions 2026-11-17). 사진 단계는 그 기록이 원래 쓰는 단계다(수리 건 내용이면 보충과 같은
   * {@code BEFORE}, #278). 장수 한도도 그 기록의 한도에 함께 센다. 표가 없으면 아무것도 하지 않고 빈 목록이다.
   *
   * @param command 이 다시 요청 행동(보는 사람·호실 확인과 행동 시각에 씀)
   * @param correctionRequestId 새로 만든 고친 내용(먼저 DB에 내보내 있어야 한다)
   * @return 붙인 사진의 지문({@code SavedState.withPhotos}로 넘김)
   */
  List<PhotoDigest> attachEvidence(
      ActionCommand command, UUID targetId, UUID correctionRequestId, List<String> uploadTickets);
}
