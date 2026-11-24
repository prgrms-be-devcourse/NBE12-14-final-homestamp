package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.entity.Supplement;
import com.chilsuitjo.back.action.repository.SupplementRepository;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [보충하고 다시 확인 요청]의 설명을 남기는 창구(#155, 03 G). 수리 건 하자({@code SUPPLEMENT}), 지불({@code
 * SUPPLEMENT_PAYMENT}), 점검 항목({@code SUPPLEMENT_ITEM})이 각자의 {@code StateStore.save} 안에서 부른다.
 *
 * <p>규칙(기록한 측만, 보류·이견일 때만, 근거 필수, 7일에 한 번)은 보지 않는다. 그건 {@code apply}의 {@code
 * AnswerSupport.rerequest}와 서비스의 {@code AnswerSupport.requireEvidence}가 먼저 본다. 사진은 여기서 붙이지 않고 부르는
 * 쪽이 {@code PhotoService.attach}로 붙여 {@code SavedState.withPhotos}로 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class SupplementWriter {

  /** 보충 행동의 내용 지문 판 종류(05 2-3 「해시 입력 형식」 내용 지문). */
  public static final String CONTENT_KIND = "supplement";

  private final SupplementRepository supplements;
  private final ActionRequestContext requestContext;

  /**
   * 보충 한 줄을 남기고 그 내용 지문 재료를 돌려준다. 부르는 쪽 {@code save}가 {@code SavedState}의 {@code content}로 넘기면 보충
   * 행동의 {@code content_digest}가 된다(새 기록을 만드는 행동은 그 기록, 안내서 3-3). 보충은 판이 하나라 판 번호는 늘 1이다.
   *
   * @param target 수리 건·지불·점검 항목 중 하나
   * @param side 보충한 측. pipeline이 채운 주체의 측({@code command.actor().side()})
   * @param description 덧붙인 설명. 사진만 보충했으면 null(빈 글자는 null로 바꿔 넘긴다)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public DigestInput write(
      UUID roomRecordId, ActionTarget target, PartySide side, String description) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(side, "side");
    UUID id = Uuids.v7();
    UUID repairCaseId = null;
    UUID paymentId = null;
    UUID inspectionItemId = null;
    switch (target.kind()) {
      case REPAIR_CASE -> repairCaseId = target.id();
      case PAYMENT -> paymentId = target.id();
      case INSPECTION_ITEM -> inspectionItemId = target.id();
      default -> throw new IllegalArgumentException("보충할 수 없는 대상이에요: " + target);
    }
    // 시각은 행동 기록과 같은 값이다(save 안에서만 열림, #94).
    supplements.save(
        new Supplement(
            id,
            roomRecordId,
            repairCaseId,
            paymentId,
            inspectionItemId,
            side,
            description,
            requestContext.at()));
    return new DigestInput(
        CONTENT_KIND, id, 1, List.of(new DigestField("description", description)));
  }
}
