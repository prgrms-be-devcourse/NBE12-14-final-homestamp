package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.AwaitingConfirm;
import java.util.List;
import java.util.UUID;

/**
 * 호실에서 확인 요청 상태인 점검을 읽는 포트(#370 A-7, #317). notification이 두고 inspection(A)이 구현한다. notification은 다른
 * 도메인을 부르지 않아서 이렇게 받는다({@link CounterpartReader}와 같은 방식).
 *
 * <p>호실 기록 행을 잠근 뒤 같은 트랜잭션에서 부른다. 확인 요청 상태가 아닌 점검(작성 중·잠김·답을 받은 점검 등)은 주지 않는다. 답을 받은 점검을 주면 그 요청은
 * 이미 닫혀 있어 새 확인 요청이 하나 더 열린다. 없으면 빈 목록이고, 읽지 못하면 예외를 그대로 올린다(빈 목록으로 바꾸지 않음).
 */
public interface InspectionsAwaitingConfirm {

  List<AwaitingConfirm> of(UUID roomRecordId);
}
