package com.chilsuitjo.back.room.dto;

import java.util.UUID;

/**
 * 부위가 어느 호실 기록에 속하고 이름이 무엇인지. 다른 도메인과 주고받는 값이다.
 *
 * @param roomRecordId 부위가 속한 호실 기록
 * @param name 부위 이름(예: 천장). 구역은 담지 않는다
 */
public record RoomPartSummary(UUID roomRecordId, String name) {}
