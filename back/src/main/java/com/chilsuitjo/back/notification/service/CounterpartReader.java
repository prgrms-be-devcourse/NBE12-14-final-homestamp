package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RoomParties;
import java.util.UUID;

/**
 * 호실 두 자리의 상태를 읽는 포트. notification이 두고 room(A)이 구현한다(decisions 2026-11-17·11-19, 09 3장 "기다림·통지").
 * notification은 도메인을 부르지 않아서 이렇게 받는다.
 *
 * <p>쓰는 곳: 응답 요청 열기·문자 만들기(상대 연결 상태·문자 설정·수신 거부·가린 이름), 정산 {@code load}(상대 공유 중단·탈퇴), 수리 {@code
 * load}(내 공유 중단 시각). 이름은 가칭이다. 내 측 값도 돌려준다.
 *
 * <p>호실 기록 행을 잠근 뒤(pipeline 1단계) 같은 트랜잭션에서 부른다. 트랜잭션을 따로 열지 않는다.
 */
public interface CounterpartReader {

  /** {@code mySide}가 행동하는 측이다. 호실이 없으면 {@code NOT_FOUND}. */
  RoomParties read(UUID roomRecordId, PartySide mySide);
}
