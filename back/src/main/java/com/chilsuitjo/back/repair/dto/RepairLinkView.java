package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 링크를 받은 측에게 보이는 수리 건 하나(#303 B 답 7206508708, #443, 06 LINK-1a). 링크 열람자는 회원이 아니라서 회원 권한 검사 없이 읽는다.
 *
 * <p>회원 상세({@link RepairCaseDetail})와 따로 둔다: 누를 수 있는 행동·재요청 시각·호실 ID·부위 ID·돈(부담 상태 포함)·업체·전문가 이름은
 * 담지 않는다(LINK-1a "그 기록 하나만", 1d "돈은 있다는 것만", 2a). 화면 문구는 만들지 않고 enum으로 주며, 문구는 링크 쪽이 만든다(08). 사진
 * 주소도 만들지 않고 대상만 준다. 주소는 링크 쪽이 {@code PhotoService.linkPhotos}로 받는다(점검의 {@code InspectionForLink}와
 * 같은 방식).
 *
 * <p>보이는 범위는 회원 상세와 같다: 공유를 그만둔 뒤면 상태·판·고친 내용 응답은 그 시각까지의 행동과 보는 측 자신의 행동으로 다시 계산한 값이고, 그 뒤 상대가 남긴
 * 원인 설명·진행 상황·고친 내용·응답은 없다(03 3-1, 6-6 9번, 불변식 18).
 *
 * @param registeredBy 등록한 측. 하자에 답하는 쪽은 그 반대 측이다
 * @param status 이 측에게 보이는 수리 진행
 * @param closeRequestedBy 종료 확인 대기면 종료를 요청한 측, 아니면 null
 * @param closedBy 처리 완료면 누가 확인해 끝났나, 아니면 null
 * @param defectAck 이 측에게 보이는 하자 인정
 * @param createdAt 등록한 시각
 * @param asOf 공유를 그만둔 뒤면 그 시각("○월 ○일 기준"), 아니면 null
 * @param partName 보이는 판의 부위 이름(숨긴 부위도 지난 기록이면 보임)
 * @param title 보이는 판의 제목
 * @param description 보이는 판의 설명. 없으면 null
 * @param photoTarget 수리 건 사진(하자 등록·보충·사진 추가)의 대상({@code REPAIR_CASE})
 * @param corrections 내용의 고친 내용(만든 순서). 확인 전(기다림·보류·이견)과 지난 이력(확인됨·거둠·대신됨·닫힘)이 함께 있고 상태로 나눈다
 * @param causeNotes 원인 설명(남긴 순서). 측마다 지금 설명만이다(고치기 전 설명은 경과에 남음)
 * @param progressNotes 진행 상황(남긴 순서)
 * @param history 이 측에게 보이는 이 수리 건과 그 원인 설명·진행 상황·고친 내용의 행동. 시각 순
 */
public record RepairLinkView(
    PartySide registeredBy,
    RepairStatus status,
    PartySide closeRequestedBy,
    ClosedBy closedBy,
    AckState defectAck,
    Instant createdAt,
    Instant asOf,
    String partName,
    String title,
    String description,
    PhotoTarget photoTarget,
    List<Correction> corrections,
    List<CauseNote> causeNotes,
    List<ProgressNote> progressNotes,
    List<History> history) {

  public RepairLinkView {
    Objects.requireNonNull(registeredBy, "registeredBy");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(defectAck, "defectAck");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(partName, "partName");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(photoTarget, "photoTarget");
    corrections = List.copyOf(corrections);
    causeNotes = List.copyOf(causeNotes);
    progressNotes = List.copyOf(progressNotes);
    history = List.copyOf(history);
  }

  /**
   * 고친 내용 하나(03 G). 링크 화면은 앞모습({@code title}·{@code description}·{@code partName})과 새 값을 비교해 보인다.
   *
   * @param id 고친 내용 ID. 경과·사진을 잇는 데만 쓰고 링크 응답에는 내보내지 않는다
   * @param field 칸({@code roomPartId}·{@code title}·{@code description})
   * @param newValue 새 값의 글자. 부위를 고쳤으면 새 부위 이름이고, 설명을 비우면 null
   * @param requestedBy 고친 측
   * @param reason 고친 이유
   * @param status 이 측에게 보이는 상태
   * @param answer 이 측에게 보이는 상대 응답(확인 전이면 {@code PENDING})
   * @param createdAt 고친 시각
   * @param photoTarget 다시 보낼 때 더한 근거 사진의 대상({@code CORRECTION_REQUEST})
   */
  public record Correction(
      UUID id,
      String field,
      String newValue,
      PartySide requestedBy,
      String reason,
      CorrectionStatus status,
      AckState answer,
      Instant createdAt,
      PhotoTarget photoTarget) {}

  /**
   * 원인 설명 하나(03 D-2). 업체·전문가 이름은 담지 않는다(06 LINK-2a, 링크에는 이름이 없음). 근거 종류만 준다.
   *
   * @param id 설명 ID. 경과·사진을 잇는 데만 쓴다
   * @param side 남긴 측
   * @param basis 근거 종류
   * @param answer 이 측에게 보이는 상대 응답. 아직 없으면 {@code PENDING}
   * @param photoTarget 근거 사진의 대상({@code CAUSE_NOTE})
   */
  public record CauseNote(
      UUID id,
      PartySide side,
      String text,
      CauseBasis basis,
      Instant createdAt,
      AckState answer,
      PhotoTarget photoTarget) {}

  /**
   * 진행 상황 하나(06 REP-6).
   *
   * @param id 진행 상황 ID. 경과·사진을 잇는 데만 쓴다
   * @param side 남긴 측
   * @param text 글. 사진만 남겼으면 빈 글자
   * @param asksCheck [상황이 바뀌었어요]로 확인을 요청했는지
   * @param answer 이 측에게 보이는 상대 응답. 확인 요청이 아니면 null, 아직 답이 없으면 {@code PENDING}
   * @param photoTarget 진행 상황 사진의 대상({@code PROGRESS_NOTE})
   */
  public record ProgressNote(
      UUID id,
      PartySide side,
      String text,
      boolean asksCheck,
      Instant createdAt,
      AckState answer,
      PhotoTarget photoTarget) {}

  /**
   * 경과 한 줄. 행동 번호·해시·사유·회원 식별값은 담지 않는다(03 3-1).
   *
   * @param side 행동한 측. 서버 행동이면 null
   * @param targetKind 행동 대상의 종류({@code REPAIR_CASE}·{@code CAUSE_NOTE}·{@code PROGRESS_NOTE}·{@code
   *     CORRECTION_REQUEST})
   * @param targetId 행동 대상. 수리 건 자체면 null이고, 그 밖에는 위 목록의 {@code id}와 같다(링크 응답에는 내보내지 않음)
   */
  public record History(
      ActionType type, PartySide side, Instant at, TargetKind targetKind, UUID targetId) {}
}
