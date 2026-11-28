package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import java.util.Optional;
import java.util.UUID;

/**
 * 링크 열람에서 수리 건을 읽는 창구(09 3장 "link → repair", #303 B 답 7206508708, #443). {@code link}의 {@code
 * LinkContentSource}가 부른다.
 *
 * <p>{@link RepairQueryService#detail}은 회원 권한 검사를 거치는 창구라 따로 둔다(점검의 {@code InspectionLinkQuery}와
 * 같음). 링크 열람자는 회원이 아니므로 권한 검사를 거치지 않고 토큰도 받지 않는다. 토큰 확인, 링크 무효·만료·공유 중단, 링크가 이 호실·수리 건의 것인지, 호실
 * 상태(정산 완료·합류 전 기록)에 따른 막기는 {@code link}가 먼저 본다.
 */
public interface RepairLinkQuery {

  /**
   * 링크를 받은 측에게 보이는 수리 건. 보이는 범위는 회원 상세와 같은 판단({@code RepairSight}, {@code Replay.fold})을 쓴다. 다음은 모두
   * 빈 값이다(예외를 던지지 않는다).
   *
   * <ul>
   *   <li>없는 수리 건, 다른 호실의 수리 건
   *   <li>보는 측이 볼 수 없는 수리 건: 공유를 그만둔 뒤 상대가 등록한 것(불변식 18)
   * </ul>
   *
   * @param viewerSide 링크를 받은 자리의 측({@code RoomForLink.recipientSide})
   */
  Optional<RepairLinkView> forLink(UUID roomRecordId, UUID repairCaseId, PartySide viewerSide);
}
