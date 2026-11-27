package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.SentRequest;
import java.util.List;
import java.util.UUID;

/**
 * 보낸 측이 보는 통지 상태(06 NOTI-4a, 4-9 "조회 응답 안"). 경과(REP-11)·내려받기처럼 행동 목록을 보이는 조회가 행동의 대상·종류로 붙여 쓴다.
 * 아무것도 바꾸지 않는다.
 *
 * <p>받는 쪽에는 통지 상태가 보이지 않으므로(NOTI-4a) 보는 측이 <b>요청한</b> 요청만 준다. 보는 사람이 그 호실의 당사자인지는 부르는 쪽이 본다.
 *
 * <p><b>지금 값만 준다. 공유를 그만둔 호실({@code ActionVisibility.Cutoff}가 있는 보는 사람)에는 아직 쓰지 않는다.</b> 그만둔 뒤의
 * 열람·답·취소가 상태·한 줄·시각에 그대로 들어 있어 03 3-1·불변식 18("그 시각까지의 행동으로 다시 계산")을 어긴다. 그 시각의 상태는 D의 후속 창구가
 * 준다(#370 B-4에서 정하는 중. 제안은 {@code sentBy(roomRecordId, side, asOf, sees)}: 상태 계산은 notification, 보이는
 * 범위는 부르는 쪽). 그 창구가 병합되기 전에는 경과·내려받기에 이 창구를 연결하지 않는다(#360 리뷰). 부르는 쪽이 시각 칸을 직접 잘라 상태를 다시 만들지 않는다(통지
 * 규칙은 여기 하나).
 */
public interface NoticeStatusQuery {

  /**
   * 호실에서 {@code side}가 요청한 응답 요청 전부(상태 무관, 만든 순서)와 요청마다 통지 상태 한 줄·이력. 다른 호실의 요청·통지는 들어가지 않는다. 요청이
   * 없으면 빈 목록.
   *
   * <p>이력에는 상대에게 간 요청 통지({@code REQUEST})와 재통지({@code RENOTIFY})만 담는다. 같은 요청에 걸린 알려 줌({@code
   * INFO})은 요청한 측에게 가는 것일 수 있어 "상대에게 보낸 통지"로 보이지 않는다. 초대 문자는 아직 요청에 걸지 않는다(#100).
   */
  List<SentRequest> sentBy(UUID roomRecordId, PartySide side);
}
