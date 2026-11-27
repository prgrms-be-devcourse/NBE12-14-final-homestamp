package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import java.util.Optional;
import java.util.UUID;

/**
 * 링크 열람에서 점검을 읽는 창구(09 3장 "link → inspection", #303, #395). {@code link}의 {@code
 * LinkContentSource}가 부른다.
 *
 * <p>{@link InspectionQueryService}는 회원 권한 검사를 거치는 창구라 따로 둔다. 링크 열람자는 회원이 아니므로 권한 검사를 거치지 않고 토큰도 받지
 * 않는다. 토큰 확인, 링크 무효·만료·공유 중단, 링크가 이 호실·점검의 것인지, 호실 상태(정산 완료·합류 전 기록)에 따른 막기는 {@code link}가 먼저 본다.
 */
public interface InspectionLinkQuery {

  /**
   * 링크를 받은 측에게 보이는 점검. 보이는 범위는 회원 상세와 같은 판단({@code InspectionSight})을 쓴다. 다음은 모두 빈 값이다(예외를 던지지
   * 않는다).
   *
   * <ul>
   *   <li>없는 점검, 다른 호실의 점검
   *   <li>작성 중인 점검. 보는 측과 상관없다(03 3-1 링크 열람자 "작성 중 점검 ✕"). 공유를 그만둔 시각에 작성 중이었으면 그 뒤 누가 확인 요청을 보냈든
   *       같다
   *   <li>공유를 그만둔 뒤 상대가 시작한 점검(불변식 18)
   * </ul>
   *
   * @param viewerSide 링크를 받은 자리의 측({@code RoomForLink.recipientSide})
   */
  Optional<InspectionForLink> forLink(UUID roomRecordId, UUID inspectionId, PartySide viewerSide);
}
